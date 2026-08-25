package ch.audienzz.bazel.jdtls;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;

public class BazelWorkspace {

    private final File root;
    private File executionRoot;

    public BazelWorkspace(File root) {
        this.root = root;
    }

    public File getRoot() {
        return root;
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

    public List<String> run(IProgressMonitor monitor, String... args) throws CoreException {
        List<String> stdout = new ArrayList<>();
        runStreaming(monitor, stdout::add, args);
        return stdout;
    }

    public void runStreaming(IProgressMonitor monitor, Consumer<String> sink, String... args)
            throws CoreException {
        List<String> command = new ArrayList<>();
        command.add(BazelBinary.resolve());
        for (String arg : args) {
            command.add(arg);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root);
        builder.redirectErrorStream(false);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new CoreException(error("Unable to start " + String.join(" ", command), e));
        }

        Thread stderrPump = drainStderr(process);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (monitor != null && monitor.isCanceled()) {
                    process.destroyForcibly();
                    throw new CoreException(error("Cancelled: " + String.join(" ", command), null));
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
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new CoreException(error("Timed out: " + String.join(" ", command), null));
            }
            exitCode = process.exitValue();
            stderrPump.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoreException(error("Interrupted: " + String.join(" ", command), e));
        }

        if (exitCode != 0) {
            throw new CoreException(error(
                    String.join(" ", command) + " failed with exit code " + exitCode, null));
        }
    }

    private Thread drainStderr(Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("ERROR") || line.startsWith("FATAL")) {
                        JavaLanguageServerPlugin.logInfo("Bazel: " + line);
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

    static IStatus error(String message, Throwable cause) {
        return new Status(IStatus.ERROR, BazelClasspathContainerInitializer.PLUGIN_ID,
                message, cause);
    }
}
