package io.github.sorteam.bazel.jdtls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*
    Plain-main test runner. The build is javac and jar with no Maven or Tycho, so the tests follow:
    no framework, no OSGi, only classes that do not touch the Eclipse runtime.

    What is covered is what silently breaks: the aquery textproto shape (a bazel release could move
    a field and the classpath would just come back empty), the grouping rules, and the settings and
    query parsing that decide what gets imported at all.
 */
public final class PluginTests {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        aqueryParserCorrelatesActionsToLabels();
        aqueryParserHandlesNestedBlocksAndEscapes();
        aqueryParserStopsClasspathAtNextFlag();
        aqueryParserReadsOwnOutput();
        sourceRootFollowsDeclaredPackages();
        nestedSourceRootsAreExcluded();
        relocationScanFindsMisplacedFiles();
        groupingMergesMainAndTest();
        groupingDeduplicatesSharedSourceRoots();
        groupingCanBeDisabled();
        settingsReadBazelProjectDirectories();
        settingsUniverseHandlesExclusions();
        queryParsesTargetsAndSkipsSourcelessRules();
        querySkipsAmbiguousSourceRoots();
        gitStateSeesCheckoutRebaseAndWorktrees();
        containerStampTracksJarsOrderAndModification();
        partialAqueryKeepsPopulatedClasspaths();
        failureGateBusyWindowDoesNotEscalate();
        watchdogKillsASilentProcess();
        busyServerIsClassifiedAsBusyNotAsFailure();
        ideBuildsRefuseToPlantConvenienceSymlinks();
        convenienceSymlinksInTheRootAreReported();

