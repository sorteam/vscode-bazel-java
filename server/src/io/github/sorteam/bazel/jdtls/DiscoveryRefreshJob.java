package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    /*
        How long a refresh is willing to wait for a git operation to finish before proceeding
        anyway - a crashed git leaves index.lock behind, and that must not silence refreshes
        forever.
     */
    private static final long MAX_GIT_WAIT_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final BazelSession session;

    private volatile boolean forced;
    private volatile long gitWaitStarted;

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
        /*
            A checkout, rebase or merge rewrites BUILD files one at a time, and the watcher's
            debounce cannot know when git is done - on a large repository the pauses between event
            bursts exceed any debounce. Refreshing against a half-written tree imports a mix of two
            branches: discovery misses targets that are about to come back, prune deletes their
            projects, and the next event recreates them. Waiting is free; the forced flag is not
            consumed, so the retry still knows a refresh was requested.
         */
        File root = session.getWorkspace().getRoot();
        if (GitState.operationInProgress(root)) {
            if (gitWaitStarted == 0) {
                gitWaitStarted = System.nanoTime();
                BazelLog.info("JBazel: a git operation is rewriting " + root.getName()
                        + ", waiting for it to finish before refreshing");
            }
            if (System.nanoTime() - gitWaitStarted < MAX_GIT_WAIT_NANOS) {
                schedule(2000);
                return Status.OK_STATUS;
            }
            // Minutes of lock usually mean a crashed git left index.lock behind; proceed.
        }
        gitWaitStarted = 0;

        if (session.getDiscoveryGate().shouldSkip()) {
            if (session.getDiscoveryGate().isBusyWaiting()) {
                // The server is busy with a terminal command; the refresh is still owed, so it
                // reschedules itself past the short busy window instead of waiting for the next
                // BUILD edit that might never come.
                schedule(session.getDiscoveryGate().remainingSeconds() * 1000 + 500);
            }
            return Status.OK_STATUS;
        }
        long started = System.currentTimeMillis();
        boolean force = forced;
        forced = false;
        String digestBefore = Digests.buildFilesDigest(root.toPath());
        if (!force && !session.getStore().isStale(session.getSettings(), digestBefore)) {
            BazelLog.info(String.format("JBazel: cached import still valid (checked in %d ms)",
                    System.currentTimeMillis() - started));
            return Status.OK_STATUS;
        }

        BazelLog.info("JBazel: build files changed since the cached import, refreshing");
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
            // the new ones land, so no container is briefly empty. Containers whose jars come back
            // identical are then NOT republished - the resolve job compares stamps first.
            List<String> labels = new ArrayList<>();
            targets.forEach(target -> labels.add(target.label()));
            session.getCache().refreshAll(labels, monitor);
            ClasspathResolveJob.enqueueAll(session, projects);

            /*
                The stamp is the digest taken before discovery. If the tree moved while the refresh
                worked - a second branch switch - stamping would mark old data as current, so the
                data is saved unstamped and another pass runs; it will find the cache stale.
             */
            String digestAfter = Digests.buildFilesDigest(root.toPath());
            if (digestBefore.isEmpty() || !digestBefore.equals(digestAfter)) {
                session.getStore().save();
                BazelLog.info("JBazel: build files changed while refreshing, scheduling another pass");
                forced = true;
                schedule(2000);
            } else {
                session.getStore().stamp(session.getSettings(), digestBefore);
                session.getStore().save();
            }
            BazelLog.info(String.format("JBazel: refreshed %d project(s) in %d ms",
                    projects.size(), System.currentTimeMillis() - started));
        } catch (CoreException e) {
            if (BazelWorkspace.isServerBusy(e)) {
                // Not a failure: a terminal build holds the server lock. Keep the refresh owed and
                // retry on the short busy window rather than escalating the backoff.
                session.getDiscoveryGate().recordBusy(e.getMessage());
                forced = true;
                schedule(session.getDiscoveryGate().remainingSeconds() * 1000 + 500);
            } else {
                session.getDiscoveryGate().recordFailure(e.getMessage());
            }
        }
        return Status.OK_STATUS;
    }
}
