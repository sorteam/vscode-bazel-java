package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/*
    Builds the targets whose jars the classpath points at.

    aquery reports what a Javac action would consume, not what exists. With
    --nojava_header_compilation those are full compile outputs, so on a fresh clone the container
    silently drops nearly every entry and the project looks like it has no dependencies. Building
    the targets once materialises them.

    Explicitly a command rather than something the import does on its own: a repository-wide build
    is the developer's decision, not the indexer's.
 */
public final class BuildClasspathJob extends Job {

    private static final long TIMEOUT_SECONDS = TimeUnit.HOURS.toSeconds(1);

    /* 20 deferrals of 30 s each: ten minutes of politeness, then give up quietly. */
    private static final int MAX_BUSY_DEFERRALS = 20;
    private static final long BUSY_DEFER_MILLIS = 30_000;

    private final BazelSession session;
    private final List<String> labels;

    private int busyDeferrals;

    private BuildClasspathJob(BazelSession session, List<String> labels) {
        super("Building bazel classpath for " + session.getWorkspace().getRoot().getName());
        this.session = session;
        this.labels = labels;
        setPriority(Job.LONG);
        setUser(true);
    }

    public static String start(Collection<BazelSession> sessions) {
        int started = 0;
        for (BazelSession session : sessions) {
            if (startFor(session)) {
                started++;
            }
        }
        return started == 0
                ? "Nothing to build: no imported bazel targets."
                : "Building " + started + " workspace(s) in the background.";
    }

    /*
        Automatic variant, run once per session after the classpath is warm. Bazel is incremental,
        so on an up-to-date repository this costs a few seconds and changes nothing; on a stale one
        it is the difference between the IDE showing last week's API and the current one.
     */
    public static void startIfConfigured(BazelSession session) {
        if (!session.getSettings().isBuildOnImport() || !session.markClasspathBuildStarted()) {
            return;
        }
        startFor(session);
    }

    private static boolean startFor(BazelSession session) {
        List<BazelQuery.Target> discovered = session.getStore().peekDiscovery();
        if (discovered == null || discovered.isEmpty()) {
            return false;
        }
        Set<String> labels = new LinkedHashSet<>();
        discovered.forEach(target -> labels.add(target.label()));
        new BuildClasspathJob(session, new ArrayList<>(labels)).schedule();
        return true;
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        /*
            Right after a branch switch the developer's own build is usually already running, and a
            repository-wide background build competing with it for the server lock helps nobody -
            it either queues or, worse, makes the terminal build queue. Defer while the server was
            recently seen busy, bounded so a permanently busy server does not park this forever.
         */
        if (session.getWorkspace().wasBusyRecently() && ++busyDeferrals <= MAX_BUSY_DEFERRALS) {
            schedule(BUSY_DEFER_MILLIS);
            return Status.OK_STATUS;
        }
        long started = System.currentTimeMillis();
        try {
            File executionRoot = session.getWorkspace().executionRoot(monitor);
            Long before = fingerprintJars(executionRoot);

            java.nio.file.Path targetFile =
                    session.getWorkspace().writeQueryFile(String.join("\n", labels));
            /*
                --jobs when configured: this build was started by the indexer, not asked for, and
                taking every core on the machine the developer is typing on is the wrong default for
                work nobody is waiting on.
             */
            List<String> arguments = new ArrayList<>(List.of(
                    "build", "--target_pattern_file=" + targetFile,
                    "--keep_going", "--noshow_progress"));
            session.getSettings().buildJobsArgument().ifPresent(arguments::add);
            session.getWorkspace().runStreaming(monitor, line -> { }, TIMEOUT_SECONDS,
                    arguments.toArray(String[]::new));

            Long after = fingerprintJars(executionRoot);
            long elapsed = System.currentTimeMillis() - started;
            /*
                On an up-to-date repository the build rewrites nothing, and republishing the
                containers anyway is not free: JDT drops what it read from every jar and re-indexes
                all of them. On this repository that is 1.6k jars and over a gigabyte of index
                written on every single start, for no change at all - and an editor closed in the
                middle of that leaves truncated index files behind, which JDT later reads as
                garbage lengths and dies with OutOfMemoryError.
             */
            if (before != null && before.equals(after)) {
                BazelLog.info(String.format(
                        "JBazel: built %d target(s) in %d ms; jars unchanged, classpath left as is",
                        labels.size(), elapsed));
                DiscoveryRefreshJob.scheduleFor(session);
                return Status.OK_STATUS;
            }
            /*
                The jars changed, so the containers behind them have to be handed to JDT again - but
                only that. This used to force a discovery refresh, which re-ran bazel query,
                re-provisioned all projects and re-resolved every label through aquery: measured at
                ~30 s of work after every start, for a build that cannot change the project layout.
                A build rewrites jar contents; the set of jars behind a label only moves when a
                BUILD or lock file does, and the non-forced refresh scheduled below is what notices
                that - by digest, without bazel.
             */
            BazelLog.info(String.format("JBazel: built %d target(s) in %d ms; jars changed,"
                    + " republishing the affected classpaths", labels.size(), elapsed));
            ClasspathResolveJob.enqueueAll(session);
            DiscoveryRefreshJob.scheduleFor(session);
        } catch (CoreException e) {
            if (BazelWorkspace.isServerBusy(e) && ++busyDeferrals <= MAX_BUSY_DEFERRALS) {
                schedule(BUSY_DEFER_MILLIS);
                return Status.OK_STATUS;
            }
            BazelLog.info("JBazel: build for the classpath failed: " + e.getMessage());
            return Status.OK_STATUS;
        }
        return Status.OK_STATUS;
    }

    /*
        Identity of the jars the containers currently point at, by path, size and modification time.
        null when nothing is cached yet - there is then no "unchanged" to speak of and the caller
        refreshes.
     */
    private Long fingerprintJars(File executionRoot) {
        List<BazelQuery.Target> discovered = session.getStore().peekDiscovery();
        if (discovered == null || executionRoot == null) {
            return null;
        }
        Set<String> paths = new LinkedHashSet<>();
        for (BazelQuery.Target target : discovered) {
            List<String> jars = session.getStore().peekJars(target.label());
            if (jars != null) {
                paths.addAll(jars);
            }
        }
        if (paths.isEmpty()) {
            return null;
        }
        long hash = 1125899906842597L;
        for (String path : paths) {
            File file = path.startsWith("/") ? new File(path) : new File(executionRoot, path);
            hash = 31 * hash + path.hashCode();
            hash = 31 * hash + file.lastModified();
            hash = 31 * hash + file.length();
        }
        return hash;
    }
}
