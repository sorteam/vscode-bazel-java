package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.jdt.ls.core.internal.handlers.JDTLanguageServer;
import org.eclipse.jdt.ls.core.internal.lsp.ValidateDocumentParams;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/*
    Gets a file back from whichever fallback jdt.ls parked it in, once a real project covers it.

    jdt.ls has two fallbacks for a java file that belongs to no project, and both are reached from
    resolveCompilationUnit on the *open* of the file - not on import. That timing is the whole
    problem: a document is resolved once, and by the time this plugin's import has provisioned the
    project that owns it, the resolution has already happened and nothing repeats it.

      - The per-folder invisible project, `<folder>_<hash>`. It links the folder and guesses the
        source root from the file's own package declaration, which is the file's own directory
        whenever the package cannot be matched against a path - hence `The declared package "..."
        does not match the expected package ""`. Its linked folder also covers the whole repository,
        so once it exists every file maps to two resources and which one wins is iteration order.

      - The default project, `jdt.ls-java-project`, which gets a linked "fake compilation unit" per
        file. Measured on a monorepo: 25 restored editor tabs across 12 services, every one of them
        resolved into it during the eleven seconds between `initialized` and the import finishing,
        and every one then failing forever with `Error in Java Model (code 969): X.java [in ... [in
        src [in jdt.ls-java-project]]] does not exist`. That is the file that stays red until it is
        clicked - clicking it is a fresh open, and by then the project exists.

    So the repair is the same for both and it is not a one-shot: the fallback project is dropped if
    it is there, and every file handed over is validated again as soon as - and only once - a real
    resource for it exists in the workspace. Waiting matters, because a file can be inside the
    imported scope by the discovery cache while its project is still being provisioned; validating
    then would just re-park it in the same fallback.

    A file this plugin genuinely cannot place is never handed over here. For that file a guessed
    source root beats nothing.
 */
final class InvisibleProject {

    private static final Map<String, Reclaim> JOBS = new ConcurrentHashMap<>();

    /* One tree lookup per pending file per second, and a ceiling so nothing waits forever. */
    private static final long RETRY_MILLIS = 1000;
    private static final long PATIENCE_NANOS = TimeUnit.MINUTES.toNanos(2);

    private InvisibleProject() {
    }

    /*
        file may be null: the importer has nothing in particular to reclaim and only wants a
        fallback project left over from a previous session gone before it shadows what was just
        provisioned.
     */
    static void reclaim(File root, File file) {
        Reclaim job = JOBS.computeIfAbsent(root.getAbsolutePath(), key -> new Reclaim(root));
        job.add(file);
        // Not immediate: the caller is answering a file open, and the resource this waits for is
        // usually written a moment later by the provisioner.
        job.schedule(200);
    }

    /* Whether jdt.ls has an invisible project for this root. A resource-tree lookup and nothing more. */
    static boolean exists(File root) {
        IProject project = projectFor(root);
        return project != null && project.exists();
    }

