package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/*
    Settings for one bazel workspace.

    The jdt.ls side cannot read bazelJava.* from the VS Code client: redhat.java only forwards the
    java.* namespace in initializationOptions, and the importer runs before any extension had a
    chance to push settings over executeCommand. So configuration is read straight off disk plus
    system properties, both of which are available at import time.

    Precedence, highest first:
      1. system property  -Dbazel.<key>                              (machine level, vmargs)
      2. environment      BAZEL_JAVA_<KEY>                           (machine level)
      3. ~/.cache/bazel-java-jdtls/settings-<hash>.json              (written by the extension
                                                                      from the bazelJava.* settings)
      4. <root>/.vscode/bazel-java.json                              (repo level, hand written)
      5. <root>/.bazelproject  directories:                          (repo level, scope only)
      6. defaults

    binary and outputBase are the exception: they name something that gets executed, so they are
    read from 1-3 only and ignored in the repo-level file. See readJson.
 */
public final class BazelSettings {

    public static final String BUILD_ON_IMPORT_OFF = "off";
    public static final String BUILD_ON_IMPORT_BACKGROUND = "background";

    public static final String IMPORT_MODE_EAGER = "eager";
    public static final String IMPORT_MODE_LAZY = "lazy";

    /* Keys that decide what gets executed, and are therefore never read from inside the repository. */
    private static final List<String> MACHINE_LEVEL_KEYS = List.of("binary", "outputBase");

    private final File root;

    private String binary = "";
    private List<String> includedPatterns = List.of();
    private List<String> excludedPatterns = List.of();
    private boolean useBazelProject = true;
    private String importMode = IMPORT_MODE_LAZY;
    private int maxProjects = 300;
    private String outputBase = "";
    private int maxIdleSeconds = 900;
    private int commandTimeoutSeconds = 120;
    private boolean discoveryNoFetch = true;
    private boolean noblockForLock = true;
    private int backoffMaxSeconds = 300;
    private boolean groupSourceRoots = true;
    private String buildOnImport = BUILD_ON_IMPORT_BACKGROUND;

    private BazelSettings(File root) {
        this.root = root;
    }

    public static BazelSettings load(File root) {
        BazelSettings settings = new BazelSettings(root);
        settings.readJson(new File(root, ".vscode/bazel-java.json"), false);
        settings.readJson(userSettingsFile(root), true);
        if (settings.useBazelProject && settings.includedPatterns.isEmpty()) {
            settings.readBazelProject(new File(root, ".bazelproject"));
        }
        settings.readOverrides();
        return settings;
    }

    /*
        Where the VS Code extension mirrors the bazelJava.* settings for this workspace. It lives
        outside the repository on purpose: the extension must not write into someone's working copy,
        and machine-specific values like a bazel binary path do not belong in a shared file.
     */
    public static File userSettingsFile(File root) {
        return new File(System.getProperty("user.home"),
                ".cache/bazel-java-jdtls/settings-" + Digests.shortHash(root.getAbsolutePath())
                        + ".json");
    }

    public File getRoot() {
        return root;
    }

    public String getBinary() {
        return binary;
    }

    public String getImportMode() {
        return importMode;
    }

    public boolean isLazyImport() {
        return IMPORT_MODE_LAZY.equals(importMode);
    }

    public int getMaxProjects() {
        return maxProjects;
    }

    public String getOutputBase() {
        return outputBase;
    }

    public boolean hasDedicatedOutputBase() {
        return !outputBase.isBlank();
    }

    public int getMaxIdleSeconds() {
        return maxIdleSeconds;
    }

    public int getCommandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    public boolean isDiscoveryNoFetch() {
        return discoveryNoFetch;
    }

    /*
        Fail fast (exit 9) when another bazel command holds the server lock instead of queueing
        behind it. On a shared output base "another command" is the developer's own terminal build,
        which right after a branch switch runs for minutes - exactly when the refresh fires.
     */
    public boolean isNoblockForLock() {
        return noblockForLock;
    }

