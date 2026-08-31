package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.FileInfoMatcherDescription;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceFilterDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubMonitor;
import java.util.regex.Pattern;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;

public class ProjectProvisioner {

    private static final IClasspathAttribute[] TEST_SOURCE =
            { JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true") };
    private static final IClasspathAttribute[] NO_ATTRIBUTES = {};

    private final BazelSession session;

    private final Set<String> freshlyCreated = new LinkedHashSet<>();

    private int created;
    private int updated;
    private int unchanged;
    private static final String RELOCATED_SUFFIX = "-relocated";
    private static final String REGEX_MATCHER = "org.eclipse.core.resources.regexFilterMatcher";

    private int pruned;
    private int relocatedFiles;

    public ProjectProvisioner(BazelSession session) {
        this.session = session;
    }

    public int getCreated() {
        return created;
    }

    public int getUpdated() {
        return updated;
    }

    public int getUnchanged() {
        return unchanged;
    }

    public int getRelocatedFiles() {
        return relocatedFiles;
    }

    public int getPruned() {
        return pruned;
    }

    /*
        Provisions every project in two batched transactions instead of ~5 resource operations per
        project. Each create / open / createLink / setRawClasspath broadcasts a delta and can wake
        the auto-builder and the indexer; doing 223 projects one operation at a time cost 22.7 s.

        The split into two phases is not cosmetic. A project created inside a batch is not visible
        to the java model until that batch ends and the resource delta has been broadcast, so
        setRawClasspath on a freshly created project inside the same batch fails with "<project>
        does not exist". Creation is therefore its own transaction, and configuration runs in a
        second one once the model has caught up.
     */
    public List<IJavaProject> provision(List<ProjectGrouping.ProjectSpec> specs, boolean prune,
            IProgressMonitor monitor) throws CoreException {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        List<IJavaProject> projects = new ArrayList<>(specs.size());
        SubMonitor progress = SubMonitor.convert(monitor, 3);
        boolean autoBuilding = setAutoBuilding(workspace, false);
        try {
            workspace.run(inner -> {
                SubMonitor phase = SubMonitor.convert(inner, specs.size());
                for (ProjectGrouping.ProjectSpec spec : specs) {
                    if (phase.isCanceled()) {
                        break;
                    }
                    try {
                        createOrOpen(spec, phase.split(1));
                    } catch (CoreException e) {
                        BazelLog.exception("JBazel: failed to create " + spec.name(), e);
                    }
                }
            }, workspace.getRoot(), IWorkspace.AVOID_UPDATE, progress.split(1));

            JavaCore.run(inner -> {
                SubMonitor phase = SubMonitor.convert(inner, specs.size());
                for (ProjectGrouping.ProjectSpec spec : specs) {
                    if (phase.isCanceled()) {
                        break;
                    }
                    try {
                        IJavaProject configured = configure(spec, phase.split(1));
                        if (configured != null) {
                            projects.add(configured);
                        }
                    } catch (CoreException e) {
                        BazelLog.exception("JBazel: failed to provision " + spec.name(), e);
                    }
                }
            }, workspace.getRoot(), progress.split(1));

            if (prune) {
                pruneStaleProjects(specs, progress.split(1));
            } else {
                progress.worked(1);
            }
        } finally {
            setAutoBuilding(workspace, autoBuilding);
        }
        return projects;
    }

    private boolean setAutoBuilding(IWorkspace workspace, boolean enabled) {
        try {
            IWorkspaceDescription description = workspace.getDescription();
            boolean previous = description.isAutoBuilding();
            if (previous != enabled) {
                description.setAutoBuilding(enabled);
                workspace.setDescription(description);
            }
            return previous;
        } catch (CoreException e) {
            BazelLog.info("JBazel: could not toggle auto-building: " + e.getMessage());
            return enabled;
        }
    }

    /* Phase one: the project exists, is open and carries the java nature when this returns. */
    private void createOrOpen(ProjectGrouping.ProjectSpec spec, IProgressMonitor monitor)
            throws CoreException {
        SubMonitor progress = SubMonitor.convert(monitor, 3);
        IWorkspace eclipseWorkspace = ResourcesPlugin.getWorkspace();
        IProject project = eclipseWorkspace.getRoot().getProject(spec.name());

        if (!project.exists()) {
            IProjectDescription description = eclipseWorkspace.newProjectDescription(spec.name());
            project.create(description, progress.split(1));
            freshlyCreated.add(spec.name());
            created++;
        } else {
            progress.worked(1);
        }
        if (!project.isOpen()) {
            project.open(progress.split(1));
        } else {
            progress.worked(1);
        }
        ensureJavaNature(project, progress.split(1));
    }

    /*
        The nature has to be applied after the project is open, not handed to create().
        IProject.create(description, ...) does not persist the natures or the build spec, and open()
        then reads whatever .project holds - so a project directory left behind by a previous session
        comes back with an empty <natures> block. JavaProject.exists() checks exactly that nature, so
        every subsequent setRawClasspath fails with "<project> does not exist". That is precisely
        what happens after the language server is killed rather than shut down, which the 2026-08-25
        incident showed is the normal way it exits.
     */
    private static void ensureJavaNature(IProject project, IProgressMonitor monitor)
            throws CoreException {
        if (project.hasNature(JavaCore.NATURE_ID)) {
            return;
        }
        IProjectDescription description = project.getDescription();
        String[] natures = description.getNatureIds();
        String[] extended = Arrays.copyOf(natures, natures.length + 1);
        extended[natures.length] = JavaCore.NATURE_ID;
        description.setNatureIds(extended);
        project.setDescription(description, monitor);
    }

    /* Phase two: links, bazel identity and classpath, on a project the java model already knows. */
    private IJavaProject configure(ProjectGrouping.ProjectSpec spec, IProgressMonitor monitor)
            throws CoreException {
        SubMonitor progress = SubMonitor.convert(monitor, 2);
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(spec.name());
        if (!project.exists() || !project.isOpen()) {
            // Phase one logged why; skipping keeps one bad project from failing the whole import.
            return null;
        }

        File root = session.getWorkspace().getRoot();
        List<LinkedFolder> links = linkFolders(project, spec, root, progress.split(1));
        removeStaleLinks(project, links);

        new ProjectLabels(root.getAbsolutePath(), spec.mainLabels(), spec.testLabels())
                .writeTo(project);

        IJavaProject javaProject = JavaCore.create(project);
        IClasspathEntry[] desired = classpath(project, links);
        IPath output = project.getFullPath().append("bin");

        boolean fresh = freshlyCreated.contains(spec.name());
        IClasspathEntry[] existing = existingClasspath(javaProject);
        if (!fresh && existing != null && Arrays.equals(desired, existing)
                && output.equals(javaProject.getOutputLocation())) {
            unchanged++;
            progress.worked(1);
            return javaProject;
        }

        /*
            The four-argument form with canModifyResources = false looks like a free saving - the
            .classpath file is redundant while the server is running, since the classpath is set
            in memory anyway. It is not: jdt.ls checks every java project for a .classpath file on
            disk and, when it is missing, logs "project has no .classpath. Removing Java nature and
            builder" and does exactly that. Skipping the write tore down all 114 projects right
            after the import, leaving every import unresolved.
         */
        javaProject.setRawClasspath(desired, output, progress.split(1));
        if (!fresh) {
            updated++;
        }
        return javaProject;
    }

    /*
        Null when the classpath cannot be read at all. A freshly created java project answers with
        JDT's default classpath rather than null, which is why freshness is tracked separately.
     */
    private static IClasspathEntry[] existingClasspath(IJavaProject javaProject) {
        try {
            return javaProject.getRawClasspath();
        } catch (CoreException e) {
            return null;
        }
    }

    private List<LinkedFolder> linkFolders(IProject project, ProjectGrouping.ProjectSpec spec,
            File root, IProgressMonitor monitor) throws CoreException {
        SubMonitor progress = SubMonitor.convert(monitor, Math.max(1, spec.sourceFolders().size()));
        List<LinkedFolder> links = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();

        for (ProjectGrouping.SourceFolder source : spec.sourceFolders()) {
            String name = uniqueName(source, usedNames);
            IFolder folder = project.getFolder(name);
            File location = new File(root, source.path());
            IPath path = new Path(location.getAbsolutePath());
            if (!folder.exists() || !path.equals(folder.getLocation())) {
                folder.createLink(path,
                        IResource.REPLACE | IResource.ALLOW_MISSING_LOCAL, progress.split(1));
            } else {
                progress.worked(1);
            }
            List<String> excluded = new ArrayList<>(source.excluded());
            relocate(project, name, location, source, excluded, links);
            links.add(new LinkedFolder(folder, source.test(), excluded));
        }
        return links;
    }

    /*
        Files whose package does not match their directory are excluded from the real source folder
        and linked into a companion one at the path their own package asks for. See SourceRelocation
        for why. The companion folder is rebuilt from scratch every time: it holds a handful of links
        and no content of its own, and rebuilding is simpler than working out which of them moved.
     */
    private void relocate(IProject project, String name, File location,
            ProjectGrouping.SourceFolder source, List<String> excluded, List<LinkedFolder> links)
            throws CoreException {
        IFolder relocated = project.getFolder(name + RELOCATED_SUFFIX);
        List<SourceRelocation.Misplaced> misplaced = session.getStore().peekMisplaced(source.path());
        if (misplaced == null) {
            List<String> nested = new ArrayList<>();
            for (String pattern : source.excluded()) {
                nested.add(pattern.endsWith("/**")
                        ? pattern.substring(0, pattern.length() - "/**".length()) : pattern);
            }
            misplaced = SourceRelocation.scan(location, nested);
            session.getStore().putMisplaced(source.path(), misplaced);
        }
        if (relocated.exists()) {
            if (matches(relocated, misplaced)) {
                // Nothing moved since the last import: the links and the filters that go with them
                // are already in place, and rebuilding them would only churn the resource tree.
                links.add(new LinkedFolder(relocated, source.test(), List.of()));
                relocatedFiles += misplaced.size();
                return;
            }
            // The links in there record which files were hidden last time; a file that is no
            // longer misplaced has to get its filter back off before the folder goes.
            unhideAll(project.getFolder(name), location, relocated);
            relocated.delete(true, new NullProgressMonitor());
        }
        if (misplaced.isEmpty()) {
            return;
        }
        relocated.create(IResource.NONE, true, new NullProgressMonitor());

        for (SourceRelocation.Misplaced file : misplaced) {
            IFolder target = relocated;
            for (String segment : file.declaredPackage().split("\\.")) {
                if (segment.isEmpty()) {
                    continue;
                }
                target = target.getFolder(segment);
                if (!target.exists()) {
                    target.create(IResource.NONE, true, new NullProgressMonitor());
                }
            }
            IFile link = target.getFile(file.fileName());
            if (link.exists()) {
                // Two misplaced files with the same name and the same declared package: the second
                // one has nowhere to go, so it stays where it is and keeps its error.
                continue;
            }
            link.createLink(new Path(new File(location, file.relativePath()).getAbsolutePath()),
                    IResource.ALLOW_MISSING_LOCAL, new NullProgressMonitor());
            hide(project.getFolder(name), file);
        }
        links.add(new LinkedFolder(relocated, source.test(), List.of()));
        relocatedFiles += misplaced.size();
    }

    private static boolean matches(IFolder relocated, List<SourceRelocation.Misplaced> misplaced)
            throws CoreException {
        Set<String> wanted = new HashSet<>();
        for (SourceRelocation.Misplaced file : misplaced) {
            String directory = file.declaredPackage().replace('.', '/');
            wanted.add(directory.isEmpty() ? file.fileName() : directory + "/" + file.fileName());
        }
        Set<String> present = new HashSet<>();
        int prefix = relocated.getFullPath().segmentCount();
        for (IResource member : linkedFilesUnder(relocated)) {
            present.add(member.getFullPath().removeFirstSegments(prefix).toString());
        }
        return wanted.equals(present);
    }

    private void unhideAll(IFolder folder, File location, IFolder relocated) throws CoreException {
        String prefix = location.getAbsolutePath() + File.separator;
        for (IResource member : linkedFilesUnder(relocated)) {
            IPath target = member.getLocation();
            if (target == null || !target.toOSString().startsWith(prefix)) {
                continue;
            }
            String relative = target.toOSString().substring(prefix.length()).replace(File.separatorChar, '/');
            int slash = relative.lastIndexOf('/');
            IContainer parent = slash < 0
                    ? folder : folder.getFolder(new Path(relative.substring(0, slash)));
            if (!parent.exists()) {
                continue;
            }
            String pattern = "^" + Pattern.quote(relative.substring(slash + 1)) + "$";
            for (IResourceFilterDescription existing : parent.getFilters()) {
                if (pattern.equals(existing.getFileInfoMatcherDescription().getArguments())) {
                    existing.delete(IResource.NONE, new NullProgressMonitor());
                }
            }
        }
    }

    private static List<IResource> linkedFilesUnder(IContainer container) throws CoreException {
        List<IResource> files = new ArrayList<>();
        for (IResource member : container.members()) {
            if (member.getType() == IResource.FOLDER) {
                files.addAll(linkedFilesUnder((IContainer) member));
            } else if (member.getType() == IResource.FILE && member.isLinked()) {
                files.add(member);
            }
        }
        return files;
    }

    /*
        Removes the file from the real source folder's resource tree.

        A classpath exclusion is not enough. It keeps the file out of the build, but the resource
        stays in the workspace, and when the editor opens the file by its path on disk the language
        server finds that resource first and answers "not on the classpath of project X, only syntax
        errors are reported" - worse than the package error it replaced. A resource filter deletes
        the resource outright, so the only thing left at that path is the relocated link.
     */
    private void hide(IFolder folder, SourceRelocation.Misplaced file) throws CoreException {
        int slash = file.relativePath().lastIndexOf('/');
        IContainer parent = slash < 0
                ? folder : folder.getFolder(new Path(file.relativePath().substring(0, slash)));
        if (!parent.exists()) {
            return;
        }
        String pattern = "^" + Pattern.quote(file.fileName()) + "$";
        for (IResourceFilterDescription existing : parent.getFilters()) {
            FileInfoMatcherDescription matcher = existing.getFileInfoMatcherDescription();
            if (pattern.equals(matcher.getArguments())) {
                return;
            }
        }
        parent.createFilter(
                IResourceFilterDescription.EXCLUDE_ALL | IResourceFilterDescription.FILES,
                new FileInfoMatcherDescription(REGEX_MATCHER, pattern),
                IResource.NONE, new NullProgressMonitor());
    }

    private static String uniqueName(ProjectGrouping.SourceFolder source, Set<String> used) {
        String base = source.test() ? "test" : "main";
        if (used.add(base)) {
            return base;
        }
        // Two source roots of the same kind in one project: fall back to the path so the folder
        // names stay stable across imports instead of depending on iteration order.
        String derived = base + "-" + source.path().replace('/', '.');
        used.add(derived);
        return derived;
    }

    /*
        A source root that disappeared from the BUILD files leaves behind a linked folder that JDT
        would keep indexing. Links are cheap to recreate, so anything not wanted now is removed.
     */
    private void removeStaleLinks(IProject project, List<LinkedFolder> wanted)
            throws CoreException {
        Set<String> keep = new LinkedHashSet<>();
        wanted.forEach(link -> keep.add(link.folder().getName()));
        for (IResource member : project.members()) {
            if (member.getType() != IResource.FOLDER || keep.contains(member.getName())) {
                continue;
            }
            if (member.isLinked() || member.getName().endsWith(RELOCATED_SUFFIX)) {
                member.delete(true, new NullProgressMonitor());
            }
        }
    }

    /*
        Projects written by an earlier run that no longer correspond to any target - including the
        one-project-per-target names used before source-root grouping - are deleted. Only projects
        carrying this plugin's workspace-root property are touched, and only their metadata: the
        source folders are links, and deleting a link never touches the file it points at.
     */
    private void pruneStaleProjects(List<ProjectGrouping.ProjectSpec> specs,
            IProgressMonitor monitor) throws CoreException {
        Set<String> wanted = new LinkedHashSet<>();
        specs.forEach(spec -> wanted.add(spec.name()));
        String root = session.getWorkspace().getRoot().getAbsolutePath();

        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (wanted.contains(project.getName()) || !project.exists()) {
                continue;
            }
            ProjectLabels labels = ProjectLabels.read(project);
            if (labels == null || !root.equals(labels.workspaceRoot())) {
                continue;
            }
            project.delete(true, true, monitor);
            pruned++;
        }
        if (pruned > 0) {
            BazelLog.info(String.format(
                    "JBazel: removed %d project(s) that no longer match any target", pruned));
        }
        pruneOrphanDirectories();
    }

