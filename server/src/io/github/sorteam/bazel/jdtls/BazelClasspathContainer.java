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
    private final int withSources;

    private BazelClasspathContainer(List<IClasspathEntry> entries, int missing, int withSources) {
        this.entries = entries.toArray(IClasspathEntry[]::new);
        this.missing = missing;
        this.withSources = withSources;
    }

    public static BazelClasspathContainer empty() {
        return new BazelClasspathContainer(List.of(), 0, 0);
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
        int[] counts = new int[2];

        add(resolved, seen, executionRoot, mainJars, NO_ATTRIBUTES, counts);
        add(resolved, seen, executionRoot, testJars, TEST_ONLY, counts);
        return new BazelClasspathContainer(resolved, counts[0], counts[1]);
    }

    /* counts[0] accumulates jars absent from disk, counts[1] those that got a source attachment. */
    private static void add(List<IClasspathEntry> resolved, Set<String> seen, File executionRoot,
            Collection<String> jars, IClasspathAttribute[] attributes, int[] counts) {
        if (jars == null) {
            return;
        }
        for (String jar : jars) {
            if (!seen.add(jar)) {
                continue;
            }
            File file = jarFile(executionRoot, jar);
            if (!file.isFile()) {
                /*
                    aquery reports the jars a Javac action would consume, not jars that exist. The
                    repository sets --nojava_header_compilation, so these are full compile outputs
                    that only exist once something has actually been built. Dropping them silently
                    is what makes a fresh clone look like a project with no dependencies, so the
                    count is reported and surfaced by the import report.
                 */
                counts[0]++;
                continue;
            }
            IPath jarPath = new Path(file.getAbsolutePath());
            File sources = sourcesFor(file);
            if (sources != null) {
                counts[1]++;
            }
            resolved.add(JavaCore.newLibraryEntry(jarPath,
                    sources == null ? null : new Path(sources.getAbsolutePath()), null,
                    null, attributes, false));
        }
    }

    public int getResolvedCount() {
        return entries.length;
    }

    public int getMissingCount() {
        return missing;
    }

    /*
        How many resolved entries opened with real sources rather than decompiled bytecode. Low on
        most repositories for a reason worth surfacing: rules_jvm_external fetches source jars
        lazily, and since they are inputs to no action, nothing ever pulls them - even with
        fetch_sources = True. That is what "JBazel: Fetch Library Sources" exists for.
     */
    public int getSourceAttachmentCount() {
        return withSources;
    }

    /*
        vscode-java decides whether to enable lombok by looking for a lombok-<version>.jar on the
        project classpath and then loading that exact file with -javaagent. What bazel puts on the
        compile classpath is the interface jar, header_lombok-*.jar: it carries the name the search
        matches but none of the bytecode the agent needs. The real jar is written next to it, so
        that one goes on the classpath instead. Only lombok is treated this way - preferring full
        jars everywhere would grow the index by hundreds of megabytes for no benefit.
     */
    static File jarFile(File executionRoot, String jar) {
        File file = jar.startsWith("/") ? new File(jar) : new File(executionRoot, jar);
        File full = fullJarFor(executionRoot, jar, file);
        return full == null ? file : full;
    }

    /*
        The real jar behind an ABI jar, or null when there is none to be found.

        aquery reports what javac consumes, and that is an ABI jar: bazel's ijar or turbine strips
        every method body, keeping only signatures. It is enough to compile against and it is not
        enough to run: a class loaded from one dies with "ClassFormatError: Absent Code attribute in
        method that is not native or abstract", which is what a launch configuration built from this
        classpath hits the moment the application starts. The names differ by producer -
        "header_spring-boot-4.0.7.jar" for a maven dependency, "liblibrary-ijar.jar" for a target of
        the repository itself - and the real jar sits next to the stripped one under the name without
        the marker.

        Preferring it also means navigating into a library shows code instead of empty stubs, and
        that the file name is the maven artifact name, which other tooling reads: the Spring Boot
        Dashboard decides whether a project is an application by looking for a classpath jar whose
        name starts with "spring-boot".

        Every candidate is checked on disk, so an ABI jar with no counterpart - and there are such -
        stays exactly as aquery reported it.
     */
    private static File fullJarFor(File executionRoot, String jar, File file) {
        String name = file.getName();
        String base = null;
        if (name.startsWith("header_") && name.endsWith(".jar")) {
            base = name.substring("header_".length(), name.length() - ".jar".length());
        } else if (name.endsWith("-ijar.jar")) {
            base = name.substring(0, name.length() - "-ijar.jar".length());
        } else if (name.endsWith("-hjar.jar")) {
            base = name.substring(0, name.length() - "-hjar.jar".length());
        }
        if (base == null) {
            return null;
        }
        /*
            <base>.jar is a java_library's output with its resources merged in, <base>-class.jar the
            javac output before that merge. The first is what bazel itself would put on a runtime
            classpath, so it goes first.
         */
        for (String candidate : List.of(base + ".jar", base + "-class.jar")) {
            File sibling = new File(file.getParentFile(), candidate);
            if (sibling.isFile()) {
                return sibling;
            }
        }
        return outsideTheIjarMirror(executionRoot, jar, base);
    }

    /*
        ijar run over the jars of an external repository - java_import over a downloaded
        distribution - writes into <repo>/_ijar/<package>/<repo>/<path inside the repository>, a
        mirror of where the original came from. The repository name therefore appears twice, and the
        tail after its last occurrence is the path to the original inside that repository.
     */
    private static File outsideTheIjarMirror(File executionRoot, String jar, String base) {
        int mirror = jar.indexOf("/_ijar/");
        if (mirror < 0 || jar.startsWith("/")) {
            return null;
        }
        String head = jar.substring(0, mirror);
        String repository = head.substring(head.lastIndexOf('/') + 1);
        int tail = jar.lastIndexOf(repository + "/");
        if (repository.isEmpty() || tail <= mirror) {
            return null;
        }
        String inside = jar.substring(tail + repository.length() + 1);
        int slash = inside.lastIndexOf('/');
        File original = new File(executionRoot, "external/" + repository + "/"
                + inside.substring(0, slash + 1) + base + ".jar");
        return original.isFile() ? original : null;
    }

    static File sourcesFor(File jar) {
        String name = jar.getName();
        String base = name.startsWith("header_") ? name.substring("header_".length()) : name;
        base = base.substring(0, base.length() - ".jar".length());
        File candidate = new File(jar.getParentFile(), base + "-sources.jar");
        if (candidate.isFile()) {
            return candidate;
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
                return generated;
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
        return "JBazel Dependencies";
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
