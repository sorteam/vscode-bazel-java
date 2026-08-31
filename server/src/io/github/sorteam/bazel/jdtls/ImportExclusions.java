package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/*
    The glob patterns that keep jdt.ls out of the bazel output tree.

    Why this exists at all. jdt.ls looks for build files by walking the workspace with
    BasicFileDetector, and that walk is Files.walkFileTree(root, EnumSet.of(FOLLOW_LINKS), ...): a
    bazel-out symlink in the repository root sends it into the whole action output tree, millions of
    files, and the import never returns. The extension used to answer that by asking the developer to
    put --experimental_convenience_symlinks=ignore in the bazelrc and delete the symlinks - which
    fixes java by breaking everything else in the repository that reads generated output through
    bazel-bin, TypeScript configs first among them.

    The same detector honours exclusions: its constructor seeds them from
    Preferences.getJavaImportExclusions() and preVisitDirectory returns SKIP_SUBTREE for a match, so
    the symlink is never opened. isExcluded matches the *full path* through a glob PathMatcher, which
    is why absolute patterns work here, not only the doubled-star-slash-name form.

    Two shapes are emitted, and both are wanted:

    - <root>/bazel-* : the standing pair, so a symlink created by a terminal build *after* this ran
      is still skipped;
    - the paths of the symlinks actually found, plus the output base itself: --symlink_prefix renames
      the symlinks, so a name-based pattern alone is not enough.

    No Eclipse types here, so the pattern arithmetic can be unit tested with plain javac. The write
    into jdt.ls preferences lives in BazelProjectImporter, which runs before every fallback importer
    (order 150 against gradle 300, maven 400, eclipse 1000, invisible 1500).
 */
public final class ImportExclusions {

    private ImportExclusions() {
    }

    public static List<String> patterns(File root, List<String> symlinks, File outputBase) {
        Set<String> patterns = new LinkedHashSet<>();
        String base = normalise(root.getAbsolutePath());
        patterns.add(base + "/bazel-*");
        patterns.add(base + "/bazel-*/**");
        for (String symlink : symlinks) {
            patterns.add(base + "/" + symlink);
            patterns.add(base + "/" + symlink + "/**");
        }
        if (outputBase != null) {
            String out = normalise(outputBase.getAbsolutePath());
            patterns.add(out);
            patterns.add(out + "/**");
        }
        return List.copyOf(patterns);
    }

    /* The wanted patterns that are not in the list yet. Empty when the scan is already fenced off. */
    public static List<String> missing(List<String> existing, List<String> wanted) {
        List<String> missing = new ArrayList<>();
        for (String pattern : wanted) {
            if (existing == null || !existing.contains(pattern)) {
                missing.add(pattern);
            }
        }
        return missing;
    }

    /*
        Null when there is nothing to add, so the caller can skip the write - and with it the log
        line - on every import after the first.
     */
    public static List<String> merge(List<String> existing, List<String> wanted) {
        List<String> missing = missing(existing, wanted);
        if (missing.isEmpty()) {
            return null;
        }
        List<String> merged = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        merged.addAll(missing);
        return merged;
    }

    private static String normalise(String path) {
        String normalised = path.replace(File.separatorChar, '/');
        while (normalised.length() > 1 && normalised.endsWith("/")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        return normalised;
    }
}
