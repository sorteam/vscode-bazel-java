package io.github.sorteam.bazel.jdtls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
    Streaming parser for `bazel aquery --output=textproto`.

    One aquery over an explicit set of labels returns every Javac action at once, so the action has
    to be mapped back to the target that owns it. That correlation is right there in the output:

        targets { id: 1  label: "//jobs/x/src/main:library" }
        actions { target_id: 1  mnemonic: "Javac"  arguments: "--classpath" ... }

    The two block kinds are interleaved, so actions are buffered and resolved in finish().

    Textproto prints the top-level fields of a message unindented and nested submessages indented,
    which makes indentation useless for structure; depth is tracked by braces instead. No Eclipse
    types are referenced here so that the parser can be unit tested with plain javac.
 */
public final class AqueryParser {

    private static final Pattern BLOCK_START = Pattern.compile("^(\\w+)\\s*\\{\\s*$");
    private static final Pattern ARGUMENT =
            Pattern.compile("^\\s*arguments:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$");
    private static final Pattern LABEL =
            Pattern.compile("^\\s*label:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$");
    private static final Pattern ID = Pattern.compile("^\\s*id:\\s*(\\d+)\\s*$");
    private static final Pattern TARGET_ID = Pattern.compile("^\\s*target_id:\\s*(\\d+)\\s*$");

    private final Map<Integer, String> labelById = new HashMap<>();
    private final List<PendingAction> actions = new ArrayList<>();
    private final Map<String, List<String>> jarsByLabel = new LinkedHashMap<>();

    private String block;
    private int depth;

    private Integer targetId;
    private String targetLabel;
    private List<String> arguments = new ArrayList<>();

    public void accept(String line) {
        if (depth == 0) {
            Matcher start = BLOCK_START.matcher(line);
            if (start.matches()) {
                block = start.group(1);
                depth = 1;
                targetId = null;
                targetLabel = null;
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
        if (depth != 1) {
            return;
        }

        if ("targets".equals(block)) {
            Matcher id = ID.matcher(line);
            if (id.matches()) {
                targetId = Integer.valueOf(id.group(1));
                return;
            }
            Matcher label = LABEL.matcher(line);
            if (label.matches()) {
                targetLabel = unescape(label.group(1));
            }
            return;
        }

        if ("actions".equals(block)) {
            Matcher id = TARGET_ID.matcher(line);
            if (id.matches()) {
                targetId = Integer.valueOf(id.group(1));
                return;
            }
            Matcher argument = ARGUMENT.matcher(line);
            if (argument.matches()) {
                arguments.add(unescape(argument.group(1)));
            }
        }
    }

    private void finishBlock() {
        if ("targets".equals(block) && targetId != null && targetLabel != null) {
            labelById.put(targetId, targetLabel);
        } else if ("actions".equals(block) && !arguments.isEmpty()) {
            actions.add(new PendingAction(targetId, outputJar(arguments),
                    classpathJars(arguments)));
        }
        block = null;
        targetId = null;
        targetLabel = null;
        arguments = new ArrayList<>();
    }

    public void finish() {
        if (depth != 0) {
            finishBlock();
            depth = 0;
        }
        Map<String, Set<String>> collected = new LinkedHashMap<>();
        for (PendingAction action : actions) {
            String label = action.targetId == null ? null : labelById.get(action.targetId);
            if (label == null) {
                continue;
            }
            Set<String> jars = collected.computeIfAbsent(label, key -> new LinkedHashSet<>());
            /*
                The target's own output goes on first, ahead of everything it compiles against.
                Bazel never puts it there - a target does not depend on itself - but the IDE has to
                see it anyway, because that jar is the only place the annotation processors' output
                exists. On this repository that is lombok on almost every target, the JPA static
                metamodel (Entity_ classes), and whole openapi-generated APIs. Types the project
                also has sources for are resolved from the source folder, which sits earlier on the
                classpath, so the jar is only ever consulted for what is not in the working copy.
             */
            if (action.output != null) {
                jars.add(action.output);
            }
            jars.addAll(action.jars);
        }
        collected.forEach((label, jars) -> jarsByLabel.put(label, List.copyOf(jars)));
        actions.clear();
    }

    public Map<String, List<String>> jarsByLabel() {
        return jarsByLabel;
    }

    public int targetCount() {
        return labelById.size();
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

    /*
        Value of the Javac action's --output, which is the jar javac writes its classes into
        (<name>-class.jar; the <name>.jar next to it is that jar merged with the target's
        resources, and is produced by a separate action this query never sees).
     */
    static String outputJar(List<String> arguments) {
        for (int i = 0; i < arguments.size() - 1; i++) {
            if ("--output".equals(arguments.get(i))) {
                String value = arguments.get(i + 1);
                return value.endsWith(".jar") ? value : null;
            }
        }
        return null;
    }

    static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\t", "\t");
    }

    private record PendingAction(Integer targetId, String output, List<String> jars) {
    }
}
