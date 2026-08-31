package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;

/*
    One report answering "why is this repository slow / noisy / red", from the side of the setup that
    the plugin cannot fix by itself.

    Everything here was learned the hard way on a real monorepo, and each check exists because the
    symptom looks like an IDE bug rather than a configuration problem. Read-only by design: it
    reports and names the line to add, and never edits a bazelrc or a settings file. The settings
    half of the report is added by the extension, which can actually see the java.* configuration.
 */
final class Doctor {

    /* Enough to tell "a directory" from "a directory that will eat the workspace scan". */
    private static final int ENTRY_CAP = 20_000;

    /* Depth of the search. node_modules and friends live at the root or one or two levels down. */
    private static final int SEARCH_DEPTH = 3;

    /*
        Directory names that hold fetched dependencies rather than the repository's own code. Only
        these are measured, deliberately: a monorepo's services/ tree is legitimately enormous, and
        telling someone their source directory is too big would be advice they cannot act on. What
        can be acted on is a vendor tree that has no business being inside the workspace at all -
        the node_modules on the repository this was built for is 4.4 GB and 151k files, and jdt.ls
        walks it during the same pre-filter scan that follows the bazel symlinks.
     */
    private static final Set<String> VENDOR_DIRECTORIES = Set.of(
            "node_modules", ".venv", "venv", "vendor", ".yarn", ".pnpm-store", ".nuget");

    private static final long GIGABYTE = 1024L * 1024 * 1024;

    private Doctor() {
    }

    /*
        The exclusions the language server is actually running with. Same internal preferences object
        the importer writes to, so what Doctor reports is what the next scan will do - as opposed to
        what settings.json says, which is what the extension half of the report shows.
     */
    private static List<String> importExclusions() {
        PreferenceManager manager = JavaLanguageServerPlugin.getPreferencesManager();
        if (manager == null || manager.getPreferences() == null) {
            return List.of();
        }
        List<String> exclusions = manager.getPreferences().getJavaImportExclusions();
        return exclusions == null ? List.of() : exclusions;
    }