    /*
        Earlier runs left a directory per project behind in the language server workspace. Once the
        project itself is gone the directory is an empty shell, but there are hundreds of them -
        enough to make the workspace look like it still holds the old one-project-per-target layout.

        Deliberately conservative: a directory is removed only when it is not a registered project,
        carries no .project file, and is empty. Anything with content in it is left alone.
     */
    private void pruneOrphanDirectories() {
        IPath location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
        if (location == null) {
            return;
        }
        File[] children = location.toFile().listFiles();
        if (children == null) {
            return;
        }
        Set<String> registered = new HashSet<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            registered.add(project.getName());
        }
        int removed = 0;
        for (File child : children) {
            if (!child.isDirectory() || child.getName().startsWith(".")
                    || registered.contains(child.getName())
                    || new File(child, ".project").exists()) {
                continue;
            }
            String[] contents = child.list();
            if (contents != null && contents.length == 0 && child.delete()) {
                removed++;
            }
        }
        if (removed > 0) {
            BazelLog.info(String.format(
                    "JBazel: removed %d empty leftover directory(ies) from the language server "
                            + "workspace", removed));
        }
    }

    private IClasspathEntry[] classpath(IProject project, List<LinkedFolder> links) {
        List<IClasspathEntry> entries = new ArrayList<>(links.size() + 2);
        for (LinkedFolder link : links) {
            /*
                Test sources get their own output folder. JDT refuses a classpath where a test source
                folder shares its output with production code, and a merged main+test project is
                exactly that situation.
             */
            IPath output = link.test() ? project.getFullPath().append("bin-test") : null;
            IPath[] exclusions = link.excluded().stream().map(Path::new).toArray(IPath[]::new);
            entries.add(JavaCore.newSourceEntry(link.folder().getFullPath(), null, exclusions,
                    output, link.test() ? TEST_SOURCE : NO_ATTRIBUTES));
        }
        entries.add(JavaRuntime.getDefaultJREContainerEntry());
        entries.add(JavaCore.newContainerEntry(BazelClasspathContainer.CONTAINER_PATH));
        return entries.toArray(IClasspathEntry[]::new);
    }

    private record LinkedFolder(IFolder folder, boolean test, List<String> excluded) {
    }
}
