package io.github.sorteam.bazel.jdtls;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

public class BazelWorkspace {

    /*
        bazel returns 3 for "command succeeded, but there were loading phase errors" - the normal
        outcome of --keep_going when some package in the repository cannot be loaded. Treating it as
        a hard failure is what turned a transient network outage into an overnight retry loop on
        2026-08-25: the java targets were all there, only unrelated rules_oci / helm_charts packages
        had failed to fetch.
     */
    private static final Set<Integer> ACCEPTED_EXIT_CODES = Set.of(0, 3);

    private static final int MAX_CAPTURED_ERRORS = 20;

    /* Per ERROR line, how much of the traceback that follows it is kept. */
    private static final int MAX_CAUSE_LINES = 6;
    private static final int MAX_DETAIL_CHARS = 900;

    /*
        An external repository that cannot be fetched - a stale rules_jvm_external lock file, most
        often - fails the analysis phase outright, so every classpath comes back empty. Unlike a busy
        server this never resolves on its own: it needs MODULE.bazel or a lock file fixed, and the
        gate says so instead of counting anonymous failures.
     */
    private static final List<String> FETCH_FAILURE_MARKERS = List.of(
            "An error occurred during the fetch of repository",
            "must be regenerated",
            "REPIN=1");
    public static final int STATUS_FETCH_BLOCKED = 10;

    /*
        The client exits with 9 when --noblock_for_lock is set and another command holds the lock
        (verified on bazel 9.2.0). The same value doubles as the IStatus code that marks a
        CoreException as "the server is busy", which callers retry on a short fixed interval
        instead of escalating the exponential backoff - a terminal build is not a failure.
     */
    private static final int SERVER_BUSY_EXIT_CODE = 9;
    public static final int STATUS_SERVER_BUSY = 9;
    private static final String BUSY_STDERR_MARKER = "Another command (pid=";

    private static final long BUSY_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(30);

    /*
        One shared watchdog thread for every bazel process this JVM starts. The commandLock keeps
        the processes themselves serialised per workspace, so the watchdog is never watching more
        than a handful at a time.
     */
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "bazel-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    /*
        The commands that write the bazel-* convenience symlinks. query and aquery do not, so they
        are not given the flag - it is a build option, and only these two carry it for certain on
        every supported bazel version.
     */
    private static final Set<String> CREATES_CONVENIENCE_SYMLINKS = Set.of("build", "test");

    private final File root;
    private final ReentrantLock commandLock = new ReentrantLock(true);

    private volatile BazelSettings settings;
    private volatile long lastBusyNanos;
    private File executionRoot;
    private boolean serverStarted;

    public BazelWorkspace(File root) {
        this.root = root;
        this.settings = BazelSettings.load(root);
    }

    public File getRoot() {
        return root;
    }

    public BazelSettings getSettings() {
        return settings;
    }

    /*
        The convenience symlinks present in the repository root, sorted, or an empty list.

        They belong to the developer and to everything else in the repository - a TypeScript config
        that reads generated clients out of bazel-bin, most of all - so they are neither removed nor
        argued with. What they need is to be kept out of the one scan that cannot survive them:
        jdt.ls walks the workspace with FOLLOW_LINKS looking for build files, and one bazel-out is
        enough to send it into the whole action output tree. See ImportExclusions.

        Detected by target rather than by name: --symlink_prefix renames all of them, so the stable
        signal is a root symlink that lands inside the output base (execroot / bazel-out), with the
        historical bazel- prefix left as a last resort for the case where nothing is known yet.
     */
    public List<String> convenienceSymlinks() {
        File[] entries = root.listFiles();
        if (entries == null) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (File entry : entries) {
            if (Files.isSymbolicLink(entry.toPath()) && leadsIntoOutputTree(entry)) {
                found.add(entry.getName());
            }
        }
        Collections.sort(found);
        return found;
    }