    static String render(BazelSession session) {
        List<String> problems = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        BazelWorkspace workspace = session.getWorkspace();
        File root = workspace.getRoot();
        ImportReport report = session.getReport();

        facts.add("workspace           : " + root);
        facts.add("bazel binary        : " + BazelBinary.resolve(session.getSettings()));
        facts.add("scope               : " + session.getSettings().universe());
        facts.add("projects imported   : " + report.getProvisionedProjects());

        /*
            The symlinks are fine and are meant to stay - the rest of the repository reads generated
            output through them. What matters is whether jdt.ls's build-file scan, which follows
            symlinks, is fenced off from them; the importer does that on every attempt, so a gap here
            means someone pinned java.import.exclusions to a list of their own.
         */
        List<String> symlinks = workspace.convenienceSymlinks();
        if (symlinks.isEmpty()) {
            facts.add("convenience symlinks: none in the repository root");
        } else {
            List<String> missing = ImportExclusions.missing(importExclusions(),
                    ImportExclusions.patterns(root, symlinks, workspace.peekOutputBase()));
            if (missing.isEmpty()) {
                facts.add("convenience symlinks: " + String.join(", ", symlinks)
                        + " (excluded from the java build-file scan)");
            } else {
                problems.add("The repository root holds " + String.join(", ", symlinks)
                        + ", and java.import.exclusions\n"
                        + "      does not cover them. jdt.ls looks for build files by walking the"
                        + " workspace with\n"
                        + "      FOLLOW_LINKS, so an unfenced bazel-out can park the import in the"
                        + " output tree.\n"
                        + "      Keep the symlinks - other tooling reads them - and add to"
                        + " java.import.exclusions:\n"
                        + "        " + String.join("\n        ", missing));
            }
        }

        for (Path heavy : heavyDirectories(root.toPath())) {
            problems.add("A large dependency directory sits inside the workspace: "
                    + root.toPath().relativize(heavy) + " (over " + ENTRY_CAP + " entries).\n"
                    + "      The first workspace scan walks it before any exclude setting applies,"
                    + " the same way\n"
                    + "      the bazel symlinks are walked, and it holds no java the importer"
                    + " needs.");
        }

        /*
            The effective heap, read from the JVM rather than parsed out of java.jdt.ls.vmargs: only
            a full window reload applies a changed -Xmx, so what the setting says and what the
            server runs with routinely differ - and that difference is itself the answer to "I
            raised the heap and nothing changed".
         */
        long maxHeap = Runtime.getRuntime().maxMemory();
        facts.add(String.format("language server heap: %.1f GB (-Xmx as this JVM actually runs)",
                maxHeap / (double) GIGABYTE));
        int projects = report.getProvisionedProjects();
        if (projects > 80 && maxHeap < 4 * GIGABYTE) {
            problems.add(String.format(
                    "%d projects on a %.1f GB heap. The measured baseline is ~4 GB at this size;"
                            + " below it\n"
                            + "      indexing thrashes the collector. Set java.jdt.ls.vmargs, for"
                            + " example:\n"
                            + "        -XX:+UseG1GC -Xmx4G -Xms512m -XX:+UseStringDeduplication\n"
                            + "      and use Reload Window - restarting only the language server"
                            + " keeps the old JVM.",
                    projects, maxHeap / (double) GIGABYTE));
        } else if (projects > 20 && maxHeap < 2 * GIGABYTE) {
            problems.add(String.format(
                    "%d projects on a %.1f GB heap. Raise -Xmx in java.jdt.ls.vmargs and reload the"
                            + " window.",
                    projects, maxHeap / (double) GIGABYTE));
        }
        if (projects > 50) {
            facts.add("consider            : java.autobuild.enabled=false at this project count -"
                    + " bazel is the build,\n"
                    + "                      and JDT compiling in parallel doubles the CPU. The"
                    + " trade is that\n"
                    + "                      diagnostics then follow the files you open rather"
                    + " than the whole tree.");
        }

        int resolved = report.getResolvedJars();
        int withSources = report.getJarsWithSources();
        facts.add("source attachments  : " + withSources + " of " + resolved + " classpath jars");
        if (resolved > 0 && withSources * 2 < resolved) {
            problems.add("Most classpath jars have no sources, so navigating into a library lands in"
                    + " decompiled\n"
                    + "      bytecode. rules_jvm_external fetches source jars lazily and they are"
                    + " inputs to no\n"
                    + "      action, so nothing ever pulls them - fetch_sources = True is not"
                    + " enough. Run\n"
                    + "      'JBazel: Fetch Library Sources' (a source jar per artifact; expect"
                    + " gigabytes).");
        }

        if (report.getMissingJars() > 0) {
            problems.add(report.getMissingJars() + " classpath jars do not exist on disk. aquery"
                    + " reports what a build would\n"
                    + "      consume, not what was produced. Run 'JBazel: Build Classpath'.");
        }

        problems.addAll(bazelrcProblems(root, facts));
        return format(problems, facts);
    }

