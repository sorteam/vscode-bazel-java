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

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

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

    /* Enough of the log to hold a recurring failure, small enough to read on every report. */
    private static final int LOG_TAIL_BYTES = 256 * 1024;

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
        facts.add("runtime classpath   : " + (session.getSettings().isRuntimeClasspath()
                ? "on (runtime_deps are on the project classpath)"
                : "off (compile classpath only; an IDE launch may miss runtime_deps)"));
        facts.add("project layout      : " + (session.getSettings().isRepositoryLayout()
                ? "repository (directories at the bazel packages; class output stays in metadata)"
                : "metadata (nothing written to the working copy)"));
        facts.add("projects imported   : " + report.getProvisionedProjects());

        /*
            The symlinks are fine and are meant to stay - the rest of the repository reads generated
            output through them. What matters is whether jdt.ls's build-file scan, which follows
            symlinks, is fenced off from them; the importer does that on every attempt, so a gap here
            means someone pinned java.import.exclusions to a list of their own.
         */
        List<String> symlinks = workspace.convenienceSymlinks();
        List<String> prefixes = BazelRc.symlinkPrefixes(root);
        if (!prefixes.isEmpty()) {
            facts.add("symlink prefix      : " + String.join(", ", prefixes)
                    + " (--symlink_prefix in the bazelrc; excluded by name as well as by target)");
        }
        if (symlinks.isEmpty()) {
            facts.add("convenience symlinks: none in the repository root");
        } else {
            List<String> missing = ImportExclusions.missing(importExclusions(),
                    ImportExclusions.patterns(root, symlinks, workspace.peekOutputBase(), prefixes));
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
        problems.addAll(indexProblems(metadataDirectory(), facts));
        problems.addAll(duplicateServerProblems(metadataDirectory(), facts));
        problems.addAll(blockingBundleProblems(projects, facts));
        return format(problems, facts);
    }

    /*
        A language server extension that cannot exit is the most expensive failure on this list, and
        the only warning of it is a process still holding gigabytes after the editor is gone.

        The mechanism, from a thread dump of one such process: an extension bundle registers a global
        job-change listener, and its done() callback - which the platform runs from inside
        JobManager.withWriteLock, holding the write lock on the whole job manager - walks every
        project in the workspace and makes one *blocking* client round trip each
        (JavaClientConnection.executeClientCommand, joined with no timeout). While the editor is
        there those calls answer or fail fast. When the editor has gone away there is nobody left to
        answer, the join never returns, the job manager's write lock is never released, and from that
        moment nothing in the platform can run: the workspace cannot be saved and the process cannot
        shut down. It has to be killed, and until it is it keeps the lock on the language server's
        workspace directory - which is what makes the next start time out and "clean the workspace"
        fail with ENOTEMPTY half way through, leaving a workspace that is inconsistent rather than
        empty.

        Nothing in this plugin can prevent that, and this plugin is what makes it reachable: the
        listener's cost is per project, and importing a monorepo is how a workspace comes to have a
        hundred of them. So it is reported, with the project count that decides how bad it is, rather
        than left to be rediscovered from a thread dump.
     */
    private static final String BLOCKING_BUNDLE = "org.springframework.tooling.jdt.ls.extension";

    static List<String> blockingBundleProblems(int projects, List<String> facts) {
        List<String> problems = new ArrayList<>();
        String version = bundleVersion(BLOCKING_BUNDLE);
        if (version == null) {
            return problems;
        }
        facts.add("blocking-listener   : " + BLOCKING_BUNDLE + " " + version + " is loaded");
        if (projects < 20) {
            return problems;
        }
        problems.add(BLOCKING_BUNDLE + " " + version + " is loaded alongside " + projects
                + " projects.\n"
                + "      It registers a job-change listener whose callback runs while the platform's"
                + " job-manager\n"
                + "      write lock is held, and makes one blocking client round trip per project"
                + " with no\n"
                + "      timeout. If the editor closes during that walk the round trip never"
                + " answers, the lock is\n"
                + "      never released, and the language server cannot exit - it has to be killed,"
                + " and until it\n"
                + "      is it holds the lock on this workspace directory, which is what makes the"
                + " next start\n"
                + "      time out and 'clean the workspace' fail half way through. Disable that"
                + " extension for\n"
                + "      this workspace if the language server keeps outliving the editor.");
        return problems;
    }

    /* Reads the OSGi framework rather than the extension list: this is about what got loaded. */
    private static String bundleVersion(String symbolicName) {
        try {
            BundleContext context = JavaLanguageServerPlugin.getBundleContext();
            if (context == null) {
                return null;
            }
            for (Bundle bundle : context.getBundles()) {
                if (symbolicName.equals(bundle.getSymbolicName())) {
                    return String.valueOf(bundle.getVersion());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return null;
    }

    /*
        The language server's .metadata directory, or null when the workspace has no location on
        disk. This is where JDT keeps its index cache, which is the one piece of state that can turn
        a healthy repository into an unusable one.
     */
    private static Path metadataDirectory() {
        IPath location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
        return location == null ? null : location.toFile().toPath().resolve(".metadata");
    }

    /*
        The JDT index cache, and the one failure of it that reads like a bug in this extension.

        A language server that is killed while saving an index leaves the file half written. JDT
        reads a length field out of it later, gets a garbage number, tries to allocate an array that
        size and dies with OutOfMemoryError - measured on this failure: "Failed to read index data
        ... at offset 8600 and size 1936028278" on a 16 GB heap. It recovers by deleting the file and
        reindexing, but the report is worth having: raising -Xmx does nothing, and the whole cache is
        derived data that can simply be deleted.

        Two signals, both cheap: a leftover .index.tmp is an interrupted write, and the failure
        itself is in the language server's own log. Only the tail of that log is read - the message
        recurs for as long as the problem exists, so the tail is where it will be.
     */
    static List<String> indexProblems(Path metadata, List<String> facts) {
        List<String> problems = new ArrayList<>();
        if (metadata == null) {
            return problems;
        }
        Path indexes = metadata.resolve(".plugins/org.eclipse.jdt.core");
        if (!Files.isDirectory(indexes)) {
            return problems;
        }
        long bytes = 0;
        int files = 0;
        int halfWritten = 0;
        try (var stream = Files.newDirectoryStream(indexes)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".index")) {
                    files++;
                    bytes += entry.toFile().length();
                } else if (name.endsWith(".index.tmp")) {
                    halfWritten++;
                }
            }
        } catch (IOException e) {
            facts.add("jdt index unreadable: " + indexes + " (" + e.getMessage() + ")");
            return problems;
        }
        facts.add(String.format("jdt index          : %d files, %.1f GB in %s", files,
                bytes / (double) GIGABYTE, indexes.getFileName()));

        boolean corrupt = logMentionsCorruptIndex(metadata.resolve(".log"));
        if (corrupt || halfWritten > 0) {
            problems.add((corrupt
                    ? "The language server log reports an unreadable JDT index"
                    : halfWritten + " half-written JDT index file(s) are left over")
                    + ", which is how a\n"
                    + "      language server killed mid-save shows up. JDT reads a length out of a"
                    + " truncated index,\n"
                    + "      allocates that much and dies with OutOfMemoryError - raising -Xmx does"
                    + " not help. The\n"
                    + "      whole directory is derived data: close the window, delete\n"
                    + "        " + indexes + "\n"
                    + "      and reopen. Expect one full reindex.");
        }
        return problems;
    }

    private static boolean logMentionsCorruptIndex(Path log) {
        return logTail(log).contains("Failed to read index data");
    }

    /*
        Two language servers on one workspace directory, which is a state nothing inside the server
        can see and nothing inside it can end.

        The language client starts the server over a pipe and gives the whole start - process, pipe,
        initialize, feature registration - thirty seconds. On a machine already short of memory that
        budget runs out, and what happens then is that a second server is started on the same
        directory over stdio while the first one is left running. The second client then tries to
        register the commands the first one has already registered, fails, and retries: every retry
        re-initializes the workspace, so the server re-imports, re-indexes and writes another
        megabyte of log, for as long as the window stays open. Both servers also share one JDT index,
        which is how the index ends up truncated.

        Worth naming because every symptom of it points somewhere else - java processes that will not
        die, a machine out of memory, an import that runs over and over - and because the recovery is
        not obvious: closing the window is not enough while a second window still holds a server on
        the same directory.

        Both signals are cheap and both are decisive. A healthy server logs ">> initialize" once per
        process; the client writes one line naming the fallback the moment it takes it.
     */
    static List<String> duplicateServerProblems(Path metadata, List<String> facts) {
        List<String> problems = new ArrayList<>();
        if (metadata == null) {
            return problems;
        }
        int initializations = occurrences(logTail(metadata.resolve(".log")), ">> initialize\n");
        boolean fellBack = clientLogReportsStdioFallback(metadata);
        if (initializations > 1) {
            facts.add("server initialized  : " + initializations
                    + " times in the tail of the log (once is normal)");
        }
        if (initializations > 1 || fellBack) {
            problems.add("The language client started a second language server on this workspace"
                    + " directory\n"
                    + "      " + (fellBack
                            ? "(its log names the fallback from 'pipe' to 'stdio')"
                            : "(the server log shows it being initialized repeatedly)")
                    + " and left the first one running. The two\n"
                    + "      share one JDT index and neither of them will settle. Close every"
                    + " window open on this\n"
                    + "      repository, wait for the java processes to exit, and reopen one. This is"
                    + " the language\n"
                    + "      client's own start timeout, not the import: it runs out on a machine"
                    + " already short of\n"
                    + "      memory, which the previous occurrence of this leaves it.");
        }
        return problems;
    }

    /*
        The client's log sits beside the workspace directory rather than inside it, because it is
        written by the extension host and not by the server.
     */
    private static boolean clientLogReportsStdioFallback(Path metadata) {
        Path directory = metadata.getParent() == null ? null : metadata.getParent().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return false;
        }
        try (var stream = Files.newDirectoryStream(directory, "client.log*")) {
            for (Path log : stream) {
                if (logTail(log).contains("Falling back to 'stdio'")) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private static int occurrences(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }

    /* Bounded: only the last stretch of the log, which is where a recurring failure lives. */
    private static String logTail(Path log) {
        if (log == null || !Files.isRegularFile(log)) {
            return "";
        }
        try (java.io.RandomAccessFile handle = new java.io.RandomAccessFile(log.toFile(), "r")) {
            long from = Math.max(0, handle.length() - LOG_TAIL_BYTES);
            handle.seek(from);
            byte[] tail = new byte[(int) Math.min(LOG_TAIL_BYTES, handle.length() - from)];
            handle.readFully(tail);
            return new String(tail, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /*
        The bazelrc lines that matter for an IDE, checked by reading the rc files this plugin can
        find (BazelRc.candidates - the same list the importer reads --symlink_prefix from). Bazel's
        own resolution is richer than this - try-import, --bazelrc, the aspect layers - so the files
        that were read are listed rather than implied, and a line found anywhere counts. Read here
        rather than through BazelRc.read because an rc file that exists and cannot be read is itself
        worth reporting.
     */
    static List<String> bazelrcProblems(File root, List<String> facts) {
        List<String> problems = new ArrayList<>();
        StringBuilder contents = new StringBuilder();
        List<String> read = new ArrayList<>();
        for (File candidate : BazelRc.candidates(root)) {
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
