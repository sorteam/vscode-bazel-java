package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
    is the developer's decision, not the indexer's. What the import may start by itself is narrower
    and conditional - only the labels whose classpath entries are genuinely absent from disk, and
    only when the buildOnImport setting asks for it.

    The distinction is the whole reason this is written down. Started unconditionally, once per
    session, it meant a build of every discovered target every time the editor was opened - on a
    workspace whose jars were all present and whose containers JDT had already restored. Warm that
    costs a second. Cold - the first start after a reboot, or after anything that dropped bazel's
    analysis cache - it is a full analysis and build of the repository, minutes of every core,
    running at the moment the editor is starting the rest of its language servers.
 */
public final class BuildClasspathJob extends Job {

    private static final long TIMEOUT_SECONDS = TimeUnit.HOURS.toSeconds(1);

    /* 20 deferrals of 30 s each: ten minutes of politeness, then give up quietly. */
    private static final int MAX_BUSY_DEFERRALS = 20;
    private static final long BUSY_DEFER_MILLIS = 30_000;

    /*
        Coalescing window. A checkout produces a resource delta per affected project, so the builder
        is called a hundred times in a row for what is one change to the working copy; without a
        window that is a hundred bazel builds queued behind each other.
     */
    private static final long COALESCE_MILLIS = 1500;

    private static final Map<String, BuildClasspathJob> JOBS = new ConcurrentHashMap<>();

    private static final DateTimeFormatter TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final BazelSession session;
    private final State state = new State();

    /*
        Per build, not per session: set back to zero whenever a build gets as far as a result.
        Counted over the whole session, the ten minutes above ran out after enough collisions with
        terminal builds, and from then on a busy server was a failed build rather than a wait. Only
        ever touched from run(), which the job manager never runs concurrently with itself.
     */
    private int busyDeferrals;

    private BuildClasspathJob(BazelSession session) {
        super("Building bazel classpath for " + session.getWorkspace().getRoot().getName());
        this.session = session;
        setPriority(Job.LONG);
        setSystem(false);
    }

    private static BuildClasspathJob jobFor(BazelSession session) {
        return JOBS.computeIfAbsent(session.getWorkspace().getRoot().getAbsolutePath(),
                ignored -> new BuildClasspathJob(session));
    }