        System.out.printf("%d checks, %d failure(s)%n", checks, FAILURES.size());
        FAILURES.forEach(failure -> System.out.println("  FAIL " + failure));
        if (!FAILURES.isEmpty()) {
            System.exit(1);
        }
    }

    /* ---------------------------------------------------------------- aquery */

    private static final String SAMPLE = String.join("\n",
            "rule_classes {",
            "id: 1",
            "name: \"java_library\"",
            "}",
            "targets {",
            "id: 1",
            "label: \"//jobs/report/src/main:library\"",
            "rule_class_id: 1",
            "}",
            "configuration {",
            "id: 1",
            "mnemonic: \"darwin_arm64-fastbuild\"",
            "}",
            "actions {",
            "target_id: 1",
            "mnemonic: \"Javac\"",
            "configuration_id: 1",
            "arguments: \"--output\"",
            "arguments: \"bazel-out/libreport-class.jar\"",
            "arguments: \"--classpath\"",
            "arguments: \"bazel-out/one.jar\"",
            "arguments: \"bazel-out/two.jar\"",
            "arguments: \"--sources\"",
            "arguments: \"bazel-out/not-a-dependency.jar\"",
            "environment_variables {",
            "  key: \"PATH\"",
            "  value: \"/bin\"",
            "}",
            "}",
            "targets {",
            "id: 2",
            "label: \"//jobs/report/src/test:library\"",
            "rule_class_id: 1",
            "}",
            "actions {",
            "target_id: 2",
            "mnemonic: \"Javac\"",
            "arguments: \"--classpath\"",
            "arguments: \"bazel-out/three.jar\"",
            "}");

    private static void sourceRootFollowsDeclaredPackages() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("bazel-java-test");
        java.nio.file.Path dir = root.resolve("platform/gen/src/main/java/com/github/cli");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("Application.java"),
                "package com.github.cli;\n\nclass Application { }\n");
        List<String> sources = List.of("//platform/gen:src/main/java/com/github/cli/Application.java");

        check("root taken from the declared package, not the first path segment",
                "platform/gen/src/main/java".equals(
                        BazelQuery.rootFromPackages(root.toFile(), "platform/gen", sources)),
                String.valueOf(BazelQuery.rootFromPackages(root.toFile(), "platform/gen", sources)));
        check("path heuristic still says src",
                "platform/gen/src".equals(BazelQuery.commonSourceRoot("platform/gen", sources)),
                BazelQuery.commonSourceRoot("platform/gen", sources));

        java.nio.file.Path loose = root.resolve("platform/starter");
        java.nio.file.Files.createDirectories(loose);
        java.nio.file.Files.writeString(loose.resolve("PropsMigrator.java"),
                "package platform.starter;\n");
        check("a package spanning the repository root is refused",
                BazelQuery.rootFromPackages(root.toFile(), "platform/starter",
                        List.of("//platform/starter:PropsMigrator.java")) == null, "");
        check("no declaration to go on falls back",
                BazelQuery.rootFromPackages(root.toFile(), "platform/gen",
                        List.of("//platform/gen:missing/Absent.java")) == null, "");
    }

    private static void relocationScanFindsMisplacedFiles() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("bazel-java-relocate");
        java.nio.file.Path good = root.resolve("com/example/ok");
        java.nio.file.Files.createDirectories(good);
        java.nio.file.Files.writeString(good.resolve("Fine.java"), "package com.example.ok;\n");
        java.nio.file.Path stray = root.resolve("com/example/here");
        java.nio.file.Files.createDirectories(stray);
        java.nio.file.Files.writeString(stray.resolve("Stray.java"),
                "package com.example.elsewhere;\n");
        java.nio.file.Path nested = root.resolve("src/main/java/com/example/inner");
        java.nio.file.Files.createDirectories(nested);
        java.nio.file.Files.writeString(nested.resolve("Inner.java"), "package com.example.inner;\n");

        List<SourceRelocation.Misplaced> all =
                SourceRelocation.scan(root.toFile(), List.of());
        check("the nested root looks misplaced from outside", all.size() == 2,
                String.valueOf(all));

        List<SourceRelocation.Misplaced> scoped =
                SourceRelocation.scan(root.toFile(), List.of("src"));
        check("skipping the nested root leaves only the real stray", scoped.size() == 1,
                String.valueOf(scoped));
        SourceRelocation.Misplaced only = scoped.get(0);
        check("stray file recorded with its declared package",
                "com/example/here/Stray.java".equals(only.relativePath())
                        && "com.example.elsewhere".equals(only.declaredPackage())
                        && "Stray.java".equals(only.fileName()),
                String.valueOf(only));
    }

    private static void nestedSourceRootsAreExcluded() {
        List<String> roots = List.of("platform/starter", "platform/starter/src/main/java");
        check("outer root excludes the nested one",
                List.of("src/main/java/**").equals(
                        ProjectGrouping.nestedIn("platform/starter", roots)),
                String.valueOf(ProjectGrouping.nestedIn("platform/starter", roots)));
        check("nested root excludes nothing",
                ProjectGrouping.nestedIn("platform/starter/src/main/java", roots).isEmpty(), "");
        check("siblings do not exclude each other",
                ProjectGrouping.nestedIn("a/src/main", List.of("a/src/main", "a/src/test")).isEmpty(),
                "");
    }

    private static void aqueryParserReadsOwnOutput() {
        check("own output picked up",
                "bazel-out/lib-class.jar".equals(AqueryParser.outputJar(
                        List.of("--output", "bazel-out/lib-class.jar", "--classpath", "a.jar"))),
                String.valueOf(AqueryParser.outputJar(
                        List.of("--output", "bazel-out/lib-class.jar"))));
        check("non-jar output ignored",
                AqueryParser.outputJar(List.of("--output", "bazel-out/lib.txt")) == null, "");
        check("absent output ignored",
                AqueryParser.outputJar(List.of("--classpath", "a.jar")) == null, "");
    }

    private static void aqueryParserCorrelatesActionsToLabels() {
        Map<String, List<String>> jars = parse(SAMPLE);
        check("two labels resolved", jars.size() == 2, jars.keySet().toString());
        check("main classpath, own output first",
                List.of("bazel-out/libreport-class.jar", "bazel-out/one.jar", "bazel-out/two.jar")
                        .equals(jars.get("//jobs/report/src/main:library")),
                String.valueOf(jars.get("//jobs/report/src/main:library")));
        check("test classpath, declared after its action",
                List.of("bazel-out/three.jar")
                        .equals(jars.get("//jobs/report/src/test:library")),
                String.valueOf(jars.get("//jobs/report/src/test:library")));
    }

    private static void aqueryParserHandlesNestedBlocksAndEscapes() {
        String sample = String.join("\n",
                "targets {",
                "id: 7",
                "label: \"//a:b\"",
                "}",
                "actions {",
                "target_id: 7",
                "arguments: \"--classpath\"",
                "arguments: \"path/with \\\"quote\\\".jar\"",
                "execution_info {",
                "  key: \"supports-workers\"",
                "  value: \"1\"",
                "}",
                "arguments: \"path/second.jar\"",
                "}");
        Map<String, List<String>> jars = parse(sample);
        check("nested blocks do not end the action",
                List.of("path/with \"quote\".jar", "path/second.jar").equals(jars.get("//a:b")),
                String.valueOf(jars.get("//a:b")));
    }

    private static void aqueryParserStopsClasspathAtNextFlag() {
        List<String> jars = AqueryParser.classpathJars(List.of(
                "--classpath", "a.jar", "b.jar", "--processorpath", "annotation.jar", "c.txt"));
        check("classpath stops at the next flag",
                List.of("a.jar", "b.jar").equals(jars), jars.toString());
    }

    private static Map<String, List<String>> parse(String textproto) {
        AqueryParser parser = new AqueryParser();
        textproto.lines().forEach(parser::accept);
        parser.finish();
        return parser.jarsByLabel();
    }

    /* -------------------------------------------------------------- grouping */

    private static void groupingMergesMainAndTest() {
        List<BazelQuery.Target> targets = List.of(
                target("//services/ws-bi/src/main:library", "services/ws-bi/src/main",
                        "services/ws-bi/src/main/java"),
                target("//services/ws-bi/src/test:library", "services/ws-bi/src/test",
                        "services/ws-bi/src/test/java"),
                target("//services/ws-bi/src/test:junit", "services/ws-bi/src/test",
                        "services/ws-bi/src/test/java"));

        List<ProjectGrouping.ProjectSpec> specs = ProjectGrouping.group(targets, true, "root");
        check("one project per service", specs.size() == 1, String.valueOf(specs.size()));

        ProjectGrouping.ProjectSpec spec = specs.get(0);
        check("project name", "services.ws-bi".equals(spec.name()), spec.name());
        check("one main and one test label list",
                spec.mainLabels().size() == 1 && spec.testLabels().size() == 2,
                spec.mainLabels() + " / " + spec.testLabels());
        check("two source folders", spec.sourceFolders().size() == 2,
                String.valueOf(spec.sourceFolders()));
        check("test folder marked as test",
                spec.sourceFolders().stream()
                        .anyMatch(folder -> folder.test()
                                && folder.path().equals("services/ws-bi/src/test/java")),
                String.valueOf(spec.sourceFolders()));
    }

    private static void groupingDeduplicatesSharedSourceRoots() {
        List<BazelQuery.Target> targets = List.of(
                target("//platform/util:library", "platform/util", "platform/util/java"),
                target("//platform/util:extra", "platform/util", "platform/util/java"));
        ProjectGrouping.ProjectSpec spec = ProjectGrouping.group(targets, true, "root").get(0);
        check("shared source root linked once", spec.sourceFolders().size() == 1,
                String.valueOf(spec.sourceFolders()));
        check("both labels kept", spec.mainLabels().size() == 2, spec.mainLabels().toString());
    }

    private static void groupingCanBeDisabled() {
        List<BazelQuery.Target> targets = List.of(
                target("//services/ws-bi/src/main:library", "services/ws-bi/src/main",
                        "services/ws-bi/src/main/java"),
                target("//services/ws-bi/src/test:library", "services/ws-bi/src/test",
                        "services/ws-bi/src/test/java"));
        List<ProjectGrouping.ProjectSpec> specs = ProjectGrouping.group(targets, false, "root");
        check("one project per target when grouping is off", specs.size() == 2,
                String.valueOf(specs.size()));
        check("legacy project name",
                specs.stream().anyMatch(spec ->
                        "services.ws-bi.src.main-library".equals(spec.name())),
                specs.toString());
    }

    private static BazelQuery.Target target(String label, String packagePath, String sourceRoot) {
        return new BazelQuery.Target(label, packagePath, sourceRoot, List.of());
    }

    /* -------------------------------------------------------------- settings */

    private static void settingsReadBazelProjectDirectories() throws IOException {
        Path root = Files.createTempDirectory("bazel-settings-test");
        Files.writeString(root.resolve(".bazelproject"), String.join("\n",
                "directories:",
                "  services",
                "  jobs",
                "  -platform/infra",
                "",
                "java_language_level: 17"), StandardCharsets.UTF_8);

        BazelSettings settings = BazelSettings.load(root.toFile());
        check("included directories",
                List.of("//services/...", "//jobs/...").equals(settings.getIncludedPatterns()),
                settings.getIncludedPatterns().toString());
        check("excluded directories",
                List.of("//platform/infra/...").equals(settings.getExcludedPatterns()),
                settings.getExcludedPatterns().toString());
        check("universe expression",
                "(//services/... + //jobs/...) - (//platform/infra/...)".equals(settings.universe()),
                settings.universe());
    }

    private static void settingsUniverseHandlesExclusions() throws IOException {
        Path root = Files.createTempDirectory("bazel-settings-dot");
        Files.writeString(root.resolve(".bazelproject"),
                "directories:\n  .\n", StandardCharsets.UTF_8);
        BazelSettings settings = BazelSettings.load(root.toFile());
        check("a dot directory means the whole repository",
                settings.isWholeRepository() && "//...".equals(settings.universe()),
                settings.universe());
    }

    /* ----------------------------------------------------------------- query */

    private static void queryParsesTargetsAndSkipsSourcelessRules() throws Exception {
        String xml = String.join("\n",
                "<query version=\"2\">",
                "<rule class=\"java_library\" name=\"//services/ws-bi/src/main:library\">",
                "  <list name=\"srcs\">",
                "    <label value=\"//services/ws-bi/src/main:java/ch/A.java\"/>",
                "    <label value=\"//services/ws-bi/src/main:java/ch/B.java\"/>",
                "  </list>",
                "</rule>",
                "<rule class=\"java_binary\" name=\"//services/ws-bi:binary\">",
                "  <list name=\"runtime_deps\">",
                "    <label value=\"//services/ws-bi/src/main:library\"/>",
                "  </list>",
                "</rule>",
                "</query>");
        List<BazelQuery.Target> targets = BazelQuery.parse(xml);
        check("only the rule with sources is imported", targets.size() == 1,
                String.valueOf(targets.size()));
        check("source root derived from srcs",
                "services/ws-bi/src/main/java".equals(targets.get(0).sourceRoot()),
                targets.get(0).sourceRoot());
    }

    private static void querySkipsAmbiguousSourceRoots() {
        check("two different first segments are ambiguous",
                BazelQuery.commonSourceRoot("a/b", List.of("//a/b:java/X.java", "//a/b:kt/Y.java"))
                        == null,
                "expected null");
        check("a source in the package root uses the package",
                "a/b".equals(BazelQuery.commonSourceRoot("a/b", List.of("//a/b:X.java"))),
                String.valueOf(BazelQuery.commonSourceRoot("a/b", List.of("//a/b:X.java"))));
    }

    /* ------------------------------------------------- branch-switch hardening */

    private static void gitStateSeesCheckoutRebaseAndWorktrees() throws IOException {
        Path repo = Files.createTempDirectory("bazel-gitstate");
        check("no .git means no git operation",
                !GitState.operationInProgress(repo.toFile()), "");

        Path gitDir = repo.resolve(".git");
        Files.createDirectories(gitDir);
        check("a quiet repository is not busy",
                !GitState.operationInProgress(repo.toFile()), "");

        Files.writeString(gitDir.resolve("index.lock"), "");
        check("index.lock means a checkout is running",
                GitState.operationInProgress(repo.toFile()), "");
        Files.delete(gitDir.resolve("index.lock"));

        Files.writeString(gitDir.resolve("MERGE_HEAD"), "abc");
        check("MERGE_HEAD means a merge is running",
                GitState.operationInProgress(repo.toFile()), "");
        Files.delete(gitDir.resolve("MERGE_HEAD"));

        Path worktree = Files.createTempDirectory("bazel-gitstate-wt");
        Files.writeString(worktree.resolve(".git"),
                "gitdir: " + gitDir.toAbsolutePath() + "\n");
        check("a worktree resolves its gitdir file",
                gitDir.toFile().equals(GitState.gitDirectory(worktree.toFile())),
                String.valueOf(GitState.gitDirectory(worktree.toFile())));
        Files.writeString(gitDir.resolve("index.lock"), "");
        check("a checkout is visible through the worktree",
                GitState.operationInProgress(worktree.toFile()), "");
    }

    private static void containerStampTracksJarsOrderAndModification() throws Exception {
        Path root = Files.createTempDirectory("bazel-stamp");
        Files.writeString(root.resolve("a.jar"), "aa");
        Files.writeString(root.resolve("b.jar"), "bbbb");

        long stamp = ContainerStamp.of(root.toFile(), List.of("a.jar", "b.jar"), List.of());
        check("same jars, same stamp",
                stamp == ContainerStamp.of(root.toFile(), List.of("a.jar", "b.jar"), List.of()),
                "");
        check("order is part of the identity",
                stamp != ContainerStamp.of(root.toFile(), List.of("b.jar", "a.jar"), List.of()),
                "");
        check("the main/test split is part of the identity",
                stamp != ContainerStamp.of(root.toFile(), List.of("a.jar"), List.of("b.jar")), "");

        long before = ContainerStamp.of(root.toFile(), List.of("a.jar"), List.of());
        root.resolve("a.jar").toFile().setLastModified(
                root.resolve("a.jar").toFile().lastModified() + 5000);
        check("a jar rebuilt in place changes the stamp",
                before != ContainerStamp.of(root.toFile(), List.of("a.jar"), List.of()), "");
    }

    private static void partialAqueryKeepsPopulatedClasspaths() {
        Map<String, List<String>> resolved = new java.util.LinkedHashMap<>();
        resolved.put("//a:lib", List.of());          // was populated -> partial answer, keep old
        resolved.put("//b:lib", List.of());          // was empty -> legitimately empty
        resolved.put("//c:lib", List.of("new.jar")); // real answer

        Map<String, List<String>> previous =
                Map.of("//a:lib", List.of("old.jar"), "//b:lib", List.of());
        int dropped = BazelClasspathCache.dropEmptiesThatWerePopulated(resolved, previous::get);

        check("only the previously populated empty answer is dropped",
                dropped == 1 && !resolved.containsKey("//a:lib"), String.valueOf(resolved));
        check("a label that was always empty stays remembered as empty",
                List.of().equals(resolved.get("//b:lib")), String.valueOf(resolved));
        check("real answers pass through",
                List.of("new.jar").equals(resolved.get("//c:lib")), String.valueOf(resolved));
    }

    private static void failureGateBusyWindowDoesNotEscalate() {
        FailureGate gate = new FailureGate("test", 300);
        gate.recordBusy("terminal build");
        check("busy opens a retry window", gate.shouldSkip(), "");
        check("busy is not a failure", gate.getConsecutiveFailures() == 0,
                String.valueOf(gate.getConsecutiveFailures()));
        check("busy window is short", gate.remainingSeconds() <= 16,
                String.valueOf(gate.remainingSeconds()));
        check("busy state is visible", gate.isBusyWaiting(), gate.describe());
        gate.recordSuccess();
        check("success clears the busy window",
                !gate.shouldSkip() && !gate.isBusyWaiting(), gate.describe());
        gate.recordBusy("again");
        gate.recordFailure("real failure");
        check("a real failure replaces the busy state",
                !gate.isBusyWaiting() && gate.getConsecutiveFailures() == 1, gate.describe());
    }

    /*
        The regression behind these two: the command timeout was only enforced after stdout hit EOF,
        so a bazel client waiting for the server lock - silent on stdout - hung a jdt.ls job thread
        for as long as the terminal build ran, commandLock held.
     */
    private static void watchdogKillsASilentProcess() throws Exception {
        Path root = Files.createTempDirectory("bazel-watchdog");
        Path fake = root.resolve("fake-bazel.sh");
        Files.writeString(fake, "#!/bin/sh\nsleep 30\n");
        fake.toFile().setExecutable(true);

        System.setProperty("bazel.binary", fake.toString());
        try {
            BazelWorkspace workspace = new BazelWorkspace(root.toFile());
            long started = System.currentTimeMillis();
            try {
                workspace.runStreaming(null, line -> { }, 2, "query", "//...");
                check("a silent process must time out", false, "no exception");
            } catch (org.eclipse.core.runtime.CoreException e) {
                long elapsed = System.currentTimeMillis() - started;
                check("killed by the watchdog while stdout was silent",
                        elapsed < 8000 && e.getMessage().contains("timed out after 2 s"),
                        elapsed + " ms, " + e.getMessage());
                check("a timeout is not classified as busy",
                        !BazelWorkspace.isServerBusy(e), e.getMessage());
            }
        } finally {
            System.clearProperty("bazel.binary");
        }
    }

    private static void busyServerIsClassifiedAsBusyNotAsFailure() throws Exception {
        Path root = Files.createTempDirectory("bazel-busy");
        Path fake = root.resolve("fake-bazel.sh");
        // What the real client prints with --noblock_for_lock when the lock is held (bazel 9.2).
        Files.writeString(fake, "#!/bin/sh\n"
                + "echo 'Another command (pid=12345) is running. Exiting immediately.' 1>&2\n"
                + "exit 9\n");
        fake.toFile().setExecutable(true);

        System.setProperty("bazel.binary", fake.toString());
        try {
            BazelWorkspace workspace = new BazelWorkspace(root.toFile());
            check("the busy flag starts clear", !workspace.wasBusyRecently(), "");
            try {
                workspace.run(null, "query", "//...");
                check("exit 9 must not pass as success", false, "no exception");
            } catch (org.eclipse.core.runtime.CoreException e) {
                check("exit 9 with the busy line is classified as busy",
                        BazelWorkspace.isServerBusy(e), e.getMessage());
            }
            check("the workspace remembers the busy server", workspace.wasBusyRecently(), "");
        } finally {
            System.clearProperty("bazel.binary");
        }
    }

    /*
        The regression: the IDE's own background build ran with bazel's default symlink behaviour and
        so created bazel-bin / bazel-out in the repository root - which is the one thing that parks
        the next jdt.ls workspace scan in the output tree. Only build and test take the flag; query
        and aquery do not create symlinks and must not be given a build option.
     */
    private static void ideBuildsRefuseToPlantConvenienceSymlinks() throws Exception {
        Path root = Files.createTempDirectory("bazel-symlink-flag");
        Path fake = root.resolve("fake-bazel.sh");
        Files.writeString(fake, "#!/bin/sh\nfor arg in \"$@\"; do echo \"$arg\"; done\n");
        fake.toFile().setExecutable(true);

        System.setProperty("bazel.binary", fake.toString());
        try {
            BazelWorkspace workspace = new BazelWorkspace(root.toFile());
            String flag = "--experimental_convenience_symlinks=ignore";

            List<String> build = workspace.run(null, "build", "//...");
            check("a build never creates the convenience symlinks", build.contains(flag),
                    build.toString());
            check("the flag follows the command name, not the startup options",
                    build.indexOf("build") < build.indexOf(flag), build.toString());

            List<String> test = workspace.run(null, "test", "//...");
            check("neither does a test run", test.contains(flag), test.toString());

            List<String> query = workspace.run(null, "query", "//...");
            check("query gets no build option", !query.contains(flag), query.toString());

            List<String> aquery = workspace.run(null, "aquery", "mnemonic(Javac, //...)");
            check("nor does aquery", !aquery.contains(flag), aquery.toString());
        } finally {
            System.clearProperty("bazel.binary");
        }
    }

    private static void convenienceSymlinksInTheRootAreReported() throws Exception {
        Path root = Files.createTempDirectory("bazel-symlink-detect");
        BazelWorkspace workspace = new BazelWorkspace(root.toFile());
        check("a clean root reports nothing", workspace.convenienceSymlinks().isEmpty(),
                workspace.convenienceSymlinks().toString());

        Path outputBase = Files.createDirectory(root.resolve("output-base"));
        Files.createSymbolicLink(root.resolve("bazel-out"), outputBase);
        Files.createSymbolicLink(root.resolve("bazel-bin"), outputBase);
        /* The repository this was found on keeps a real bazel-lsp wrapper next to them. */
        Files.writeString(root.resolve("bazel-lsp"), "#!/bin/sh\n");

        List<String> found = workspace.convenienceSymlinks();
        check("both symlinks reported, sorted", List.of("bazel-bin", "bazel-out").equals(found),
                found.toString());
        check("a real file named bazel-* is not a symlink and is left alone",
                !found.contains("bazel-lsp"), found.toString());
    }

    /* ------------------------------------------------------------------ util */

    private static void check(String name, boolean condition, String actual) {
        checks++;
        if (!condition) {
            FAILURES.add(name + " (actual: " + actual + ")");
        }
    }
}
