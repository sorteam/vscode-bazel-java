package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

/*
    Background resolution of bazel classpath containers.

    ClasspathContainerInitializer.initialize() is called by JDT on its own schedule, often while it
    holds model locks, and the old implementation ran a bazel process inside it - once per project.
    Nothing about the language server start could proceed until all 223 of them had finished.

    Here initialize() only publishes whatever is already known and hands the label to this job. The
    job coalesces everything queued so far into a single batched aquery, then pushes the finished
    containers back with JavaCore.setClasspathContainer. Projects requested with priority - the one
    owning the file the developer just opened - are resolved individually first so that a single
    open file does not wait for a repository-wide batch.
 */
public final class ClasspathResolveJob extends Job {

    private static final Map<String, ClasspathResolveJob> JOBS = new ConcurrentHashMap<>();

    private final BazelSession session;
    private final Map<String, Request> pending = new LinkedHashMap<>();
    private final Set<String> priority = new LinkedHashSet<>();

    private ClasspathResolveJob(BazelSession session) {
        super("Resolving bazel classpath for " + session.getWorkspace().getRoot().getName());
        this.session = session;
        setPriority(Job.LONG);
        setSystem(false);
        setUser(false);
    }

    public static void enqueue(BazelSession session, IJavaProject javaProject,
            List<String> mainLabels, List<String> testLabels, boolean urgent) {
        ClasspathResolveJob job = JOBS.computeIfAbsent(
                session.getWorkspace().getRoot().getAbsolutePath(),
                ignored -> new ClasspathResolveJob(session));
        job.add(javaProject, mainLabels, testLabels, urgent);
    }

    public static void enqueueAll(BazelSession session, List<IJavaProject> projects) {
        projects.forEach(project -> {
            ProjectLabels labels = ProjectLabels.read(project.getProject());
            if (labels != null) {
                enqueue(session, project, labels.mainLabels(), labels.testLabels(), false);
            }
        });
    }

    private synchronized void add(IJavaProject javaProject, List<String> mainLabels,
            List<String> testLabels, boolean urgent) {
        String key = javaProject.getProject().getName();
        pending.put(key, new Request(javaProject, mainLabels, testLabels));
        if (urgent) {
            priority.add(key);
        }
        schedule(urgent ? 0 : 200);
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        if (session.getClasspathGate().shouldSkip()) {
            // Reschedule past the backoff window instead of hammering a workspace that is failing.
            schedule(session.getClasspathGate().remainingSeconds() * 1000 + 500);
            return Status.OK_STATUS;
        }

        List<Request> batch;
        List<Request> urgent;
        synchronized (this) {
            batch = new ArrayList<>(pending.values());
            urgent = new ArrayList<>();
            priority.forEach(key -> {
                Request request = pending.get(key);
                if (request != null) {
                    urgent.add(request);
                }
            });
            pending.clear();
            priority.clear();
        }
        if (batch.isEmpty()) {
            return Status.OK_STATUS;
        }

        try {
            /*
                Captured before any bazel work: this digest describes the tree the resolved
                classpaths belong to. Stamping a digest taken after the fact would mark data from
                the old tree as current when a branch switch lands mid-resolve.
             */
            String buildFilesDigest = Digests.buildFilesDigest(
                    session.getWorkspace().getRoot().toPath());
            File executionRoot = executionRoot(monitor);

            int published = 0;
            int unchanged = 0;
            for (Request request : urgent) {
                published += resolveOne(request, executionRoot, monitor) ? 1 : 0;
            }

            Set<String> labels = new LinkedHashSet<>();
            batch.forEach(request -> labels.addAll(request.allLabels()));
            session.getCache().warmAll(new ArrayList<>(labels), monitor);

            for (Request request : batch) {
                if (publish(request, executionRoot)) {
                    published++;
                } else {
                    unchanged++;
                }
            }
            if (published + unchanged > 1) {
                BazelLog.info(String.format(
                        "Bazel: %d classpath container(s) published, %d unchanged (kept, no"
                                + " reindex)", published, unchanged));
            }
            session.getStore().setExecutionRoot(executionRoot.getAbsolutePath());
            /*
                Only the cold import path stamps from here - the store has no stamp yet then, and
                someone has to mark the cache usable. Everywhere else DiscoveryRefreshJob owns the
                stamp: it is the one that actually ran discovery, so it knows which tree the data
                describes. Stamping unconditionally here used to be able to mask a BUILD edit that
                happened between a cached discovery and this resolve.
             */
            if (!session.getStore().hasStamp()) {
                session.getStore().stamp(session.getSettings(), buildFilesDigest);
            }
            session.getStore().save();
            session.getClasspathGate().recordSuccess();
            BuildClasspathJob.startIfConfigured(session);
        } catch (CoreException e) {
            if (BazelWorkspace.isServerBusy(e)) {
                // A terminal build holds the server; short fixed retry, no failure escalation.
                session.getClasspathGate().recordBusy(e.getMessage());
            } else {
                session.getClasspathGate().recordFailure(e.getMessage());
            }
            // Put the work back so the next attempt, after the backoff, still has it.
            synchronized (this) {
                batch.forEach(request ->
                        pending.putIfAbsent(request.javaProject().getProject().getName(), request));
            }
            schedule(session.getClasspathGate().remainingSeconds() * 1000 + 500);
        }
        return Status.OK_STATUS;
    }

