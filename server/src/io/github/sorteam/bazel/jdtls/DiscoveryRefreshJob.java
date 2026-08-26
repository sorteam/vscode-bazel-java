package io.github.sorteam.bazel.jdtls;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IJavaProject;

/*
    Validates the cached import in the background and re-provisions if the repository moved on.

    The import path deliberately trusts the cache without checking it, because checking means walking
    every BUILD file in the repository and that walk belongs nowhere near the startup critical path.
    This job does the checking afterwards: if the stamp still matches, it costs one directory walk
    and no bazel at all; if it does not, it runs discovery once and provisions the difference.
 */
public final class DiscoveryRefreshJob extends Job {

    private static final Map<String, DiscoveryRefreshJob> JOBS = new ConcurrentHashMap<>();

    private final BazelSession session;

    private volatile boolean forced;

    private DiscoveryRefreshJob(BazelSession session) {
        super("Checking bazel project layout for " + session.getWorkspace().getRoot().getName());
        this.session = session;
        setPriority(Job.LONG);
        setSystem(false);
    }

    public static void scheduleFor(BazelSession session) {
        scheduleFor(session, false);
    }

    /*
        forced skips the stamp check. A BUILD edit can land inside the same filesystem timestamp
        granularity as the stamp that was taken, and the point of an explicit "these files changed"
        signal is not to second-guess it.
     */
    public static void scheduleFor(BazelSession session, boolean force) {
        DiscoveryRefreshJob job = JOBS.computeIfAbsent(
                session.getWorkspace().getRoot().getAbsolutePath(),
                ignored -> new DiscoveryRefreshJob(session));
        if (force) {
            job.forced = true;
        }
        job.schedule(force ? 500 : 2000);
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        if (session.getDiscoveryGate().shouldSkip()) {
            return Status.OK_STATUS;
        }
        long started = System.currentTimeMillis();
        boolean force = forced;
        forced = false;
        if (!force && !session.getStore().isStale(session.getSettings())) {
            BazelLog.info(String.format("Bazel: cached import still valid (checked in %d ms)",
                    System.currentTimeMillis() - started));
            return Status.OK_STATUS;
        }

        BazelLog.info("Bazel: build files changed since the cached import, refreshing");
        try {
            List<BazelQuery.Target> targets = new BazelQuery(session.getWorkspace())
                    .javaTargets(monitor, session.getSettings().isDiscoveryNoFetch());
            session.getDiscoveryGate().recordSuccess();
            session.getStore().putDiscovery(targets);
            session.getStore().clearMisplaced();

            List<ProjectGrouping.ProjectSpec> specs = ProjectGrouping.group(targets,
                    session.getSettings().isGroupSourceRoots(),
                    session.getWorkspace().getRoot().getName());
            List<IJavaProject> projects =
                    new ProjectProvisioner(session).provision(specs, true, monitor);

            // The classpath of an existing label can change without the label itself changing, so
            // the cached jars are re-resolved rather than reused. Old values stay in place until
            // the new ones land, so no container is briefly empty.
            List<String> labels = new ArrayList<>();
            targets.forEach(target -> labels.add(target.label()));
            session.getCache().refreshAll(labels, monitor);
            ClasspathResolveJob.enqueueAll(session, projects);

            session.getStore().stamp(session.getSettings());
            session.getStore().save();
            BazelLog.info(String.format("Bazel: refreshed %d project(s) in %d ms",
                    projects.size(), System.currentTimeMillis() - started));
        } catch (CoreException e) {
            session.getDiscoveryGate().recordFailure(e.getMessage());
        }
        return Status.OK_STATUS;
    }
}
