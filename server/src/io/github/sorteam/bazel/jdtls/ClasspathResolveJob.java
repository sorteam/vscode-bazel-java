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
            File executionRoot = executionRoot(monitor);

            for (Request request : urgent) {
                resolveOne(request, executionRoot, monitor);
            }

            Set<String> labels = new LinkedHashSet<>();
            batch.forEach(request -> labels.addAll(request.allLabels()));
            session.getCache().warmAll(new ArrayList<>(labels), monitor);

            for (Request request : batch) {
                publish(request, executionRoot);
            }
            session.getStore().setExecutionRoot(executionRoot.getAbsolutePath());
            session.getStore().stamp(session.getSettings());
            session.getStore().save();
            session.getClasspathGate().recordSuccess();
            BuildClasspathJob.startIfConfigured(session);
        } catch (CoreException e) {
            session.getClasspathGate().recordFailure(e.getMessage());
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

    private void resolveOne(Request request, File executionRoot, IProgressMonitor monitor)
            throws CoreException {
        for (String label : request.allLabels()) {
            session.getCache().jarsFor(label, monitor);
        }
        publish(request, executionRoot);
    }

    private void publish(Request request, File executionRoot) {
        BazelClasspathContainer container = build(request, executionRoot);
        try {
            JavaCore.setClasspathContainer(BazelClasspathContainer.CONTAINER_PATH,
                    new IJavaProject[] { request.javaProject() },
                    new IClasspathContainer[] { container },
                    new NullProgressMonitor());
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
    }

    private BazelClasspathContainer build(Request request, File executionRoot) {
        Set<String> mainJars = new LinkedHashSet<>();
        Set<String> testJars = new LinkedHashSet<>();
        request.mainLabels().forEach(label -> mainJars.addAll(jars(label)));
        request.testLabels().forEach(label -> testJars.addAll(jars(label)));
        testJars.removeAll(mainJars);
        return BazelClasspathContainer.fromJars(executionRoot, mainJars, testJars);
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