    /*
        The bazelrc lines that matter for an IDE, checked by reading the rc files this plugin can
        find. Bazel's own resolution is richer than this - try-import, --bazelrc, the aspect layers -
        so the files that were read are listed rather than implied, and a line found anywhere counts.
     */
    static List<String> bazelrcProblems(File root, List<String> facts) {
        List<String> problems = new ArrayList<>();
        List<File> candidates = new ArrayList<>(List.of(
                new File(root, ".bazelrc"),
                new File(root, ".bazelrc.user"),
                new File(root, "user.bazelrc"),
                new File(System.getProperty("user.home"), ".bazelrc")));
        File aspect = new File(root, ".aspect/bazelrc");
        File[] aspectFiles = aspect.listFiles((dir, name) -> name.endsWith(".bazelrc"));
        if (aspectFiles != null) {
            candidates.addAll(List.of(aspectFiles));
        }

        StringBuilder contents = new StringBuilder();
        List<String> read = new ArrayList<>();
        for (File candidate : candidates) {
            if (!candidate.isFile()) {
                continue;
            }
            try {
                contents.append(Files.readString(candidate.toPath(), StandardCharsets.UTF_8))
                        .append('\n');
                read.add(candidate.getName());
            } catch (IOException e) {
                facts.add("bazelrc unreadable  : " + candidate + " (" + e.getMessage() + ")");
            }
        }
        facts.add("bazelrc files read  : " + (read.isEmpty() ? "none found" : String.join(", ", read)));
        if (read.isEmpty()) {
            return problems;
        }

        String text = contents.toString();
        /*
            No check for --experimental_convenience_symlinks any more. 0.4.0 and 0.5.0 demanded it,
            which was wrong: how a repository configures bazel's own symlinks is not a java
            importer's business, and other tooling there may resolve build outputs through them.
            Keeping jdt.ls out of the output tree is the extension's job (ImportExclusions), not a
            line in someone's bazelrc.
         */
        if (!text.contains("disk_cache")) {
            facts.add("consider            : common --disk_cache=~/.cache/bazel-disk with"
                    + " --experimental_disk_cache_gc_max_size,\n"
                    + "                      so a branch switch reuses outputs instead of"
                    + " rebuilding them.");
        }
        if (!text.contains("max_idle_secs")) {
            facts.add("consider            : startup --max_idle_secs=600, so an idle bazel server"
                    + " releases its JVM heap\n"
                    + "                      instead of competing with the language server for"
                    + " memory.");
        }
        if (!text.contains("--jobs")) {
            facts.add("consider            : build --jobs below your core count, or"
                    + " bazelJava.buildJobs for the IDE's own\n"
                    + "                      builds, so a background build does not starve the"
                    + " editor.");
        }
        return problems;
    }

    /*
        Directories big enough to dominate the first workspace scan. Counting stops at the cap, so
        this is bounded work on a repository of any size; the answer needed is "is this one huge",
        not "how huge".
     */
    static List<Path> heavyDirectories(Path root) {
        return heavyDirectories(root, ENTRY_CAP);
    }

    /* cap is a parameter so the test can ask the question with five files instead of twenty thousand. */
    static List<Path> heavyDirectories(Path root, int cap) {
        List<Path> heavy = new ArrayList<>();
        collect(root, 0, heavy, cap);
        return heavy;
    }

    private static void collect(Path directory, int depth, List<Path> heavy, int cap) {
        if (depth > SEARCH_DEPTH) {
            return;
        }
        File[] children = directory.toFile().listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            Path path = child.toPath();
            if (!child.isDirectory() || Files.isSymbolicLink(path)) {
                // Symlinks are reported by the symlink check; following them here would be the very
                // mistake this whole report exists to warn about.
                continue;
            }
            if (child.getName().equals(".git") || child.getName().startsWith(".bazel")) {
                continue;
            }
            if (VENDOR_DIRECTORIES.contains(child.getName())) {
                if (countEntries(path, cap) >= cap) {
                    heavy.add(path);
                }
                // Never descended into either way: nothing inside is the repository's own code.
                continue;
            }
            collect(path, depth + 1, heavy, cap);
        }
    }

    private static int countEntries(Path directory, int cap) {
        int[] count = new int[1];
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    return ++count[0] >= cap ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    return attributes.isSymbolicLink() ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return 0;
        }
        return count[0];
    }

    private static String format(List<String> problems, List<String> facts) {
        StringBuilder out = new StringBuilder();
        facts.forEach(fact -> out.append("  ").append(fact).append('\n'));
        out.append('\n');
        if (problems.isEmpty()) {
            out.append("  Nothing to fix on the bazel side.\n");
            return out.toString();
        }
        for (int i = 0; i < problems.size(); i++) {
            out.append("  ").append(i + 1).append(". ").append(problems.get(i)).append("\n\n");
        }
        return out.toString();
    }
}
