package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class BazelBinary {

    private static final String SYSTEM_PROPERTY = "bazel.binary";
    private static final String ENVIRONMENT_VARIABLE = "BAZEL_BINARY";
    private static final List<String> WELL_KNOWN_LOCATIONS = List.of(
            "/opt/homebrew/bin/bazelisk",
            "/opt/homebrew/bin/bazel",
            "/usr/local/bin/bazelisk",
            "/usr/local/bin/bazel");

    /*
        Resolution stats every PATH entry twice. It used to run on every single process launch, which
        on an import that spawned hundreds of bazel calls meant hundreds of pointless directory
        scans. The answer cannot change while the server is running, so it is resolved once.
     */
    private static volatile String resolved;

    private BazelBinary() {
    }

    public static String resolve(BazelSettings settings) {
        if (settings != null && !settings.getBinary().isBlank()
                && isExecutable(settings.getBinary())) {
            return settings.getBinary();
        }
        String cached = resolved;
        if (cached != null) {
            return cached;
        }
        synchronized (BazelBinary.class) {
            if (resolved == null) {
                resolved = search();
                BazelLog.info("JBazel: using binary " + resolved);
            }
            return resolved;
        }
    }

    public static synchronized void invalidate() {
        resolved = null;
    }

    private static String search() {
        String configured = System.getProperty(SYSTEM_PROPERTY);
        if (isExecutable(configured)) {
            return configured;
        }
        configured = System.getenv(ENVIRONMENT_VARIABLE);
        if (isExecutable(configured)) {
            return configured;
        }
        for (String candidate : searchPath()) {
            if (isExecutable(candidate)) {
                return candidate;
            }
        }
        return "bazel";
    }

    private static List<String> searchPath() {
        List<String> candidates = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(File.pathSeparator)) {
                if (entry.isBlank()) {
                    continue;
                }
                candidates.add(new File(entry, "bazelisk").getAbsolutePath());
                candidates.add(new File(entry, "bazel").getAbsolutePath());
            }
        }
        candidates.addAll(WELL_KNOWN_LOCATIONS);
        return candidates;
    }

    private static boolean isExecutable(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        File file = new File(path);
        return file.isFile() && file.canExecute();
    }
}
