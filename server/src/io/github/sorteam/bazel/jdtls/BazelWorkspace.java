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
        The bazel-* convenience symlinks present in the repository root, sorted, or an empty list.

        Nothing this plugin does creates them any more, but a terminal build without
        --experimental_convenience_symlinks=ignore in the repository's bazelrc does, and one of them
        is enough to park the next jdt.ls workspace scan in the action output tree. They cannot be
        removed from here - they are the developer's, and their own build put them there - so they
        are reported instead, in the log, the import report and the status bar.
     */
    public List<String> convenienceSymlinks() {
        File[] entries = root.listFiles();
        if (entries == null) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (File entry : entries) {
            if (entry.getName().startsWith("bazel-") && Files.isSymbolicLink(entry.toPath())) {
                found.add(entry.getName());
            }
        }
        Collections.sort(found);
        return found;
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
            throw new CoreException(error(summarise(command) + " failed with exit code " + exitCode
                    + (capturedErrors.isEmpty() ? "" : ": " + firstError(capturedErrors)), null));
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
                if (CREATES_CONVENIENCE_SYMLINKS.contains(arg)) {
                    /*
                        The IDE's own builds must not plant the bazel-bin / bazel-out /
                        bazel-testlogs symlinks in the repository root. jdt.ls follows symlinks
                        during the very first workspace scan (UnifiedTree.isRecursiveLink), and that
                        scan runs before java.project.resourceFilters is applied - configureFilters()
                        only runs once initializeProjects() has returned - so no exclude setting can
                        save an import that meets them: it descends into the whole action output
                        tree and the import parks at "Initialize Workspace" indefinitely.

                        "ignore" neither creates nor removes them, so a developer whose terminal
                        builds rely on bazel-bin keeps whatever is already there; this only stops the
                        IDE from being the one that creates it. Verified present on bazel 9.2.0.
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
                while ((line = reader.readLine()) != null) {
                    if (line.contains(BUSY_STDERR_MARKER)) {
                        // "Another command (pid=N) is running." - printed by the client both when
                        // it exits immediately (--noblock_for_lock) and when it queues. Not an
                        // ERROR line, so it used to vanish silently while the IDE looked hung.
                        busyLine.compareAndSet(null, line.strip());
                    }
                    if (line.startsWith("ERROR") || line.startsWith("FATAL")) {
                        if (captured.size() < MAX_CAPTURED_ERRORS) {
                            captured.add(line);
                        }
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
