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
        theSymlinkFlagIsPassedOnlyForADedicatedOutputBase();
        convenienceSymlinksInTheRootAreReported();
        exclusionPatternsFenceOffTheOutputTree();
        theBazelrcSymlinkPrefixJoinsTheStandingExclusion();
        bazelFailuresKeepTheirCauseAndAreClassified();
        theGateSaysWhenAFailureNeedsAHuman();
        stampCoversSourceAttachmentsAndLombok();
        sourceLabelsAreFilteredOutOfTheRepositoryListing();
        doctorReadsTheBazelrcAndFindsHeavyDirectories();
        doctorSpotsATruncatedJdtIndex();
        settingsReadBuildJobsAndMavenRepository();

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
        The flag policy, reversed in 0.6.0. It used to go on every build and test the IDE ran, on the
        theory that the convenience symlinks are a hazard - but they are what the rest of a monorepo
        reads generated output through, and on the shared output base an IDE build writes exactly the
        paths a terminal build would. What is left is the case where the IDE owns a separate output
        base: measured on bazel 9.2.0, a build repoints every symlink at the base it ran in, so
        without the flag bazel-bin would end up aimed at ~/.cache/bazel-ide.
     */
    private static void theSymlinkFlagIsPassedOnlyForADedicatedOutputBase() throws Exception {
        Path root = Files.createTempDirectory("bazel-symlink-flag");
        Path fake = root.resolve("fake-bazel.sh");
        Files.writeString(fake, "#!/bin/sh\nfor arg in \"$@\"; do echo \"$arg\"; done\n");
        fake.toFile().setExecutable(true);

        String flag = "--experimental_convenience_symlinks=ignore";
        System.setProperty("bazel.binary", fake.toString());
        try {
            BazelWorkspace shared = new BazelWorkspace(root.toFile());
            List<String> build = shared.run(null, "build", "//...");
            check("on the shared output base a build adds no symlink flag", !build.contains(flag),
                    build.toString());
            List<String> test = shared.run(null, "test", "//...");
            check("nor does a test run", !test.contains(flag), test.toString());

            System.setProperty("bazel.outputBase", root.resolve("ide-base").toString());
            BazelWorkspace dedicated = new BazelWorkspace(root.toFile());
            List<String> ideBuild = dedicated.run(null, "build", "//...");
            check("with an IDE-owned output base the flag is back", ideBuild.contains(flag),
                    ideBuild.toString());
            check("and it follows the command name, not the startup options",
                    ideBuild.indexOf("build") < ideBuild.indexOf(flag), ideBuild.toString());

            List<String> query = dedicated.run(null, "query", "//...");
            check("query gets no build option", !query.contains(flag), query.toString());
            List<String> aquery = dedicated.run(null, "aquery", "mnemonic(Javac, //...)");
            check("nor does aquery", !aquery.contains(flag), aquery.toString());
        } finally {
            System.clearProperty("bazel.binary");
            System.clearProperty("bazel.outputBase");
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
        /*
            --symlink_prefix renames all of them, so the name cannot be the test. What is stable is
            where the link lands: inside the output base, which always has execroot in it.
         */
        Path execroot = Files.createDirectories(root.resolve("elsewhere/execroot/_main"));
        Files.createSymbolicLink(root.resolve("out-main"), execroot);
        /* An unrelated symlink to a directory of the developer's own must be left alone. */
        Files.createSymbolicLink(root.resolve("data"),
                Files.createDirectory(root.resolve("real-data")));

        List<String> found = workspace.convenienceSymlinks();
        check("both bazel-prefixed symlinks reported, sorted",
                found.indexOf("bazel-bin") == 0 && found.indexOf("bazel-out") == 1,
                found.toString());
        check("a symlink into the output tree counts whatever it is called",
                found.contains("out-main"), found.toString());
        check("a real file named bazel-* is not a symlink and is left alone",
                !found.contains("bazel-lsp"), found.toString());
        check("an unrelated symlink is not ours to exclude", !found.contains("data"),
                found.toString());
    }

    /*
        The patterns that replace the retracted "delete the symlinks" advice. jdt.ls looks for build
        files with Files.walkFileTree(root, FOLLOW_LINKS, ...) and skips a directory whose full path
        matches one of java.import.exclusions, so the scan can be fenced off while the symlinks stay
        exactly where bazel and the rest of the repository's tooling expect them.
     */
    private static void exclusionPatternsFenceOffTheOutputTree() throws Exception {
        Path root = Files.createTempDirectory("bazel-exclusions");
        Path outputBase = Files.createDirectory(root.resolve("output-base"));
        String base = root.toFile().getAbsolutePath();

        List<String> patterns = ImportExclusions.patterns(root.toFile(),
                List.of("bazel-out", "out-main"), outputBase.toFile(), List.of());
        check("the standing pair covers symlinks created later",
                patterns.contains(base + "/bazel-*") && patterns.contains(base + "/bazel-*/**"),
                patterns.toString());
        check("each symlink found is fenced off by its own path",
                patterns.contains(base + "/out-main/**"), patterns.toString());
        check("and so is the output base itself",
                patterns.contains(outputBase.toFile().getAbsolutePath() + "/**"),
                patterns.toString());

        List<String> existing = new ArrayList<>(List.of("**/node_modules/**"));
        List<String> merged = ImportExclusions.merge(existing, patterns);
        check("merging keeps what jdt.ls already excludes",
                merged != null && merged.get(0).equals("**/node_modules/**"), String.valueOf(merged));
        check("and adds every pattern", merged.containsAll(patterns), String.valueOf(merged));
        check("a second pass has nothing to add and writes nothing",
                ImportExclusions.merge(merged, patterns) == null, "");
        check("missing() is empty once the scan is fenced off",
                ImportExclusions.missing(merged, patterns).isEmpty(),
                ImportExclusions.missing(merged, patterns).toString());
        check("and names exactly what is absent otherwise",
                ImportExclusions.missing(List.of(), patterns).size() == patterns.size(), "");
    }

    /*
        The gap the standing <root>/bazel-* pair leaves. A symlink is recognised by where it points,
        which needs it to exist - so one that a terminal build creates *after* the last import
        attempt is covered by name or not at all, and --symlink_prefix renames every one of them.
        Bazel's rc files say what that prefix is, so the standing pair follows it.

        The rejected values matter more than the accepted one: an empty prefix would turn into
        <root>/*, which takes the whole repository out of the build-file scan and leaves the importer
        with nothing to find.
     */
    private static void theBazelrcSymlinkPrefixJoinsTheStandingExclusion() throws Exception {
        Path root = Files.createTempDirectory("bazel-symlink-prefix");
        Files.writeString(root.resolve(".bazelrc"), String.join("\n",
                "# build --symlink_prefix=commented-out",
                "build --symlink_prefix=out-",
                "common --symlink_prefix \"quoted-\"",
                "build --symlink_prefix=.bazel/",
                "build --jobs=4") + "\n");

        List<String> prefixes = BazelRc.symlinkPrefixes(root.toFile());
        check("the prefix is read off the bazelrc", prefixes.contains("out-"), prefixes.toString());
        check("the space-separated form counts too, unquoted",
                prefixes.contains("quoted-"), prefixes.toString());
        check("a commented-out line does not",
                !prefixes.contains("commented-out"), prefixes.toString());
        check("a prefix that nests the symlinks is kept as written",
                prefixes.contains(".bazel/"), prefixes.toString());

        check("an empty prefix is refused - it would exclude the repository",
                BazelRc.symlinkPrefixes("build --symlink_prefix=").isEmpty(), "");
        check("so is bazel's spelling of 'create no symlinks'",
                BazelRc.symlinkPrefixes("build --symlink_prefix=/").isEmpty(), "");
        check("and so is a prefix that is itself a glob",
                BazelRc.symlinkPrefixes("build --symlink_prefix=o*t").isEmpty(), "");
        check("a bazelrc without the option leaves the default alone",
                BazelRc.symlinkPrefixes("build --jobs=4\n").isEmpty(), "");

        String base = root.toFile().getAbsolutePath();
        List<String> patterns = ImportExclusions.patterns(root.toFile(), List.of(), null,
                BazelRc.symlinkPrefixes(root.toFile()));
        check("the renamed symlinks get a standing pair of their own",
                patterns.contains(base + "/out-*") && patterns.contains(base + "/out-*/**"),
                patterns.toString());
        check("nested ones are fenced off one level down, where a glob star stops",
                patterns.contains(base + "/.bazel/*") && patterns.contains(base + "/.bazel/*/**"),
                patterns.toString());
        check("bazel's own prefix is covered whatever the rc file says",
                patterns.contains(base + "/bazel-*"), patterns.toString());
    }

    /*
        The report used to end where the answer began: only lines starting with ERROR were captured,
        so "An error occurred during the fetch of repository 'maven_nullaway':" was logged without
        the traceback that follows it - and the traceback is where bazel prints the command that
        fixes it. A fetch failure is also classified apart from a generic one, because no amount of
        retrying clears it.
     */
    private static void bazelFailuresKeepTheirCauseAndAreClassified() throws Exception {
        Path root = Files.createTempDirectory("bazel-error-cause");
        Path fake = root.resolve("fake-bazel.sh");
        Files.writeString(fake, "#!/bin/sh\n"
                + "echo \"ERROR: /x/coursier.bzl:678:21: An error occurred during the fetch of"
                + " repository 'maven_nullaway':\" >&2\n"
                + "echo \"   Traceback (most recent call last):\" >&2\n"
                + "echo \"Error in fail: maven_nullaway_install.json contains an invalid input"
                + " signature and must be regenerated. please run: REPIN=1 bazel run"
                + " @maven_nullaway//:pin\" >&2\n"
                + "exit 1\n");
        fake.toFile().setExecutable(true);

        System.setProperty("bazel.binary", fake.toString());
        try {
            BazelWorkspace workspace = new BazelWorkspace(root.toFile());
            String message = "";
            boolean blocked = false;
            try {
                workspace.run(null, "aquery", "mnemonic(Javac, //...)");
            } catch (org.eclipse.core.runtime.CoreException e) {
                message = String.valueOf(e.getMessage());
                blocked = BazelWorkspace.isFetchBlocked(e);
            }
            check("the failure message carries the remedy bazel printed",
                    message.contains("REPIN=1 bazel run @maven_nullaway//:pin"), message);
            check("and the traceback line in between", message.contains("Traceback"), message);
            check("an unfetchable repository is classified as needing a fix", blocked, message);
        } finally {
            System.clearProperty("bazel.binary");
        }

        /*
            The output a command produced before it failed is the point. bazel run with --keep_going
            analyses what it can, prints those actions, and exits non-zero for the rest; measured on
            a repository with one unfetchable external: ten of eleven actions on stdout, exit 1. If
            the sink does not see them, a single broken repository empties every classpath.
         */
        Path streaming = Files.createTempDirectory("bazel-partial");
        Path partial = streaming.resolve("fake-bazel.sh");
        Files.writeString(partial, "#!/bin/sh\n"
                + "echo 'targets {'\n"
                + "echo '  id: 1'\n"
                + "echo \"ERROR: An error occurred during the fetch of repository 'maven':\" >&2\n"
                + "exit 1\n");
        partial.toFile().setExecutable(true);
        System.setProperty("bazel.binary", partial.toString());
        try {
            BazelWorkspace workspace = new BazelWorkspace(streaming.toFile());
            List<String> seen = new ArrayList<>();
            boolean threw = false;
            try {
                workspace.runStreaming(null, seen::add, "aquery", "mnemonic(Javac, //...)");
            } catch (org.eclipse.core.runtime.CoreException e) {
                threw = true;
            }
            check("a failing command still reports its failure", threw, "");
            check("but what it printed before failing reaches the sink", seen.size() == 2,
                    seen.toString());
        } finally {
            System.clearProperty("bazel.binary");
        }

        /*
            The remedy is the last thing bazel prints, so a detail longer than the cap keeps its tail.
         */
        String long1 = "ERROR: " + "x".repeat(2000) + " please run: REPIN=1 bazel run @maven//:pin";
        String elided = BazelWorkspace.failureDetail(List.of(long1));
        check("a long failure keeps the command at its end",
                elided.contains("REPIN=1 bazel run @maven//:pin"), elided);
        check("and still starts where bazel started", elided.startsWith("ERROR: xxx"), elided);

        /*
            "3 label(s) could not be analysed" reads as a rounding error until one of the three is the
            service open in the editor. They are named.
         */
        check("the labels without a classpath are named",
                BazelClasspathCache.describe(List.of("//a:x", "//b:y"))
                        .equals("//a:x, //b:y"),
                BazelClasspathCache.describe(List.of("//a:x", "//b:y")));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add("//pkg" + i + ":library");
        }
        check("and a long list keeps a count instead of a wall of text",
                BazelClasspathCache.describe(many).endsWith("and 4 more"),
                BazelClasspathCache.describe(many));

        /* bazel's sign-off repeats the exit code and must not become the "last" error. */
        String detail = BazelWorkspace.failureDetail(List.of(
                "ERROR: /x/coursier.bzl:678: fetch of repository 'maven_nullaway' failed",
                "ERROR: Build did NOT complete successfully"));
        check("the summary line is not the one reported",
                !detail.contains("did NOT complete"), detail);

        check("a plain build failure is not classified as a fetch problem",
                !BazelWorkspace.isFetchFailure(List.of("ERROR: BUILD:3:1 syntax error")), "");
        check("the fetch markers are bazel's own wording",
                BazelWorkspace.isFetchFailure(
                        List.of("ERROR: An error occurred during the fetch of repository 'maven':")),
                "");
    }

    /*
        A stale lock file is not a transient failure, and "7 consecutive failure(s), retry in 52 s"
        is the wrong thing to show for it: nothing happens until a human repins.
     */
    private static void theGateSaysWhenAFailureNeedsAHuman() throws Exception {
        FailureGate gate = new FailureGate("classpath", 300);
        gate.recordFailure("bazel aquery failed with exit code 1: ERROR: An error occurred during"
                + " the fetch of repository 'maven_nullaway': Error in fail: ... REPIN=1 bazel run"
                + " @maven_nullaway//:pin");
        check("the gate knows this one waits on a person", gate.needsAFix(), gate.describe());
        check("and says so instead of counting failures",
                gate.describe().contains("needs a fix"), gate.describe());

        FailureGate other = new FailureGate("discovery", 300);
        other.recordFailure("bazel query failed with exit code 1: ERROR: BUILD:3:1 syntax error");
        check("an ordinary failure still reads as a backoff", !other.needsAFix(), other.describe());
        check("with the failure counter in the text",
                other.describe().contains("consecutive failure(s)"), other.describe());

        other.recordSuccess();
        check("and recovery clears everything", other.describe().equals("ok"), other.describe());
    }

    /*
        The regression this prevents: fetching source jars changes nothing about the classpath - same
        jars, same order, same mtimes - so a stamp that ignored source attachments reported "nothing
        changed", the containers were not republished, and the freshly downloaded sources stayed
        invisible until the window was reloaded.
     */
    private static void stampCoversSourceAttachmentsAndLombok() throws Exception {
        Path root = Files.createTempDirectory("bazel-stamp-sources");
        Files.writeString(root.resolve("guava.jar"), "aa");

        long before = ContainerStamp.of(root.toFile(), List.of("guava.jar"), List.of());
        Files.writeString(root.resolve("guava-sources.jar"), "src");
        long after = ContainerStamp.of(root.toFile(), List.of("guava.jar"), List.of());
        check("a source jar appearing next to a jar changes the stamp", before != after, "");

        Files.writeString(root.resolve("guava-sources.jar"), "more source");
        check("and so does the source jar changing",
                after != ContainerStamp.of(root.toFile(), List.of("guava.jar"), List.of()), "");

        /* The stamp has to describe the file that goes on the classpath, lombok substitution and all. */
        Files.writeString(root.resolve("header_lombok-1.18.30.jar"), "stub");
        long headerOnly = ContainerStamp.of(root.toFile(),
                List.of("header_lombok-1.18.30.jar"), List.of());
        Files.writeString(root.resolve("lombok-1.18.30.jar"), "the real thing");
        check("the full lombok jar appearing changes the stamp of the header entry",
                headerOnly != ContainerStamp.of(root.toFile(),
                        List.of("header_lombok-1.18.30.jar"), List.of()), "");
        check("and that entry resolves to the full jar",
                "lombok-1.18.30.jar".equals(BazelClasspathContainer
                        .jarFile(root.toFile(), "header_lombok-1.18.30.jar").getName()),
                BazelClasspathContainer.jarFile(root.toFile(), "header_lombok-1.18.30.jar")
                        .getName());
    }

    private static void sourceLabelsAreFilteredOutOfTheRepositoryListing() {
        List<String> output = List.of(
                "@maven//:com_google_guava_guava",
                "@maven//:com_google_guava_guava_sources",
                "@maven//:org_postgresql_postgresql_sources",
                "@maven//:com_google_guava_guava_sources",
                "Loading: 0 packages loaded",
                "WARNING: some package failed to load",
                "");

        List<String> labels = FetchSourcesJob.sourceLabels(output);
        check("only the sources labels are kept, deduplicated",
                List.of("@maven//:com_google_guava_guava_sources",
                        "@maven//:org_postgresql_postgresql_sources").equals(labels),
                labels.toString());
    }

    private static void doctorReadsTheBazelrcAndFindsHeavyDirectories() throws Exception {
        Path root = Files.createTempDirectory("bazel-doctor");
        List<String> facts = new java.util.ArrayList<>();
        check("no bazelrc anywhere is not reported as a problem",
                Doctor.bazelrcProblems(root.toFile(), facts).isEmpty(), "");
        check("but the report says which files were read",
                facts.toString().contains("none found"), facts.toString());

        Files.writeString(root.resolve(".bazelrc"), "build --jobs=8\n");
        facts.clear();
        List<String> problems = Doctor.bazelrcProblems(root.toFile(), facts);
        /*
            0.6.0 retracted the demand for --experimental_convenience_symlinks=ignore: the symlinks
            are load-bearing for everything in the repository that is not java, and the scan they
            used to break is fenced off with java.import.exclusions instead. A bazelrc without that
            line must therefore report nothing.
         */
        check("a bazelrc is no longer faulted for keeping the convenience symlinks",
                problems.isEmpty(), problems.toString());
        check("and --jobs being present is not suggested again",
                facts.stream().noneMatch(fact -> fact.contains("--jobs below")), facts.toString());

        /* Also picks up the aspect layer, which is where this repository keeps its personal rc. */
        Files.createDirectories(root.resolve(".aspect/bazelrc"));
        Files.writeString(root.resolve(".aspect/bazelrc/user.bazelrc"),
                "common --disk_cache=~/.cache/bazel-disk\nstartup --max_idle_secs=600\n");
        facts.clear();
        Doctor.bazelrcProblems(root.toFile(), facts);
        check("the disk cache suggestion is silenced by the aspect layer",
                facts.stream().noneMatch(fact -> fact.contains("disk_cache=~/.cache/bazel-disk with")),
                facts.toString());
        check("and that file is listed as read",
                facts.toString().contains("user.bazelrc"), facts.toString());

        Path modules = Files.createDirectories(root.resolve("web/node_modules"));
        for (int i = 0; i < 6; i++) {
            Files.writeString(modules.resolve("file" + i + ".js"), "x");
        }
        /*
            A source tree of the very same size must not be reported. Telling someone their
            services/ directory is too big is advice they cannot act on, and it was the first thing
            this check got wrong.
         */
        Path services = Files.createDirectories(root.resolve("services/ws-crm/src/main/java"));
        for (int i = 0; i < 6; i++) {
            Files.writeString(services.resolve("Type" + i + ".java"), "class X {}");
        }

        List<Path> heavy = Doctor.heavyDirectories(root, 5);
        check("the vendor directory is found", heavy.size() == 1, heavy.toString());
        check("and it is the vendor one, not the source tree",
                heavy.get(0).endsWith("node_modules"), heavy.toString());

        /* A symlink is never descended into: doing so is the mistake the whole report warns about. */
        Files.createSymbolicLink(root.resolve("bazel-out"), modules);
        check("symlinked trees are left to the symlink check",
                Doctor.heavyDirectories(root, 5).size() == 1,
                Doctor.heavyDirectories(root, 5).toString());
    }

    /*
        The OOM that reads like a bug in the extension. A language server killed while saving an
        index leaves the file half written; JDT later reads a length field out of it, gets a garbage
        number - measured: "size 1936028278" - allocates that much and dies with OutOfMemoryError,
        on a 16 GB heap, repeatedly. Nothing about the repository is wrong and no setting helps, so
        the report has to name the cache and say it can be deleted.
     */
    private static void doctorSpotsATruncatedJdtIndex() throws Exception {
        Path metadata = Files.createTempDirectory("jdt-metadata");
        Path indexes = Files.createDirectories(metadata.resolve(".plugins/org.eclipse.jdt.core"));
        Files.writeString(indexes.resolve("1865797976.index"), "x".repeat(2048));
        Files.writeString(metadata.resolve(".log"), "!MESSAGE Workspace initialized in 182ms\n");

        List<String> facts = new ArrayList<>();
        check("a healthy index cache is a fact, not a problem",
                Doctor.indexProblems(metadata, facts).isEmpty(), facts.toString());
        check("and its size is reported",
                facts.stream().anyMatch(fact -> fact.startsWith("jdt index")), facts.toString());

        Files.writeString(indexes.resolve("438257673.index.tmp"), "");
        facts.clear();
        List<String> interrupted = Doctor.indexProblems(metadata, facts);
        check("a half-written index file is reported", interrupted.size() == 1,
                interrupted.toString());
        check("and the remedy names the directory to delete",
                interrupted.get(0).contains("org.eclipse.jdt.core"), interrupted.toString());

        Files.delete(indexes.resolve("438257673.index.tmp"));
        Files.writeString(metadata.resolve(".log"),
                "java.io.UTFDataFormatException: Failed to read index data from file:/x.index"
                        + " at offset 8600 and size 1936028278\n");
        facts.clear();
        check("so is the failure once it has already happened",
                Doctor.indexProblems(metadata, facts).size() == 1, "");

        facts.clear();
        check("a workspace with no metadata directory says nothing",
                Doctor.indexProblems(metadata.resolve("missing"), facts).isEmpty(), "");
        check("nor does a null location", Doctor.indexProblems(null, facts).isEmpty(), "");
    }

    private static void settingsReadBuildJobsAndMavenRepository() throws Exception {
        Path root = Files.createTempDirectory("bazel-jobs");
        Files.createDirectories(root.resolve(".vscode"));

        BazelSettings defaults = BazelSettings.load(root.toFile());
        check("no --jobs argument unless asked for", defaults.buildJobsArgument().isEmpty(), "");
        check("the maven repository defaults to the conventional name",
                "maven".equals(defaults.getMavenRepository()), defaults.getMavenRepository());

        Files.writeString(root.resolve(".vscode/bazel-java.json"),
                "{\"buildJobs\": 4, \"mavenRepository\": \"maven_install\"}");
        BazelSettings configured = BazelSettings.load(root.toFile());
        check("buildJobs becomes a --jobs argument",
                configured.buildJobsArgument().orElse("").equals("--jobs=4"),
                configured.buildJobsArgument().toString());
        check("the maven repository is read from the repository file",
                "maven_install".equals(configured.getMavenRepository()),
                configured.getMavenRepository());
    }

    /* ------------------------------------------------------------------ util */

    private static void check(String name, boolean condition, String actual) {
        checks++;
        if (!condition) {
            FAILURES.add(name + " (actual: " + actual + ")");
        }
    }
}