    private File executionRoot(IProgressMonitor monitor) throws CoreException {
        BazelWorkspace workspace = session.getWorkspace();
        File cached = workspace.peekExecutionRoot();
        if (cached != null) {
            return cached;
        }
        String stored = session.getStore().peekExecutionRoot();
        if (!stored.isBlank() && new File(stored).isDirectory()) {
            workspace.setExecutionRoot(new File(stored));
            return new File(stored);
        }
        return workspace.executionRoot(monitor);
    }

    private boolean resolveOne(Request request, File executionRoot, IProgressMonitor monitor)
            throws CoreException {
        for (String label : request.allLabels()) {
            session.getCache().jarsFor(label, monitor);
        }
        return publish(request, executionRoot);
    }

    /*
        Returns true when a container was actually handed to JDT. Republishing an identical one is
        not a no-op: JDT forgets what it read from every jar behind the container and re-indexes all
        of them - on a large repository ~1.6k jars and over a gigabyte of index writes, which is
        most of what "the java process hangs after a branch switch" was. The stamp covers the jar
        list, its order, and each jar's size and mtime, so a jar rebuilt in place still republishes.
     */
    private boolean publish(Request request, File executionRoot) {
        Set<String> mainJars = new LinkedHashSet<>();
        Set<String> testJars = new LinkedHashSet<>();
        request.mainLabels().forEach(label -> mainJars.addAll(jars(label)));
        request.testLabels().forEach(label -> testJars.addAll(jars(label)));
        testJars.removeAll(mainJars);

        String projectName = request.javaProject().getProject().getName();
        long stamp = ContainerStamp.of(executionRoot, mainJars, testJars);
        Long lastPublished = session.getPublishedContainerStamp(projectName);
        if (lastPublished != null && lastPublished == stamp) {
            session.getReport().countContainerUnchanged();
            return false;
        }

        BazelClasspathContainer container =
                BazelClasspathContainer.fromJars(executionRoot, mainJars, testJars);
        try {
            JavaCore.setClasspathContainer(BazelClasspathContainer.CONTAINER_PATH,
                    new IJavaProject[] { request.javaProject() },
                    new IClasspathContainer[] { container },
                    new NullProgressMonitor());
            session.setPublishedContainerStamp(projectName, stamp);
            session.getReport().countContainerPublished();
            session.getReport().countJars(container.getResolvedCount(),
                    container.getMissingCount());
            if (container.getMissingCount() > 0) {
                BazelLog.warnOnce("missing-jars:" + session.getWorkspace().getRoot(), String.format(
                        "Bazel: %d classpath jars do not exist on disk yet (for example in %s)."
                                + " Run 'Bazel: Build Classpath' to produce them.",
                        container.getMissingCount(), request.javaProject().getProject().getName()));
            }
        } catch (CoreException e) {
            BazelLog.exception("Bazel: failed to set the classpath container for "
                    + request.javaProject().getProject().getName(), e);
        }
        return true;
    }

    private List<String> jars(String label) {
        List<String> cached = session.getCache().peek(label);
        return cached == null ? List.of() : cached;
    }

    record Request(IJavaProject javaProject, List<String> mainLabels, List<String> testLabels) {

        List<String> allLabels() {
            List<String> all = new ArrayList<>(mainLabels);
            all.addAll(testLabels);
            return all;
        }
    }
}