    private static IProject projectFor(File root) {
        try {
            IPath rootPath = Path.fromOSString(root.getAbsolutePath());
            String name = ProjectUtils.getWorkspaceInvisibleProjectName(rootPath);
            if (name == null || name.isBlank()) {
                return null;
            }
            return ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /*
        Whether the workspace now has a real resource for this file - real meaning in a project this
        plugin provisioned rather than in one of the two fallbacks. This is the same question
        jdt.ls's own findFile asks, so an answer of yes means a fresh resolve will land in the right
        project.
     */
    private static boolean claimedByARealProject(File root, File file) {
        String fallback = invisibleName(root);
        try {
            IFile[] candidates = ResourcesPlugin.getWorkspace().getRoot()
                    .findFilesForLocationURI(file.toPath().toUri());
            for (IFile candidate : candidates) {
                if (!candidate.exists()) {
                    continue;
                }
                String project = candidate.getProject().getName();
                if (project.equals(ProjectsManager.DEFAULT_PROJECT_NAME)
                        || project.equals(fallback)) {
                    continue;
                }
                return true;
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    private static String invisibleName(File root) {
        IProject project = projectFor(root);
        return project == null ? "" : project.getName();
    }

    /*
        Releases the files jdt.ls parked in its own project once a real one covers them.

        This is the half that cannot wait to be asked. The client-side path - the extension telling
        the server which document was opened - is gated on the language client being usable, and the
        session where it matters most is precisely the one where it is not: a client stuck retrying
        its initialization never reports itself ready, so nothing is ever sent, and the parked files
        stay parked. Meanwhile every workspace-wide diagnostics pass walks them and throws twice per
        file - once failing to recreate a link that already exists, once opening a compilation unit
        whose resource does not - which on this repository is ~270 stack traces per pass and a
        megabyte of log every few seconds. That load is what makes the *next* server's start miss the
        client's 30 s budget, and the second server that follows is what corrupts the shared index.

        So the sweep runs on the server's own initiative, from the same job that drops the invisible
        project, and needs nothing from the client. Deleting a linked resource removes the link and
        not the file it points at; the language server creates one again by itself if it ever needs
        to.
     */
    private static int releaseParkedFiles(File root, IProgressMonitor monitor) {
        IProject fallback = ResourcesPlugin.getWorkspace().getRoot()
                .getProject(ProjectsManager.DEFAULT_PROJECT_NAME);
        if (!fallback.exists()) {
            return 0;
        }
        List<IFile> parked = new ArrayList<>();
        try {
            IResourceVisitor collect = resource -> {
                if (resource.getType() == IResource.FILE && resource.isLinked()) {
                    parked.add((IFile) resource);
                }
                return true;
            };
            fallback.accept(collect);
        } catch (CoreException | RuntimeException e) {
            return 0;
        }
        int released = 0;
        for (IFile file : parked) {
            IPath location = file.getLocation();
            if (location == null || !claimedByARealProject(root, location.toFile())) {
                continue;
            }
            try {
                ResourcesPlugin.getWorkspace().run(progress -> file.delete(true, progress), monitor);
                validate(location.toFile());
                released++;
            } catch (CoreException | RuntimeException e) {
                BazelLog.exception("JBazel: could not release " + file.getFullPath(), e);
            }
        }
        return released;
    }

    /*
        deleteContent is false on purpose. The invisible project's own metadata lives in jdt.ls's
        workspace directory, but the folder it links is the repository, and there is no reading of
        the API under which a plugin gets to delete that.
     */
    private static boolean delete(IProject project, IProgressMonitor monitor) throws CoreException {
        if (!project.exists()) {
            return false;
        }
        ResourcesPlugin.getWorkspace().run(
                progress -> project.delete(false, true, progress), monitor);
        return true;
    }

    /*
        Asks jdt.ls to resolve the document again and republish its diagnostics. Nothing else does:
        validateDocument starts by resolving the compilation unit from the URI, which is exactly the
        step that has to be repeated, and neither provisioning a project nor deleting the one that
        held the file triggers it on its own.
     */
    private static void validate(File file) {
        try {
            JavaLanguageServerPlugin plugin = JavaLanguageServerPlugin.getInstance();
            if (plugin == null || !(plugin.getProtocol() instanceof JDTLanguageServer server)) {
                // Syntax-only mode has no diagnostics to correct.
                return;
            }
            String uri = file.toPath().toUri().toString();
            server.validateDocument(new ValidateDocumentParams(new TextDocumentIdentifier(uri)));
        } catch (RuntimeException | LinkageError e) {
            /*
                LinkageError as well as RuntimeException: this is the one place that reaches for a
                jdt.ls handler rather than a published extension point, and a jdt.ls that moved it
                should cost the correction, not the import.
             */
            BazelLog.exception("JBazel: could not ask for the document to be validated again", e);
        }
    }

    private static final class Reclaim extends Job {

        private final File root;
        private final Set<File> pending = ConcurrentHashMap.newKeySet();

        private volatile long deadline;

        private Reclaim(File root) {
            super("JBazel: reattaching open files to their bazel projects");
            this.root = root;
            setPriority(Job.SHORT);
            setSystem(false);
        }

        private void add(File file) {
            if (file != null) {
                pending.add(file);
            }
            deadline = System.nanoTime() + PATIENCE_NANOS;
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            dismissInvisibleProject(monitor);
            int released = releaseParkedFiles(root, monitor);
            if (released > 0) {
                BazelLog.info(String.format(
                        "JBazel: released %d file(s) the language server had parked in its fallback"
                                + " project; they belong to imported bazel projects now", released));
            }
            int rebound = 0;
            for (Iterator<File> files = pending.iterator(); files.hasNext();) {
                File file = files.next();
                if (claimedByARealProject(root, file)) {
                    files.remove();
                    validate(file);
                    rebound++;
                }
            }
            if (rebound > 0) {
                BazelLog.info(String.format(
                        "JBazel: %d open file(s) were resolved before their project existed; asked"
                                + " the language server to look at them again", rebound));
            }
            if (!pending.isEmpty() && System.nanoTime() < deadline) {
                schedule(RETRY_MILLIS);
            }
            return Status.OK_STATUS;
        }

        /*
            Checked on every run rather than remembered: jdt.ls creates this project again the next
            time a file nothing covers is opened, so "already dismissed" would be wrong within the
            same session.
         */
        private void dismissInvisibleProject(IProgressMonitor monitor) {
            IProject project = projectFor(root);
            if (project == null || !project.exists()) {
                return;
            }
            try {
                if (delete(project, monitor)) {
                    BazelLog.info(String.format(
                            "JBazel: dropped the language server's fallback project '%s'; the files"
                                    + " it claimed belong to the imported bazel projects",
                            project.getName()));
                }
            } catch (CoreException | RuntimeException e) {
                BazelLog.exception(
                        "JBazel: could not drop the fallback project " + project.getName(), e);
            }
        }
    }
}