    /*
        The one way in. Labels accumulate and the build runs once for all of them, which is what
        makes a caller that reports one project at a time - the project builder - safe. The reason
        travels with the labels, so whatever the coalescing makes of them can still say what the
        build is for.
     */
    static void enqueue(BazelSession session, Collection<String> labels, Reason reason) {
        if (labels.isEmpty()) {
            return;
        }
        BuildClasspathJob job = jobFor(session);
        job.state.enqueue(labels, reason);
        job.schedule(COALESCE_MILLIS);
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
    /*
        Started only when the classpath actually points at jars that are not there.

        The setting used to mean "build the repository once per session", and a session is every
        time the editor is opened - so a workspace whose jars were all present, whose cached import
        was valid and whose containers JDT had already restored still ran a build of every discovered
        target on every start. Warm that is a second; cold it is a full build competing with the
        editor's own startup, and the machine it is competing on is the one deciding whether the
        language client's 30 s start budget is met.

        Missing jars are the condition the build exists for - aquery reports what a Javac action
        would consume, so on a fresh clone the entries name jars nothing has produced yet - and they
        are counted while publishing, which is the only place that knows. Nothing missing, nothing to
        build.
     */
    public static void startIfConfigured(BazelSession session, Collection<String> labels) {
        /*
            Still once per session, unlike the delta-driven path. This one fires from the resolve,
            which runs again whenever a classpath is republished, and a target that cannot build
            leaves its jars missing - so without a bound a repository with one broken target would
            rebuild on a loop. A change the developer makes is a different matter: that has a person
            behind it and is allowed to ask again.
         */
        if (labels.isEmpty() || !session.getSettings().isBuildOnImport()
                || !session.markClasspathBuildStarted()) {
            return;
        }
        BazelLog.warnOnce("missing-jar-build:" + session.getWorkspace().getRoot().getName(),
                String.format("JBazel: %d label(s) have classpath jars that are not on disk;"
                        + " building those in the background", labels.size()));
        enqueue(session, labels, Reason.missingJars(labels.size()));
    }

    private static boolean startFor(BazelSession session) {
        List<BazelQuery.Target> discovered = session.getStore().peekDiscovery();
        if (discovered == null || discovered.isEmpty()) {
            return false;
        }
        Set<String> labels = new LinkedHashSet<>();
        discovered.forEach(target -> labels.add(target.label()));
        enqueue(session, labels, Reason.command());
        return true;
    }

    /*
        The build half of what the client polls; State.status has the shape. A session that has
        never asked for a build has no job yet and reads exactly like an idle one.
     */
    static Map<String, Object> status(BazelSession session) {
        BuildClasspathJob job = JOBS.get(session.getWorkspace().getRoot().getAbsolutePath());
        return (job == null ? new State() : job.state).status(System.nanoTime());
    }

    /* One line for the import report, which is where the status bar item leads. */
    static String describe(BazelSession session) {
        BuildClasspathJob job = JOBS.get(session.getWorkspace().getRoot().getAbsolutePath());
        return (job == null ? new State() : job.state).describe(System.nanoTime());
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
        Batch batch = state.start(System.nanoTime());
        if (batch == null) {
            return Status.OK_STATUS;
        }
        List<String> labels = batch.labels();
        BazelLog.info(String.format("JBazel: building %d target(s) - %s", labels.size(),
                batch.reason().describe()));
        Errors errors = new Errors();
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
            /*
                stdout stays unread: a build prints nothing there. What it has to say about a
                failure is on stderr, and only as much of that as a tooltip can carry is kept - see
                Errors.
             */
            session.getWorkspace().runStreaming(monitor, line -> { }, errors, TIMEOUT_SECONDS,
                    arguments.toArray(String[]::new));

            Long after = fingerprintJars(executionRoot);
            long elapsed = System.currentTimeMillis() - started;
            finish(batch, Result.success(batch, System.currentTimeMillis(), elapsed));
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
                The jars changed, so the classpath has to be resolved again - but only that. This
                used to force a discovery refresh, which re-ran bazel query, re-provisioned all
                projects and re-resolved every label through aquery: measured at ~30 s of work after
                every start, for a build that cannot change the project layout. A build rewrites jar
                contents; the set of jars behind a label only moves when a BUILD or lock file does,
                and the non-forced refresh scheduled below is what notices that - by digest, without
                bazel.

                Most of what the resolve then does is not republishing. A jar the build rewrote in
                place is the same classpath entry, so its container is unchanged and stays where it
                is; what makes its new classes visible is the external-archive refresh the resolve
                ends with. Containers move only for jars that appeared, vanished or resolved
                elsewhere.
             */
            BazelLog.info(String.format("JBazel: built %d target(s) in %d ms; jars changed,"
                    + " re-reading the affected classpaths", labels.size(), elapsed));
            ClasspathResolveJob.enqueueAll(session);
            DiscoveryRefreshJob.scheduleFor(session);
        } catch (CoreException e) {
            if (BazelWorkspace.isServerBusy(e) && ++busyDeferrals <= MAX_BUSY_DEFERRALS) {
                /*
                    Back on the queue rather than dropped. The labels left it when the build
                    started, and a retry scheduled without them woke up to an empty queue - the wait
                    was polite, and then the build never happened.
                 */
                state.putBack(batch);
                schedule(BUSY_DEFER_MILLIS);
                return Status.OK_STATUS;
            }
            /*
                Failed means bazel did not report success, and under --keep_going that includes a
                partial one: bazel builds everything it can, then exits 1 for whatever it could
                not. Those are exactly the targets whose jars the editor is missing or reading
                stale, so a build that produced nine outputs of ten has failed as far as anyone
                looking at an unresolved type is concerned. There is no partial-success exit code
                for a build to be let off with - the 3 BazelWorkspace accepts is a query's, and for
                build and test it reports failed tests, which a build does not run. Nor does the
                converse hold: a build that exited 0 succeeded whatever its stderr says, since every
                action's own output is passed through there, and a generator logging at an error
                level of its own has not failed anything.

                Anything else that ends a build without success counts the same - a timeout, a
                cancellation, a bazel that could not be started, a server still busy after the ten
                minutes of deferrals: nothing says what such a build produced, and the message it
                ended with stands in for the error bazel never printed.
             */
            long elapsed = System.currentTimeMillis() - started;
            String error = errors.excerpt(e.getMessage());
            finish(batch, Result.failure(batch, System.currentTimeMillis(), elapsed,
                    errors.count(), error));
            BazelLog.info(String.format("JBazel: build of %d target(s) failed after %d ms%s: %s",
                    labels.size(), elapsed,
                    errors.count() == 0 ? "" : " with " + errors.count() + " error(s)", error));
            return Status.OK_STATUS;
        } finally {
            /*
                Whatever escapes above, the status must stop saying a build is running: a spinner
                that never stops is worse than none. A no-op after finish and putBack, which have
                already said what happened.
             */
            state.stopped(batch);
        }
        return Status.OK_STATUS;
    }