    private boolean leadsIntoOutputTree(File symlink) {
        File base = peekOutputBase();
        String target;
        try {
            target = symlink.toPath().toRealPath().toString();
        } catch (IOException e) {
            // A dangling symlink cannot be walked into, but bazel will refresh it on the next
            // build, so it is still one of ours if the name says so.
            return symlink.getName().startsWith("bazel-");
        }
        String normalised = target.replace(File.separatorChar, '/');
        if (base != null && normalised.startsWith(
                base.getAbsolutePath().replace(File.separatorChar, '/') + "/")) {
            return true;
        }
        return normalised.contains("/execroot/") || normalised.contains("/bazel-out/")
                || symlink.getName().startsWith("bazel-");
    }

    /*
        The output base of whatever bazel this workspace talks to, or null when nothing is known yet.
        Taken from the configured dedicated base, otherwise derived from the execution root, which
        is <output base>/execroot/<workspace> and is cached in ClasspathStore across sessions - so
        this answers without running bazel, which matters at import time.
     */
    public File peekOutputBase() {
        BazelSettings current = settings;
        if (current.hasDedicatedOutputBase()) {
            return outputBaseDirectory(current);
        }
        File execution = peekExecutionRoot();
        if (execution == null) {
            return null;
        }
        File execroot = execution.getParentFile();
        if (execroot == null || !"execroot".equals(execroot.getName())) {
            return null;
        }
        return execroot.getParentFile();
    }

    public synchronized BazelSettings reloadSettings() {
        settings = BazelSettings.load(root);
        executionRoot = null;
        return settings;
    }

    public synchronized File executionRoot(IProgressMonitor monitor) throws CoreException {
        if (executionRoot == null) {
            List<String> output = run(monitor, "info", "execution_root", "--noshow_progress");
            if (output.isEmpty()) {
                throw new CoreException(error("bazel info execution_root returned nothing", null));
            }
            executionRoot = new File(output.get(0).trim());
        }
        return executionRoot;
    }

    public synchronized void setExecutionRoot(File cached) {
        if (cached != null && executionRoot == null) {
            executionRoot = cached;
        }
    }

    public synchronized File peekExecutionRoot() {
        return executionRoot;
    }

    public List<String> run(IProgressMonitor monitor, String... args) throws CoreException {
        List<String> stdout = new ArrayList<>();
        runStreaming(monitor, stdout::add, args);
        return stdout;
    }

    /*
        Writes a bazel query expression to a temporary file for --query_file. The batched aquery
        names every java label explicitly (~17.6 KB for a 442-target monorepo), which is well past
        what belongs on a command line.
     */
    public Path writeQueryFile(String expression) throws CoreException {
        try {
            Path file = Files.createTempFile("bazel-jdtls-query", ".txt");
            file.toFile().deleteOnExit();
            Files.writeString(file, expression, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new CoreException(error("Unable to write bazel query file", e));
        }
    }

    public void runStreaming(IProgressMonitor monitor, Consumer<String> sink, String... args)
            throws CoreException {
        runStreaming(monitor, sink, 0, args);
    }

    /*
        timeoutOverrideSeconds is for commands that are expected to be slow on purpose - `bazel
        build` from the "Build Classpath" command, mostly. Everything on the indexing path uses the
        configured timeout, which is short by design: a query that hangs must not hang the IDE.
     */
    public void runStreaming(IProgressMonitor monitor, Consumer<String> sink,
            long timeoutOverrideSeconds, String... args) throws CoreException {
        BazelSettings current = settings;
        List<String> command = buildCommand(current, args);
        long timeoutSeconds = timeoutOverrideSeconds > 0
                ? timeoutOverrideSeconds
                : Math.max(10, current.getCommandTimeoutSeconds());

        /*
            The bazel server runs one command at a time. Firing several in parallel only makes them
            queue on the server lock while holding jdt.ls threads hostage, so they are serialised
            here where the wait is visible and bounded.
         */
        boolean locked;
        try {
            locked = commandLock.tryLock(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoreException(error("Interrupted waiting for the bazel command lock", e));
        }
        if (!locked) {
            throw new CoreException(error(
                    "Another bazel command is still running after " + timeoutSeconds + " s", null));
        }
        try {
            execute(command, sink, monitor, timeoutSeconds);
        } finally {
            commandLock.unlock();
        }
    }

    private void execute(List<String> command, Consumer<String> sink, IProgressMonitor monitor,
            long timeoutSeconds) throws CoreException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root);
        builder.redirectErrorStream(false);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new CoreException(error("Unable to start " + String.join(" ", command), e));
        }
        serverStarted = true;

