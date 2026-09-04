package io.github.sorteam.bazel.jdtls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
    The jars a target needs to *run*, which are not the jars it needs to compile.

    Everything else in this plugin reads the classpath off the Javac action, because that is the one
    bazel guarantees to describe compilation exactly. It cannot describe running: runtime_deps are
    not inputs to javac, so a jdbc driver, a logging backend or a flyway module declared there is
    absent from the classpath the IDE hands to a launch configuration - and the application dies on
    startup with "Cannot load driver class" while bazel run works fine.

    cquery answers this directly and cheaply: JavaInfo.transitive_runtime_jars is the runtime
    closure, it needs analysis but no actions, and it comes back as real jars rather than the ABI
    jars javac consumes. Measured on a 116-project repository: one target in about a second.

    The provider is looked up by the suffix of its key. cquery keys providers by their defining file
    ("@@rules_java+//java/private:java_info.bzl%JavaInfo"), which moved between bazel releases and
    will move again; the trailing "%JavaInfo" is the stable part.

    No Eclipse types here, so the script and the parsing are unit tested with plain javac.
 */
public final class RuntimeClasspath {

    private RuntimeClasspath() {
    }

    /*
        A --starlark:file rather than a --starlark:expr: a target without java at all has no
        JavaInfo, and an expression has nowhere to put that check.
     */
    public static String starlarkScript() {
        return String.join("\n",
                "def format(target):",
                "    infos = [p for k, p in providers(target).items()"
                        + " if k.endswith('%JavaInfo')]",
                "    if not infos:",
                "        return ''",
                "    jars = [f.path for f in infos[0].transitive_runtime_jars.to_list()]",
                "    return '\\t'.join([str(target.label)] + jars)",
                "");
    }

    /*
        One line per target: the label, then its runtime jars, tab separated. Lines that are not
        that - bazel's own progress and info output - are skipped rather than guessed at.
     */
    public static Map<String, List<String>> parse(List<String> lines) {
        Map<String, List<String>> jarsByLabel = new LinkedHashMap<>();
        for (String line : lines) {
            String[] fields = line.split("\t");
            if (fields.length < 2) {
                continue;
            }
            String label = canonical(fields[0]);
            if (label == null) {
                continue;
            }
            List<String> jars = new ArrayList<>(fields.length - 1);
            for (int i = 1; i < fields.length; i++) {
                String jar = fields[i].trim();
                if (jar.endsWith(".jar")) {
                    jars.add(jar);
                }
            }
            if (!jars.isEmpty()) {
                jarsByLabel.put(label, jars);
            }
        }
        return jarsByLabel;
    }

    /*
        Labels come back canonicalised - "@@//services/post/src/main:library" under bzlmod, "@//..."
        before it - while every label this plugin holds came from bazel query as "//...". Anything
        that is not a label of the main repository belongs to nobody here and is dropped.
     */
    private static String canonical(String label) {
        String trimmed = label.trim();
        while (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.startsWith("//") ? trimmed : null;
    }

    /*
        Compile classpath first, runtime jars after it, no duplicates. Order matters to JDT only in
        that the first jar declaring a type wins, and the compile classpath is the one bazel
        compiled against, so it keeps precedence.
     */
    public static List<String> merge(List<String> compile, List<String> runtime) {
        Set<String> merged = new LinkedHashSet<>(compile == null ? List.of() : compile);
        if (runtime != null) {
            merged.addAll(runtime);
        }
        return List.copyOf(merged);
    }
}