    private void finish(Batch batch, Result result) {
        busyDeferrals = 0;
        state.finish(batch, result);
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

    /*
        What the status bar reads, per session: the labels waiting and why, the build running and
        since when, and how the last one ended.

        It exists because the alternative was silence. The builds that start by themselves - a
        project's files changed, the classpath names jars nothing has produced - ran with nothing on
        screen, and from the editor a build not started yet, a build still running and a build that
        had failed an hour ago all look the same: unresolved types.

        Written from the project builder and the command handler as well as from run(), and read by
        the status command on a thread of its own, so all of it sits behind one lock: a reader never
        sees a build that has left the queue without being running or finished. Nothing in it grows
        with bazel's output - the queue holds labels, and a finished build keeps an excerpt.
     */
    static final class State {

        private final Set<String> pending = new LinkedHashSet<>();
        private Reason pendingReason = Reason.NONE;
        private Batch running;
        private Result last;

        synchronized void enqueue(Collection<String> labels, Reason reason) {
            pending.addAll(labels);
            pendingReason = pendingReason.merge(reason);
        }

        /* Everything queued becomes the build that is starting; null when nothing is queued. */
        synchronized Batch start(long nowNanos) {
            if (pending.isEmpty()) {
                return null;
            }
            running = new Batch(List.copyOf(pending), pendingReason, nowNanos);
            pending.clear();
            pendingReason = Reason.NONE;
            return running;
        }

        /*
            A build that could not run goes back to the front of the queue, its reason ahead of
            whatever arrived while it was trying.
         */
        synchronized void putBack(Batch batch) {
            Set<String> labels = new LinkedHashSet<>(batch.labels());
            labels.addAll(pending);
            pending.clear();
            pending.addAll(labels);
            pendingReason = batch.reason().merge(pendingReason);
            stopped(batch);
        }

        /* Replaces the previous result, so a success is also what clears a failure. */
        synchronized void finish(Batch batch, Result result) {
            last = result;
            stopped(batch);
        }

        synchronized void stopped(Batch batch) {
            if (running == batch) {
                running = null;
            }
        }

        /*
            The shape extension.js reads, one key per question so the client can test for presence:

              building     {targets, elapsedSeconds, reason}; null while nothing is running
              buildQueued  {targets, reason}; null while nothing waits. Waiting is the coalescing
                           window or a busy server, and it can be set while building is, for work
                           that arrived in the meantime
              lastBuild    {failed, error, errors, targets, reason, finishedAt, elapsedSeconds};
                           null before the session's first build. finishedAt is epoch millis, error
                           an excerpt and empty for a success, errors the number bazel printed
         */
        synchronized Map<String, Object> status(long nowNanos) {
            Map<String, Object> status = new LinkedHashMap<>();
            Map<String, Object> building = null;
            if (running != null) {
                building = new LinkedHashMap<>();
                building.put("targets", running.labels().size());
                building.put("elapsedSeconds", seconds(nowNanos - running.startedNanos()));
                building.put("reason", running.reason().describe());
            }
            status.put("building", building);

            Map<String, Object> queued = null;
            if (!pending.isEmpty()) {
                queued = new LinkedHashMap<>();
                queued.put("targets", pending.size());
                queued.put("reason", pendingReason.describe());
            }
            status.put("buildQueued", queued);

            Map<String, Object> lastBuild = null;
            if (last != null) {
                lastBuild = new LinkedHashMap<>();
                lastBuild.put("failed", last.failed());
                lastBuild.put("error", last.error());
                lastBuild.put("errors", last.errors());
                lastBuild.put("targets", last.targets());
                lastBuild.put("reason", last.reason());
                lastBuild.put("finishedAt", last.finishedAtMillis());
                lastBuild.put("elapsedSeconds",
                        TimeUnit.MILLISECONDS.toSeconds(last.elapsedMillis()));
            }
            status.put("lastBuild", lastBuild);
            return status;
        }

        synchronized String describe(long nowNanos) {
            List<String> parts = new ArrayList<>();
            if (running != null) {
                parts.add(String.format("building %d target(s) for %d s - %s",
                        running.labels().size(), seconds(nowNanos - running.startedNanos()),
                        running.reason().describe()));
            }
            if (!pending.isEmpty()) {
                parts.add(String.format("%d target(s) queued - %s", pending.size(),
                        pendingReason.describe()));
            }
            if (last != null) {
                String when = TIME_OF_DAY.format(Instant.ofEpochMilli(last.finishedAtMillis())
                        .atZone(ZoneId.systemDefault()));
                long took = TimeUnit.MILLISECONDS.toSeconds(last.elapsedMillis());
                parts.add(last.failed()
                        ? String.format("last build failed at %s after %d s%s: %s", when, took,
                                last.errors() == 0 ? "" : " with " + last.errors() + " error(s)",
                                last.error())
                        : String.format("last build succeeded at %s after %d s", when, took));
            }
            return parts.isEmpty() ? "none this session" : String.join("; ", parts);
        }

        private static long seconds(long nanos) {
            return TimeUnit.NANOSECONDS.toSeconds(Math.max(0, nanos));
        }
    }

    /* The labels of one build, why it was asked for, and when it started. */
    record Batch(List<String> labels, Reason reason, long startedNanos) {
    }

    /* How a build ended. error is the excerpt Errors made of it, empty for a success. */
    record Result(boolean failed, int targets, String reason, long finishedAtMillis,
            long elapsedMillis, int errors, String error) {

        static Result success(Batch batch, long finishedAtMillis, long elapsedMillis) {
            return new Result(false, batch.labels().size(), batch.reason().describe(),
                    finishedAtMillis, elapsedMillis, 0, "");
        }

        static Result failure(Batch batch, long finishedAtMillis, long elapsedMillis, int errors,
                String error) {
            return new Result(true, batch.labels().size(), batch.reason().describe(),
                    finishedAtMillis, elapsedMillis, errors, error);
        }
    }

    /*
        Why a build was asked for, kept apart by kind so that coalescing can summarise instead of
        list. A checkout hands the builder one delta per affected project and the build that follows
        is one invocation for all of them, so the log line and the tooltip have to say it in a
        sentence rather than in a hundred: each kind keeps one example and a count - the first file
        that changed and how many projects had changes, how many labels had missing jars. A person
        asking is named first, whatever else was queued with it: they know why they asked, and it is
        the answer they will be looking for.
     */
    static final class Reason {

        static final Reason NONE = new Reason(false, "", Set.of(), Set.of(), 0);

        private final boolean requested;
        private final String changedFile;
        private final Set<String> changedProjects;
        private final Set<String> fullBuilds;
        private final int missingJarLabels;

        private Reason(boolean requested, String changedFile, Set<String> changedProjects,
                Set<String> fullBuilds, int missingJarLabels) {
            this.requested = requested;
            this.changedFile = changedFile;
            this.changedProjects =
                    Collections.unmodifiableSet(new LinkedHashSet<>(changedProjects));
            this.fullBuilds = Collections.unmodifiableSet(new LinkedHashSet<>(fullBuilds));
            this.missingJarLabels = missingJarLabels;
        }

        /* 'JBazel: Build Classpath'. */
        static Reason command() {
            return new Reason(true, "", Set.of(), Set.of(), 0);
        }

        /* A file of this project changed; path is where the developer knows it from. */
        static Reason fileChanged(String project, String path) {
            return new Reason(false, path, Set.of(project), Set.of(), 0);
        }

        /* The platform built the project in full, which comes with no list of what changed. */
        static Reason fullBuild(String project) {
            return new Reason(false, "", Set.of(), Set.of(project), 0);
        }

        static Reason missingJars(int labels) {
            return new Reason(false, "", Set.of(), Set.of(), labels);
        }

        /*
            This reason followed by a later one. The example file stays the first one seen, and a
            project that changed twice is still one project.
         */
        Reason merge(Reason later) {
            Set<String> changed = new LinkedHashSet<>(changedProjects);
            changed.addAll(later.changedProjects);
            Set<String> full = new LinkedHashSet<>(fullBuilds);
            full.addAll(later.fullBuilds);
            return new Reason(requested || later.requested,
                    changedProjects.isEmpty() ? later.changedFile : changedFile,
                    changed, full, missingJarLabels + later.missingJarLabels);
        }

        String describe() {
            List<String> parts = new ArrayList<>();
            if (requested) {
                parts.add("requested with 'JBazel: Build Classpath'");
            }
            if (!changedProjects.isEmpty()) {
                parts.add(changedFile + " changed in " + andMore(changedProjects));
            }
            if (!fullBuilds.isEmpty()) {
                parts.add("full build of " + andMore(fullBuilds));
            }
            if (missingJarLabels > 0) {
                parts.add(missingJarLabels + " label(s) have classpath jars that are not on disk");
            }
            return parts.isEmpty() ? "no reason recorded" : String.join("; ", parts);
        }

        private static String andMore(Set<String> projects) {
            String first = projects.iterator().next();
            return projects.size() == 1
                    ? first
                    : first + " and " + (projects.size() - 1) + " more project(s)";
        }
    }

    /*
        What a failed build said, cut to what fits a tooltip and a log line: the first error and how
        many there were. With --keep_going bazel reports every target it could not build, one ERROR
        line each, and the first one names a BUILD file, an action and a target - the question
        someone looking at an unresolved type is actually asking. The rest is in bazel's own output,
        one terminal command away.

        "The first error" is what BazelWorkspace takes one to be: the ERROR line together with the
        traceback bazel prints under it, because that is where the remedy is, and a line ending in
        "An error occurred during the fetch of repository 'x':" stops where the answer starts.
        bazel's sign-off - "Build did NOT complete successfully" - is neither counted nor shown: it
        repeats the exit code, and "2 errors" for one broken target reads as two.

        Bounded as it streams, not afterwards. A build's stderr is every action's output passed
        through, and with --verbose_failures a single line can be a whole command line; one error
        and a handful of cause lines, each cut to size, is all that is ever held.
     */
    static final class Errors implements Consumer<String> {

        static final int MAX_EXCERPT_CHARS = 500;

        private int count;
        private String first;
        private final StringBuilder causes = new StringBuilder();
        private int causeLines;
        private boolean following;

        @Override
        public synchronized void accept(String line) {
            if (BazelWorkspace.isError(line)) {
                following = false;
                if (BazelWorkspace.isSummary(line)) {
                    return;
                }
                count++;
                if (first == null) {
                    first = cut(line.strip());
                    following = true;
                }
            } else if (following && causeLines < BazelWorkspace.MAX_CAUSE_LINES
                    && BazelWorkspace.isCause(line)) {
                causes.append(' ').append(cut(line.strip()));
                causeLines++;
            } else {
                following = false;
            }
        }

        synchronized int count() {
            return count;
        }

        /*
            The first error - or, for a build that ended without printing one (killed at its
            timeout, cancelled, unable to start), the message it ended with.

            Where the cut goes depends on what follows the ERROR line. Alone, its useful half is the
            start - the BUILD file, the action, the target - and what is left is a command line, so
            it is cut at the end. With a traceback the remedy is last, and the cut moves to the
            middle.
         */
        synchronized String excerpt(String fallback) {
            if (first == null) {
                return BazelWorkspace.elideMiddle(fallback == null ? "" : fallback.strip(),
                        MAX_EXCERPT_CHARS);
            }
            return causes.length() == 0
                    ? first
                    : BazelWorkspace.elideMiddle(first + causes, MAX_EXCERPT_CHARS);
        }

        private static String cut(String line) {
            return line.length() <= MAX_EXCERPT_CHARS
                    ? line
                    : line.substring(0, MAX_EXCERPT_CHARS - 4) + " ...";
        }
    }
}