        List<String> capturedErrors = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> busyLine = new AtomicReference<>();
        Thread stderrPump = drainStderr(process, capturedErrors, busyLine);

        /*
            The timeout used to be enforced only in waitFor(), after stdout hit EOF - and a bazel
            client waiting for the server lock writes nothing to stdout, so "timed out" never fired
            in exactly the case it was written for: a terminal build holding the lock while a jdt.ls
            job thread sat in readLine(). The watchdog covers the whole lifetime of the process, and
            doubles as the only cancellation check that works while the process is silent.
         */
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        AtomicReference<String> killReason = new AtomicReference<>();
        ScheduledFuture<?> watchdog = WATCHDOG.scheduleWithFixedDelay(() -> {
            if (!process.isAlive()) {
                return;
            }
            if (monitor != null && monitor.isCanceled()) {
                if (killReason.compareAndSet(null, "was cancelled")) {
                    process.destroyForcibly();
                }
            } else if (System.nanoTime() - deadline >= 0) {
                if (killReason.compareAndSet(null, "timed out after " + timeoutSeconds + " s")) {
                    process.destroyForcibly();
                }
            }
        }, 500, 500, TimeUnit.MILLISECONDS);

        int exitCode;
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (monitor != null && monitor.isCanceled()) {
                        process.destroyForcibly();
                        throw new CoreException(error("Cancelled: " + summarise(command), null));
                    }
                    if (!line.isBlank()) {
                        sink.accept(line);
                    }
                }
            } catch (IOException e) {
                // A process killed by the watchdog closes its streams mid-read; the kill reason is
                // the real story then, reported below.
                if (killReason.get() == null) {
                    throw new CoreException(error("Failed reading output of " + command.get(0), e));
                }
            }

            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new CoreException(error(
                            "Timed out after " + timeoutSeconds + " s: " + summarise(command), null));
                }
                exitCode = process.exitValue();
                stderrPump.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CoreException(error("Interrupted: " + summarise(command), e));
            }
        } finally {
            watchdog.cancel(false);
        }

        /*
            Busy beats every other classification: with --noblock_for_lock the client exits with 9
            immediately, and without it the watchdog kill of a client stuck behind the lock must not
            read as a generic failure. Either way the caller sees STATUS_SERVER_BUSY and retries on a
            short fixed interval instead of escalating the exponential backoff.
         */
        if (!ACCEPTED_EXIT_CODES.contains(exitCode)
                && (exitCode == SERVER_BUSY_EXIT_CODE || busyLine.get() != null)) {
            lastBusyNanos = System.nanoTime();
            String detail = busyLine.get() == null ? "exit code " + exitCode : busyLine.get();
            BazelLog.warnOnce("bazel-busy:" + root.getName(), String.format(
                    "JBazel: the bazel server for %s is busy with another command (a terminal"
                            + " build?): %s", root.getName(), detail));
            throw new CoreException(busyError(
                    summarise(command) + " - the bazel server is busy: " + detail));
        }
        String killed = killReason.get();
        if (killed != null) {
            throw new CoreException(error(summarise(command) + " " + killed, null));
        }

        if (exitCode == 3) {
            // Partial success. The java targets are in the output; whatever failed to load is
            // reported once by the stderr pump and then suppressed.
            BazelLog.warnOnce(errorKey(capturedErrors), String.format(
                    "JBazel: %s completed with loading-phase errors (exit 3), using partial results."
                            + " First error: %s",
                    summarise(command), firstError(capturedErrors)));
            lastBusyNanos = 0;
            return;
        }
        if (!ACCEPTED_EXIT_CODES.contains(exitCode)) {
            String message = summarise(command) + " failed with exit code " + exitCode
                    + (capturedErrors.isEmpty() ? "" : ": " + failureDetail(capturedErrors));
            throw new CoreException(isFetchFailure(capturedErrors)
                    ? new Status(IStatus.ERROR, BazelClasspathContainerInitializer.PLUGIN_ID,
                            STATUS_FETCH_BLOCKED, message, null)
                    : error(message, null));
        }
        lastBusyNanos = 0;
        BazelLog.clear("bazel-busy:" + root.getName());
    }

    /*
        True when the last completed command found the server occupied by someone else's command and
        nothing has succeeded since. Consulted by jobs that are free to wait - the background
        classpath build, mostly - before they join the queue.
     */
    public boolean wasBusyRecently() {
        long busyAt = lastBusyNanos;
        return busyAt != 0 && System.nanoTime() - busyAt < BUSY_WINDOW_NANOS;
    }

    public static boolean isServerBusy(CoreException e) {
        return e.getStatus() != null && e.getStatus().getCode() == STATUS_SERVER_BUSY;
    }

    public static boolean isFetchBlocked(CoreException e) {
        return e.getStatus() != null && e.getStatus().getCode() == STATUS_FETCH_BLOCKED;
    }

    /*
        Package-private and static so the classification is testable without a bazel process: the
        markers are bazel's wording, and a bazel release renaming them would otherwise turn a
        precise, actionable message back into "failed with exit code 1".
     */
    static boolean isFetchFailure(List<String> captured) {
        synchronized (captured) {
            for (String line : captured) {
                for (String marker : FETCH_FAILURE_MARKERS) {
                    if (line.contains(marker)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isCause(String line) {
        return line.startsWith(" ") || line.startsWith("\t") || line.startsWith("Error in ");
    }

    /*
        The first error with its cause, plus the last one when bazel repeated itself further down -
        which it does exactly in the interesting case, where the final ERROR line carries the remedy.
     */
    static String failureDetail(List<String> captured) {
        String detail;
        synchronized (captured) {
            if (captured.isEmpty()) {
                return "(no stderr)";
            }
            String first = captured.get(0);
            String last = captured.get(captured.size() - 1);
            detail = first.contains(last) || last.contains(first) || captured.size() == 1
                    ? first
                    : first + " | last: " + last;
        }
        return detail.length() <= MAX_DETAIL_CHARS
                ? detail
                : detail.substring(0, MAX_DETAIL_CHARS) + " ...";
    }

    private static IStatus busyError(String message) {
        return new Status(IStatus.ERROR, BazelClasspathContainerInitializer.PLUGIN_ID,
                STATUS_SERVER_BUSY, message, null);
    }

    private List<String> buildCommand(BazelSettings current, String[] args) {
        List<String> command = new ArrayList<>();
        command.add(BazelBinary.resolve(current));

        // Startup options have to precede the command name.
        if (current.isNoblockForLock()) {
            /*
                Fail with exit 9 instead of queueing behind whoever holds the server lock - on a
                shared output base that is the developer's own terminal build, which can run for
                many minutes right after the branch switch that triggered this refresh. Verified on
                bazel 9.2.0: startup-option position, exit code 9, and it does not restart a running
                server that was started without it.
             */
            command.add("--noblock_for_lock");
        }
        if (current.hasDedicatedOutputBase()) {
            command.add("--output_base=" + outputBaseDirectory(current));
            command.add("--max_idle_secs=" + current.getMaxIdleSeconds());
        }

        boolean first = true;
        for (String arg : args) {
            command.add(arg);
            if (first) {
                // Right after the command name: keep bazel's output machine readable regardless of
                // what the developer's bazelrc does.
                command.add("--curses=no");
                command.add("--color=no");
                if (CREATES_CONVENIENCE_SYMLINKS.contains(arg)
                        && current.hasDedicatedOutputBase()) {
                    /*
                        Only with a dedicated output base, and only because there the default is
                        actively wrong: measured on bazel 9.2.0, a build repoints every convenience
                        symlink at the output base it ran in, so an IDE build would send bazel-bin
                        at ~/.cache/bazel-ide, where nothing but the IDE's own classpath targets was
                        ever built - and everything else in the repository that reads generated
                        output through bazel-bin (TypeScript configs, scripts) would read an empty
                        tree until the next terminal build put it back. "normal" can also delete a
                        symlink it considers ambiguous; "ignore" neither creates nor removes.

                        On the shared output base - the default - the IDE writes the same paths a
                        terminal build would, so there is nothing to protect against and no flag is
                        added. Keeping the symlinks out of the jdt.ls scan is ImportExclusions' job,
                        not bazel's.
                     */
                    command.add("--experimental_convenience_symlinks=ignore");
                }
                first = false;
            }
        }
        return command;
    }

    /*
        A dedicated output base isolates IDE queries from the developer's terminal bazel. Without it
        the two contend for the single server lock: a `bazel build` in a terminal blocks indexing,
        and a burst of IDE queries blocks the build. It also makes the server safe to shut down when
        the language server exits, which is what leaves ~1.15 GB behind today.
     */
    public File outputBaseDirectory(BazelSettings current) {
        String configured = current.getOutputBase();
        if (!"ide".equalsIgnoreCase(configured)) {
            return new File(configured);
        }
        String home = System.getProperty("user.home");
        return new File(home, ".cache/bazel-ide/" + Digests.shortHash(root.getAbsolutePath()));
    }

    /*
        Only ever shuts down a server this plugin owns. The shared server belongs to the developer's
        terminal and killing it would cancel their build.
     */
    public void shutdownOwnedServer() {
        BazelSettings current = settings;
        if (!current.hasDedicatedOutputBase() || !serverStarted) {
            return;
        }
        List<String> command = List.of(BazelBinary.resolve(current),
                "--output_base=" + outputBaseDirectory(current), "shutdown");
        try {
            Process process = new ProcessBuilder(command).directory(root)
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            process.waitFor(30, TimeUnit.SECONDS);
            BazelLog.info("JBazel: shut down the IDE-owned server for " + root);
        } catch (IOException e) {
            BazelLog.info("JBazel: could not shut down the IDE-owned server: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Thread drainStderr(Process process, List<String> captured,
            AtomicReference<String> busyLine) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                int capturing = -1;
                int causeLines = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(BUSY_STDERR_MARKER)) {
                        // "Another command (pid=N) is running." - printed by the client both when
                        // it exits immediately (--noblock_for_lock) and when it queues. Not an
                        // ERROR line, so it used to vanish silently while the IDE looked hung.
                        busyLine.compareAndSet(null, line.strip());
                    }
                    if (line.startsWith("ERROR") || line.startsWith("FATAL")) {
                        capturing = captured.size() < MAX_CAPTURED_ERRORS ? captured.size() : -1;
                        causeLines = 0;
                        if (capturing >= 0) {
                            captured.add(line);
                        }
                    } else if (capturing >= 0 && causeLines < MAX_CAUSE_LINES && isCause(line)) {
                        /*
                            bazel prints the actionable half of a failure *after* its ERROR line:
                            the Starlark traceback, and the "Error in fail: ... please run: REPIN=1
                            bazel run @maven//:pin" that says what to do about it. Capturing only
                            lines that start with ERROR threw precisely that away, and the import
                            report read "failed with exit code 1: ERROR: ...coursier.bzl:678:21: An
                            error occurred during the fetch of repository 'maven_nullaway':" - a
                            sentence that stops where the answer starts.
                         */
                        captured.set(capturing, captured.get(capturing) + " " + line.strip());
                        causeLines++;
                    } else {
                        capturing = -1;
                    }
                }
            } catch (IOException ignored) {
                // process gone
            }
        }, "bazel-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /*
        Keys the log suppression by what actually failed rather than by call site, so a repeating
        outage collapses into one entry while a genuinely new error still gets through.
     */
    private String errorKey(List<String> captured) {
        return "bazel-errors:" + root.getName() + ":" + Digests.shortHash(firstError(captured));
    }

    private static String firstError(List<String> captured) {
        synchronized (captured) {
            return captured.isEmpty() ? "(no stderr)" : captured.get(0);
        }
    }

    private static String summarise(List<String> command) {
        StringBuilder out = new StringBuilder("bazel");
        for (int i = 1; i < command.size() && i < 4; i++) {
            out.append(' ').append(command.get(i));
        }
        if (command.size() > 4) {
            out.append(" ...");
        }
        return out.toString();
    }

    static IStatus error(String message, Throwable cause) {
        return new Status(IStatus.ERROR, BazelClasspathContainerInitializer.PLUGIN_ID,
                message, cause);
    }
}