    public int getBackoffMaxSeconds() {
        return backoffMaxSeconds;
    }

    public boolean isGroupSourceRoots() {
        return groupSourceRoots;
    }

    /*
        aquery reports the jars a Javac action would consume; it does not build them. A jar that was
        produced before the last change to its inputs is still on disk and JDT indexes it happily,
        so the IDE shows a stale API - a method added last week is "undefined" while bazel builds
        fine. That fails silently and is worse than a missing jar, so the imported targets are built
        once per session in the background by default.
     */
    public boolean isBuildOnImport() {
        return BUILD_ON_IMPORT_BACKGROUND.equals(buildOnImport);
    }

    public List<String> getIncludedPatterns() {
        return includedPatterns;
    }

    public List<String> getExcludedPatterns() {
        return excludedPatterns;
    }

    public boolean isWholeRepository() {
        return includedPatterns.isEmpty() && excludedPatterns.isEmpty();
    }

    /*
        The universe expression handed to bazel query. Empty configuration means the whole
        repository, which is what the plugin did before scoping existed.
     */
    public String universe() {
        if (includedPatterns.isEmpty() && excludedPatterns.isEmpty()) {
            return "//...";
        }
        String included = includedPatterns.isEmpty()
                ? "//..."
                : String.join(" + ", includedPatterns);
        if (excludedPatterns.isEmpty()) {
            return included;
        }
        return "(" + included + ") - (" + String.join(" + ", excludedPatterns) + ")";
    }

    /*
        Every input that changes what an import would produce. Part of the cache stamp so that
        flipping a setting invalidates the persisted classpath instead of silently reusing it.
     */
    public String fingerprint() {
        return String.join(" ",
                binary, String.join(",", includedPatterns), String.join(",", excludedPatterns),
                importMode, outputBase, String.valueOf(groupSourceRoots));
    }

    /*
        machineLevel says whether this file is trusted with the keys that decide what gets executed.
        binary is handed straight to ProcessBuilder and outputBase becomes a --output_base startup
        option, so neither may come from a file inside the repository: cloning someone's repository
        and opening it would then run whatever binary that repository names. They are accepted only
        from the mirror the extension writes outside the working copy, whose source is a
        machine-scoped VS Code setting, and from the system property / environment overrides. The
        rest of the keys only narrow or slow the import down and are safe to share through the
        repository.
     */
    private void readJson(File file, boolean machineLevel) {
        if (!file.isFile()) {
            return;
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            BazelLog.warnOnce("settings:" + file, "Bazel: cannot read " + file + ": " + e);
            return;
        }
        if (machineLevel) {
            binary = string(json, "binary", binary);
            outputBase = string(json, "outputBase", outputBase);
        } else {
            for (String key : MACHINE_LEVEL_KEYS) {
                if (json.has(key)) {
                    BazelLog.warnOnce("settings-ignored:" + file + ":" + key, String.format(
                            "Bazel: ignoring '%s' from %s - it names something to execute, so it is"
                                    + " only read from VS Code settings, not from the repository.",
                            key, file));
                }
            }
        }
        includedPatterns = strings(json, "targets", includedPatterns);
        excludedPatterns = strings(json, "excludeTargets", excludedPatterns);
        useBazelProject = bool(json, "useBazelProject", useBazelProject);
        importMode = string(json, "importMode", importMode);
        maxProjects = integer(json, "maxProjects", maxProjects);
        maxIdleSeconds = integer(json, "maxIdleSeconds", maxIdleSeconds);
        commandTimeoutSeconds = integer(json, "commandTimeoutSeconds", commandTimeoutSeconds);
        discoveryNoFetch = bool(json, "discoveryNoFetch", discoveryNoFetch);
        noblockForLock = bool(json, "noblockForLock", noblockForLock);
        backoffMaxSeconds = integer(json, "backoffMaxSeconds", backoffMaxSeconds);
        groupSourceRoots = bool(json, "groupSourceRoots", groupSourceRoots);
        buildOnImport = string(json, "buildOnImport", buildOnImport);
    }

