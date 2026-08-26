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
import java.util.concurrent.TimeUnit;
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

    private final File root;
    private final ReentrantLock commandLock = new ReentrantLock(true);

    private volatile BazelSettings settings;
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
        Thread stderrPump = drainStderr(process, capturedErrors);
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
            throw new CoreException(error("Failed reading output of " + command.get(0), e));
        }

        int exitCode;
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

        if (exitCode == 3) {
            // Partial success. The java targets are in the output; whatever failed to load is
            // reported once by the stderr pump and then suppressed.
            BazelLog.warnOnce(errorKey(capturedErrors), String.format(
                    "Bazel: %s completed with loading-phase errors (exit 3), using partial results."
                            + " First error: %s",
                    summarise(command), firstError(capturedErrors)));
            return;
        }
        if (!ACCEPTED_EXIT_CODES.contains(exitCode)) {
            throw new CoreException(error(summarise(command) + " failed with exit code " + exitCode
                    + (capturedErrors.isEmpty() ? "" : ": " + firstError(capturedErrors)), null));
        }
    }

    private List<String> buildCommand(BazelSettings current, String[] args) {
        List<String> command = new ArrayList<>();
        command.add(BazelBinary.resolve(current));

        // Startup options have to precede the command name.
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
            BazelLog.info("Bazel: shut down the IDE-owned server for " + root);
        } catch (IOException e) {
            BazelLog.info("Bazel: could not shut down the IDE-owned server: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Thread drainStderr(Process process, List<String> captured) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
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
