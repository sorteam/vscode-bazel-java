package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/*
    The bazelrc files this plugin can find, and the one option in them the importer has to act on.

    Bazel's own resolution is richer than this - try-import, --bazelrc on the command line, the
    .aspect layer, options that arrive on the command line and never appear in a file at all - so
    reading them is a best effort over the well-known locations: a line found anywhere counts, and
    finding nothing means the caller falls back to what it would have done anyway. Nothing here
    decides anything on its own; it only widens what ImportExclusions fences off.

    No Eclipse types, so the parsing is unit tested with plain javac.
 */
public final class BazelRc {

    private static final String OPTION = "--symlink_prefix";

    private BazelRc() {
    }

    /* The rc files that exist, in the order bazel would read them. */
    public static List<File> candidates(File root) {
        List<File> candidates = new ArrayList<>(List.of(
                new File(root, ".bazelrc"),
                new File(root, ".bazelrc.user"),
                new File(root, "user.bazelrc"),
                new File(System.getProperty("user.home"), ".bazelrc")));
        File[] aspect = new File(root, ".aspect/bazelrc")
                .listFiles((directory, name) -> name.endsWith(".bazelrc"));
        if (aspect != null) {
            candidates.addAll(List.of(aspect));
        }
        candidates.removeIf(candidate -> !candidate.isFile());
        return candidates;
    }

    /* Every rc file concatenated; one that cannot be read is skipped, not reported - Doctor has its
       own reading loop for that, because a missing rc file is a fact worth printing there. */
    public static String read(File root) {
        StringBuilder text = new StringBuilder();
        for (File candidate : candidates(root)) {
            try {
                text.append(Files.readString(candidate.toPath(), StandardCharsets.UTF_8))
                        .append('\n');
            } catch (IOException e) {
                // Unreadable rc file: the prefixes stay at their default, which is the same answer
                // as a repository that never set one.
            }
        }
        return text.toString();
    }

    public static List<String> symlinkPrefixes(File root) {
        return symlinkPrefixes(read(root));
    }

    /*
        The convenience symlink prefixes configured with --symlink_prefix, without the bazel- default
        that the caller covers regardless.

        Wanted because the standing <root>/bazel-* exclusion only guesses at names: a symlink that a
        terminal build creates after the last import is fenced off by name or not at all, and
        --symlink_prefix=out- renames every one of them. Detection by target still catches those on
        the next import attempt (BazelWorkspace.convenienceSymlinks) - this closes the window in
        between.
     */
    public static List<String> symlinkPrefixes(String text) {
        Set<String> prefixes = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            String stripped = line.split("#", 2)[0].trim();
            if (stripped.isEmpty() || !stripped.contains(OPTION)) {
                continue;
            }
            String[] tokens = stripped.split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i];
                String value = null;
                if (token.startsWith(OPTION + "=")) {
                    value = token.substring(OPTION.length() + 1);
                } else if (token.equals(OPTION) && i + 1 < tokens.length) {
                    value = tokens[i + 1];
                }
                String usable = usableAsGlobPrefix(value);
                if (usable != null) {
                    prefixes.add(usable);
                }
            }
        }
        return List.copyOf(prefixes);
    }

    /*
        Null for a prefix that must not be turned into a glob:

        - empty - bazel then names the symlinks bin, out, testlogs and so on, and <root>/* as an
          exclusion would take the entire repository out of the build-file scan;
        - "/" - bazel's spelling of "create no convenience symlinks at all";
        - absolute, so the symlinks are not under the repository root and no <root>/... pattern can
          reach them;
        - anything holding glob metacharacters, which would make the pattern match who knows what.

        A prefix with a slash inside it is kept: it puts the symlinks one directory down, and
        <root>/.bazel/* is exactly the pattern that fences those off, because a glob * does not cross
        a separator.
     */
    private static String usableAsGlobPrefix(String value) {
        if (value == null) {
            return null;
        }
        String prefix = value.trim();
        if (prefix.length() > 1 && (prefix.startsWith("\"") && prefix.endsWith("\"")
                || prefix.startsWith("'") && prefix.endsWith("'"))) {
            prefix = prefix.substring(1, prefix.length() - 1);
        }
        while (prefix.startsWith("./")) {
            prefix = prefix.substring(2);
        }
        if (prefix.isEmpty() || prefix.startsWith("/") || prefix.startsWith("~")
                || prefix.contains("\\")) {
            return null;
        }
        for (char metacharacter : new char[] {'*', '?', '[', ']', '{', '}'}) {
            if (prefix.indexOf(metacharacter) >= 0) {
                return null;
            }
        }
        return prefix;
    }
}