    /*
        .bazelproject is the IntelliJ Bazel plugin's project view file. Only the directories: block
        is meaningful here; entries prefixed with - are exclusions.
     */
    private void readBazelProject(File file) {
        if (!file.isFile()) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            BazelLog.warnOnce("bazelproject:" + file, "Bazel: cannot read " + file + ": " + e);
            return;
        }

        Set<String> included = new LinkedHashSet<>();
        Set<String> excluded = new LinkedHashSet<>();
        boolean inDirectories = false;
        for (String line : lines) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            boolean indented = Character.isWhitespace(line.charAt(0));
            if (!indented) {
                inDirectories = line.strip().startsWith("directories:");
                continue;
            }
            if (!inDirectories) {
                continue;
            }
            String entry = line.strip();
            boolean exclusion = entry.startsWith("-");
            if (exclusion) {
                entry = entry.substring(1).strip();
            }
            String pattern = toTargetPattern(entry);
            if (pattern == null) {
                continue;
            }
            (exclusion ? excluded : included).add(pattern);
        }

        // "directories: ." means the whole repository, which is the default already.
        if (included.size() == 1 && included.contains("//...")) {
            included.clear();
        }
        includedPatterns = List.copyOf(included);
        excludedPatterns = List.copyOf(excluded);
    }

    static String toTargetPattern(String directory) {
        String cleaned = directory.replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty() || ".".equals(cleaned)) {
            return "//...";
        }
        return "//" + cleaned + "/...";
    }

    private void readOverrides() {
        binary = override("binary", binary);
        importMode = override("importMode", importMode);
        outputBase = override("outputBase", outputBase);
        maxProjects = integer(override("maxProjects", null), maxProjects);
        maxIdleSeconds = integer(override("maxIdleSeconds", null), maxIdleSeconds);
        commandTimeoutSeconds = integer(override("commandTimeoutSeconds", null),
                commandTimeoutSeconds);
        backoffMaxSeconds = integer(override("backoffMaxSeconds", null), backoffMaxSeconds);
        discoveryNoFetch = bool(override("discoveryNoFetch", null), discoveryNoFetch);
        noblockForLock = bool(override("noblockForLock", null), noblockForLock);
        groupSourceRoots = bool(override("groupSourceRoots", null), groupSourceRoots);
        buildOnImport = override("buildOnImport", buildOnImport);

        String targets = override("targets", null);
        if (targets != null && !targets.isBlank()) {
            List<String> parsed = new ArrayList<>();
            for (String part : targets.split(",")) {
                if (!part.isBlank()) {
                    parsed.add(part.strip());
                }
            }
            includedPatterns = List.copyOf(parsed);
        }
    }

    private static String override(String key, String fallback) {
        String property = System.getProperty("bazel." + key);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String environment = System.getenv("BAZEL_JAVA_" + camelToUpper(key));
        if (environment != null && !environment.isBlank()) {
            return environment;
        }
        return fallback;
    }

    static String camelToUpper(String key) {
        StringBuilder out = new StringBuilder(key.length() + 4);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                out.append('_');
            }
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
    }

    private static int integer(JsonObject json, String key, int fallback) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsInt();
    }

    private static List<String> strings(JsonObject json, String key, List<String> fallback) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonArray()) {
            return fallback;
        }
        List<String> values = new ArrayList<>();
        element.getAsJsonArray().forEach(item -> {
            if (!item.isJsonNull() && !item.getAsString().isBlank()) {
                values.add(item.getAsString().strip());
            }
        });
        return List.copyOf(values);
    }

    private static int integer(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.strip());
    }
}
