package io.github.sorteam.bazel.jdtls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
    Turns discovered bazel targets into the set of Eclipse projects to provision.

    Two problems are solved here. The obvious one is volume: a 900-package monorepo yields 223
    targets and therefore 223 projects, each with its own ~300 entry classpath container, when the
    natural unit is a service with a main and a test source folder - roughly half as many projects.
    The subtler one is that several targets in the same package share one source root (`:library`
    and `:junit` under src/test, for instance); provisioning each separately gives overlapping
    linked folders, so the same files get indexed and diagnosed twice.

    Grouping can be switched off (groupSourceRoots: false) to get the old one-project-per-target
    layout back.

    No Eclipse types here, so the grouping rules can be unit tested with plain javac.
 */
public final class ProjectGrouping {

    /*
        excluded holds paths of other source folders of the same project nested inside this one,
        relative to it. //platform/starter and //platform/starter/src/main are two targets whose
        roots are one inside the other; without the exclusion the outer folder claims the inner
        one's files as well, which both duplicates every type and reports the wrong package for it.
     */
    public record SourceFolder(String path, boolean test, List<String> excluded) {

        public SourceFolder(String path, boolean test) {
            this(path, test, List.of());
        }
    }

    public record ProjectSpec(String name, List<SourceFolder> sourceFolders,
            List<String> mainLabels, List<String> testLabels) {

        public boolean hasTests() {
            return !testLabels.isEmpty();
        }
    }

    private static final Set<String> TEST_TARGET_NAMES = Set.of("junit", "test", "tests");

    private ProjectGrouping() {
    }

    public static List<ProjectSpec> group(List<BazelQuery.Target> targets, boolean grouped,
            String fallbackName) {
        Map<String, Builder> builders = new LinkedHashMap<>();
        for (BazelQuery.Target target : targets) {
            boolean test = isTest(target);
            String name = grouped
                    ? projectName(baseOf(target.packagePath()), fallbackName)
                    : target.projectName();
            builders.computeIfAbsent(name, Builder::new).add(target, test);
        }

        List<ProjectSpec> specs = new ArrayList<>(builders.size());
        builders.values().forEach(builder -> specs.add(builder.build()));
        specs.sort((left, right) -> left.name().compareTo(right.name()));
        return specs;
    }

    /*
        A target is a test target when it lives in a src/test package, or when its name is one of the
        conventional test target names. Getting this wrong is not fatal - it only decides whether the
        jars are marked test-only - but it keeps test-scoped dependencies out of main code.
     */
    static boolean isTest(BazelQuery.Target target) {
        String packagePath = target.packagePath();
        if (packagePath.equals("src/test") || packagePath.endsWith("/src/test")
                || packagePath.contains("/src/test/")) {
            return true;
        }
        int colon = target.label().indexOf(':');
        String targetName = colon < 0 ? "" : target.label().substring(colon + 1);
        return TEST_TARGET_NAMES.contains(targetName);
    }

    /*
        Strips the conventional src/main and src/test suffix so that both halves of a service land in
        the same project. Anything else keeps its package path as the grouping key.
     */
    static String baseOf(String packagePath) {
        for (String suffix : List.of("/src/main", "/src/test")) {
            if (packagePath.endsWith(suffix)) {
                return packagePath.substring(0, packagePath.length() - suffix.length());
            }
        }
        if (packagePath.equals("src/main") || packagePath.equals("src/test")) {
            return "";
        }
        return packagePath;
    }

    static String projectName(String base, String fallbackName) {
        if (base.isEmpty()) {
            return fallbackName;
        }
        return base.replace('/', '.');
    }

    static List<String> nestedIn(String root, List<String> roots) {
        List<String> nested = new ArrayList<>();
        for (String other : roots) {
            if (!other.equals(root) && other.startsWith(root + "/")) {
                nested.add(other.substring(root.length() + 1) + "/**");
            }
        }
        return List.copyOf(nested);
    }

    private static final class Builder {

        private final String name;
        private final Set<String> mainRoots = new LinkedHashSet<>();
        private final Set<String> testRoots = new LinkedHashSet<>();
        private final Set<String> mainLabels = new LinkedHashSet<>();
        private final Set<String> testLabels = new LinkedHashSet<>();

        Builder(String name) {
            this.name = name;
        }

        void add(BazelQuery.Target target, boolean test) {
            (test ? testRoots : mainRoots).add(target.sourceRoot());
            (test ? testLabels : mainLabels).add(target.label());
        }

        ProjectSpec build() {
            List<String> all = new ArrayList<>(mainRoots);
            testRoots.stream().filter(root -> !mainRoots.contains(root)).forEach(all::add);

            List<SourceFolder> folders = new ArrayList<>();
            for (String root : all) {
                // A root used by both halves stays a main folder: marking it test-only would hide
                // production code from itself.
                folders.add(new SourceFolder(root, !mainRoots.contains(root), nestedIn(root, all)));
            }
            return new ProjectSpec(name, List.copyOf(folders),
                    List.copyOf(mainLabels), List.copyOf(testLabels));
        }
    }
}
