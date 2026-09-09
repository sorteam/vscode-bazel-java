package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModel;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

/*
    Makes JDT re-read the classpath jars that bazel rewrote in place - and, more importantly, does
    nothing at all when none were.

    Every entry this plugin publishes is an absolute path outside the workspace, which JDT calls an
    external archive and treats as immutable until told otherwise: each one's modification time lives
    in the java model's own state and is compared against the disk when the language server starts,
    and when refreshExternalArchives is called. Republishing the container is not one of those
    moments - setClasspathContainer compares entries, and a rebuilt jar keeps its path - so without
    the call, a class that has been on disk for an hour stays invisible until the window is reloaded.

    What that call costs is the reason for everything below it. refreshExternalArchives takes the
    java model lock, walks every archive of every project in scope, and for each one whose timestamp
    moved it flushes the model's caches and queues the jar for indexing. Handing it a whole workspace
    unconditionally is therefore not a check but a rebuild: on a 116-project workspace the first pass
    after an upgrade found ~1.6k stale timestamps and turned into minutes of indexing under the model
    lock. A language server busy for minutes is a language server whose client gives up - redhat.java
    races the handshake against 30 s and then starts a second server on the same -data directory,
    which corrupts the shared JDT index. So the cheap half is done here, in this plugin, where it
    cannot hold a lock: the jars behind the containers are stat'ed, and JDT is only asked about
    projects whose jars actually moved.

    The first pass seeds and reports nothing. At that point the language server has just started,
    which is when JDT refreshed these archives on its own, so what is on disk is what JDT has already
    read - and calling it again would only repeat that work. A jar rebuilt in the seconds between
    those two reads is missed until something touches it again; the alternative, treating an unseeded
    path as changed, is exactly the unconditional pass this class exists to avoid.
 */
final class ExternalArchives {

    /*
        Path -> size and modification time of every jar this plugin has handed JDT, as JDT last saw
        it. Not a duplicate of the container stamp: that one answers "is this a different container"
        and deliberately ignores content, this one answers "did the content move".
     */
    private static final Map<String, long[]> lastSeen = new ConcurrentHashMap<>();

    /* The client sends its cue on several events that routinely fire together. */
    private static final long MIN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final AtomicLong lastSync = new AtomicLong(Long.MIN_VALUE);

    private static final SyncJob SYNC = new SyncJob();

    private ExternalArchives() {
    }

    /*
        The projects a resolve just finished for. Cheap by construction: one stat per jar behind their
        containers, and no JDT call unless one of them moved.
     */
    static void refresh(Collection<IJavaProject> projects) {
        refreshChanged(projects);
    }

    /*
        Every project this plugin owns, on the client's cue.

        A developer who builds in a terminal rewrites the same jars and tells the IDE nothing: no
        BUILD file changed, so no refresh is owed, and the editor keeps reporting errors about a
        class that was compiled minutes ago. The cue is deliberately vague ("the developer may have
        built something") because the precise one does not exist, and what makes that affordable is
        that a cue finding nothing costs stats and touches neither bazel, nor the index, nor a lock.
     */
    static String sync() {
        long now = System.nanoTime();
        long previous = lastSync.get();
        if (previous != Long.MIN_VALUE && now - previous < MIN_INTERVAL_NANOS) {
            return "Checked a moment ago.";
        }
        if (!lastSync.compareAndSet(previous, now)) {
            return "Already checking.";
        }
        SYNC.schedule();
        return "Checking the classpath jars for changes.";
    }

    private static List<IJavaProject> ownProjects() {
        List<IJavaProject> projects = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen() && ProjectLabels.read(project) != null) {
                projects.add(JavaCore.create(project));
            }
        }
        return projects;
    }

    /*
        Returns how many projects were handed to JDT, which is zero on all but the passes that
        matter.

        Two loops on purpose. The jars are shared - a 116-project workspace resolves ~1.6k distinct
        files across ~50k container entries - so the first loop stats each path once and the second
        one maps the paths that moved back to the projects holding them. Statting per entry instead
        would be 30x the syscalls to learn the same thing.
     */
    private static int refreshChanged(Collection<IJavaProject> projects) {
        Map<IJavaProject, IClasspathEntry[]> entriesByProject = new LinkedHashMap<>();
        for (IJavaProject javaProject : projects) {
            IClasspathEntry[] entries = containerEntries(javaProject);
            if (entries.length > 0) {
                entriesByProject.put(javaProject, entries);
            }
        }

        Set<String> moved = new HashSet<>();
        Set<String> stated = new HashSet<>();
        for (IClasspathEntry[] entries : entriesByProject.values()) {
            for (IClasspathEntry entry : entries) {
                String path = libraryPath(entry);
                if (path == null || !stated.add(path)) {
                    continue;
                }
                File file = new File(path);
                long[] onDisk = { file.lastModified(), file.length() };
                long[] before = lastSeen.put(path, onDisk);
                if (before != null && (before[0] != onDisk[0] || before[1] != onDisk[1])) {
                    moved.add(path);
                }
            }
        }
        if (moved.isEmpty()) {
            return 0;
        }

        List<IJavaElement> changed = new ArrayList<>();
        entriesByProject.forEach((javaProject, entries) -> {
            for (IClasspathEntry entry : entries) {
                String path = libraryPath(entry);
                if (path != null && moved.contains(path)) {
                    changed.add(javaProject);
                    return;
                }
            }
        });
        BazelLog.info(String.format(
                "JBazel: %d classpath jar(s) were rebuilt in place; asking JDT to re-read them for"
                        + " %d project(s)", moved.size(), changed.size()));
        refreshNow(changed);
        return changed.size();
    }

    private static String libraryPath(IClasspathEntry entry) {
        return entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY
                ? entry.getPath().toFile().getAbsolutePath()
                : null;
    }

    /* From memory: JDT answers with the container this plugin published, and never runs bazel. */
    private static IClasspathEntry[] containerEntries(IJavaProject javaProject) {
        try {
            IClasspathContainer container = JavaCore.getClasspathContainer(
                    BazelClasspathContainer.CONTAINER_PATH, javaProject);
            return container == null ? new IClasspathEntry[0] : container.getClasspathEntries();
        } catch (CoreException e) {
            return new IClasspathEntry[0];
        }
    }

    private static void refreshNow(List<IJavaElement> scope) {
        IJavaModel model = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot());
        if (model == null) {
            return;
        }
        try {
            model.refreshExternalArchives(scope.toArray(IJavaElement[]::new),
                    new NullProgressMonitor());
        } catch (JavaModelException | RuntimeException e) {
            /*
                Not fatal: the classpath JDT holds stays exactly as it was, which is what it would
                have been without this call. Caught wide because this runs at the tail of the resolve
                job, and failing to notice a rebuilt jar must not take a resolve that already
                succeeded down with it.
             */
            BazelLog.exception("JBazel: could not re-read the rebuilt classpath jars", e);
        }
    }

    /*
        Off the request thread. The stats are quick, but a pass that does find something goes on to
        fire deltas and queue indexing, and none of that belongs in the round trip of a command the
        client sends on a window focus.
     */
    private static final class SyncJob extends Job {

        private SyncJob() {
            super("Checking bazel classpath jars for changes");
            setPriority(Job.SHORT);
            setSystem(true);
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            refreshChanged(ownProjects());
            return Status.OK_STATUS;
        }
    }
}
