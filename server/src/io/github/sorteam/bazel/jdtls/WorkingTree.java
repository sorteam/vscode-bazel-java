package io.github.sorteam.bazel.jdtls;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IJavaProject;

/*
    Tells the language server's resource model what the working copy actually contains.

    Eclipse does not read the file system to answer questions about it. It answers from a tree it
    keeps in memory and persists on the way out, and that tree is only updated by something calling
    refreshLocal. Auto-refresh - the workspace description flag that would install a file-system
    monitor - is left off by jdt.ls, which instead refreshes at three specific moments: when the
    client reports a watched file event, when a document is opened, and when an importer or build
    support refreshes the projects it just touched.

    The first two do not cover the case that matters here. A branch switch, a pull or a rebase
    performed while the editor is closed produces no client events at all, and on the next start the
    tree is restored from the snapshot the previous session wrote - so a file that arrived in the
    meantime does not exist as far as the java model is concerned, and every reference to a type
    declared in it is an error, in every file except the ones someone happens to open. That is the
    difference between "the plugin did not rebuild" and what actually happened: the compiler was
    never shown the source.

    The third moment is the one to use, and the one this plugin was missing - the importers that ship
    with the language server refresh their projects as part of importing them, and this one did not.
    The work is a scan of the source folders comparing timestamps, not a read of their contents, and
    it runs in the background after the import rather than inside it.
 */
final class WorkingTree {

    private WorkingTree() {
    }

    static void refresh(BazelSession session, List<IJavaProject> projects) {
        if (projects.isEmpty()) {
            return;
        }
        List<IProject> resources = new ArrayList<>();
        projects.forEach(project -> resources.add(project.getProject()));
        new RefreshJob(session, resources).schedule(200);
    }

    private static final class RefreshJob extends Job {

        private final BazelSession session;
        private final List<IProject> projects;

        private RefreshJob(BazelSession session, List<IProject> projects) {
            super("Synchronising " + session.getWorkspace().getRoot().getName()
                    + " with the working copy");
            this.session = session;
            this.projects = projects;
            setPriority(Job.LONG);
            setSystem(false);
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            long started = System.currentTimeMillis();
            int refreshed = 0;
            int failed = 0;
            for (IProject project : projects) {
                if (monitor != null && monitor.isCanceled()) {
                    break;
                }
                if (!project.exists() || !project.isOpen()) {
                    continue;
                }
                try {
                    /*
                        Per project rather than once over the workspace root: a project whose
                        location has gone - a service deleted on the branch that was just checked
                        out - must not stop the other hundred from being refreshed.
                     */
                    project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                    refreshed++;
                } catch (CoreException | RuntimeException e) {
                    failed++;
                    BazelLog.warnOnce("refresh-failed:" + project.getName(),
                            "JBazel: could not synchronise " + project.getName()
                                    + " with the working copy: " + e.getMessage());
                }
            }
            long elapsed = System.currentTimeMillis() - started;
            BazelLog.info(String.format(
                    "JBazel: synchronised %d project(s) with the working copy in %d ms%s",
                    refreshed, elapsed, failed > 0 ? " (" + failed + " could not be read)" : ""));
            session.getReport().phase("refresh", elapsed);
            return Status.OK_STATUS;
        }

        /* One at a time per workspace: two of these would walk the same directories twice. */
        @Override
        public boolean belongsTo(Object family) {
            return family instanceof String name
                    && name.equals("jbazel.refresh:"
                            + session.getWorkspace().getRoot().getAbsolutePath());
        }
    }
}
