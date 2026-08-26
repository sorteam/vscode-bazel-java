package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;

public class BazelClasspathContainer implements IClasspathContainer {

    public static final String CONTAINER_ID = "io.github.sorteam.bazel.jdtls.BAZEL_CONTAINER";
    public static final IPath CONTAINER_PATH = new Path(CONTAINER_ID);

    private static final IClasspathAttribute[] TEST_ONLY =
            { JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true") };
    private static final IClasspathAttribute[] NO_ATTRIBUTES = {};

    private final IClasspathEntry[] entries;
    private final int missing;

    private BazelClasspathContainer(List<IClasspathEntry> entries, int missing) {
        this.entries = entries.toArray(IClasspathEntry[]::new);
        this.missing = missing;
    }

    public static BazelClasspathContainer empty() {
        return new BazelClasspathContainer(List.of(), 0);
    }

    /*
        Jars that only the test half of a project depends on are marked test-only so that production
        code cannot accidentally compile against them. When main and test share a jar, main wins.
     */
    public static BazelClasspathContainer fromJars(File executionRoot,
            Collection<String> mainJars, Collection<String> testJars) {
        if (executionRoot == null) {
            return empty();
        }
        List<IClasspathEntry> resolved = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int missing = 0;

        missing += add(resolved, seen, executionRoot, mainJars, NO_ATTRIBUTES);
        missing += add(resolved, seen, executionRoot, testJars, TEST_ONLY);
        return new BazelClasspathContainer(resolved, missing);
    }

    private static int add(List<IClasspathEntry> resolved, Set<String> seen, File executionRoot,
            Collection<String> jars, IClasspathAttribute[] attributes) {
        if (jars == null) {
            return 0;
        }
        int missing = 0;
        for (String jar : jars) {
            if (!seen.add(jar)) {
                continue;
            }
            File file = resolveJar(executionRoot, jar);
            if (!file.isFile()) {
                /*
                    aquery reports the jars a Javac action would consume, not jars that exist. The
                    repository sets --nojava_header_compilation, so these are full compile outputs
                    that only exist once something has actually been built. Dropping them silently
                    is what makes a fresh clone look like a project with no dependencies, so the
                    count is reported and surfaced by the import report.
                 */
                missing++;
                continue;
            }
            IPath jarPath = new Path(file.getAbsolutePath());
            resolved.add(JavaCore.newLibraryEntry(jarPath, sourcesFor(file), null,
                    null, attributes, false));
        }
        return missing;
    }

    public int getResolvedCount() {
        return entries.length;
    }

    public int getMissingCount() {
        return missing;
    }

    /*
        vscode-java decides whether to enable lombok by looking for a lombok-<version>.jar on the
        project classpath and then loading that exact file with -javaagent. What bazel puts on the
        compile classpath is the interface jar, header_lombok-*.jar: it carries the name the search
        matches but none of the bytecode the agent needs. The real jar is written next to it, so
        that one goes on the classpath instead. Only lombok is treated this way - preferring full
        jars everywhere would grow the index by hundreds of megabytes for no benefit.
     */
    private static File resolveJar(File executionRoot, String jar) {
        File file = new File(executionRoot, jar);
        String name = file.getName();
        if (name.startsWith("header_lombok-") && name.endsWith(".jar")) {
            File full = new File(file.getParentFile(), name.substring("header_".length()));
            if (full.isFile()) {
                return full;
            }
        }
        return file;
    }

    private static IPath sourcesFor(File jar) {
        String name = jar.getName();
        String base = name.startsWith("header_") ? name.substring("header_".length()) : name;
        base = base.substring(0, base.length() - ".jar".length());
        File candidate = new File(jar.getParentFile(), base + "-sources.jar");
        if (candidate.isFile()) {
            return new Path(candidate.getAbsolutePath());
        }
        /*
            A target's own -class.jar has no sources jar; what it has is the -gensrc.jar the same
            Javac action wrote, holding exactly the sources the annotation processors generated.
            Attaching it costs nothing for the rest of the jar: everything else in there is in the
            project's source folder, which JDT reaches first.
         */
        if (base.endsWith("-class")) {
            File generated = new File(jar.getParentFile(),
                    base.substring(0, base.length() - "-class".length()) + "-gensrc.jar");
            if (generated.isFile()) {
                return new Path(generated.getAbsolutePath());
            }
        }
        return null;
    }

    @Override
    public IClasspathEntry[] getClasspathEntries() {
        return entries;
    }

    @Override
    public String getDescription() {
        return "Bazel Dependencies";
    }

    @Override
    public int getKind() {
        return IClasspathContainer.K_APPLICATION;
    }

    @Override
    public IPath getPath() {
        return CONTAINER_PATH;
    }
}
