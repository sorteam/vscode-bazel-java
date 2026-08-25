package ch.audienzz.bazel.jdtls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;

public final class BazelClasspathCache {

    private static final Pattern BLOCK_START = Pattern.compile("^(\\w+)\\s*\\{\\s*$");
    private static final Pattern ARGUMENT =
            Pattern.compile("^\\s*arguments:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$");

    private static final BazelClasspathCache INSTANCE = new BazelClasspathCache();

    private final Map<String, List<String>> jarsByLabel = new HashMap<>();

    private BazelClasspathCache() {
    }

    public static BazelClasspathCache getInstance() {
        return INSTANCE;
    }

    public synchronized void clear() {
        jarsByLabel.clear();
    }

    /*
        Scoped to the one label on purpose. aquery over //... has to analyse every configured target
        in the repo before it can report a single --classpath, which on this monorepo is the dominant
        cost of importing any project. A single target's Javac action needs only its own closure
        analysed, and the bazel server shares that analysis across the queries that follow.
     */
    public List<String> jarsFor(BazelWorkspace workspace, String label, IProgressMonitor monitor)
            throws CoreException {
        synchronized (this) {
            List<String> cached = jarsByLabel.get(label);
            if (cached != null) {
                return cached;
            }
        }

        long started = System.currentTimeMillis();
        Collector collector = new Collector();
        workspace.runStreaming(monitor, collector::accept, "aquery",
                "mnemonic(\"Javac\", " + label + ")", "--output=textproto",
                "--noshow_progress", "--keep_going", "--ui_event_filters=-info");
        collector.finish();
        List<String> jars = collector.jars();

        if (jars.isEmpty()) {
            JavaLanguageServerPlugin.logInfo(
                    "Bazel: no Javac action found for " + label + ", classpath will be empty");
        } else {
            JavaLanguageServerPlugin.logInfo(String.format("Bazel: %d classpath jars for %s in %d ms",
                    jars.size(), label, System.currentTimeMillis() - started));
        }

        synchronized (this) {
            jarsByLabel.putIfAbsent(label, jars);
            return jarsByLabel.get(label);
        }
    }

    /*
        The query names a single target, so every Javac action in the output belongs to it and the
        action-to-label correlation the repo-wide query needed is gone with it.
     */
    private static final class Collector {

        private final Set<String> jars = new LinkedHashSet<>();

        private String block;
        private int depth;
        private List<String> arguments = new ArrayList<>();

        void accept(String line) {
            if (depth == 0) {
                Matcher start = BLOCK_START.matcher(line);
                if (start.matches()) {
                    block = start.group(1);
                    depth = 1;
                    arguments = new ArrayList<>();
                }
                return;
            }

            String trimmed = line.trim();
            if (trimmed.endsWith("{")) {
                depth++;
                return;
            }
            if (trimmed.equals("}")) {
                depth--;
                if (depth == 0) {
                    finishBlock();
                }
                return;
            }
            if (depth != 1 || !"actions".equals(block)) {
                return;
            }

            Matcher argument = ARGUMENT.matcher(line);
            if (argument.matches()) {
                arguments.add(unescape(argument.group(1)));
            }
        }

        private void finishBlock() {
            if ("actions".equals(block) && !arguments.isEmpty()) {
                jars.addAll(classpathJars(arguments));
            }
            block = null;
            arguments = new ArrayList<>();
        }

        void finish() {
            if (depth != 0) {
                finishBlock();
                depth = 0;
            }
        }

        List<String> jars() {
            return List.copyOf(jars);
        }
    }

    static List<String> classpathJars(List<String> arguments) {
        Set<String> jars = new LinkedHashSet<>();
        for (int i = 0; i < arguments.size(); i++) {
            if (!"--classpath".equals(arguments.get(i))) {
                continue;
            }
            for (int j = i + 1; j < arguments.size() && !arguments.get(j).startsWith("--"); j++) {
                String value = arguments.get(j);
                if (value.endsWith(".jar")) {
                    jars.add(value);
                }
            }
        }
        return new ArrayList<>(jars);
    }

    static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\t", "\t");
    }
}
