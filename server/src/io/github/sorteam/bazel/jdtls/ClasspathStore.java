package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.ResourcesPlugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/*
    Persistent cache of everything an import produces, so a restart costs no bazel calls at all.

    Without it every VS Code restart replays the whole thing: jdt.ls restores the projects it wrote
    last time, JDT initialises a classpath container for each, and each container runs its own
    aquery. On a 900-package monorepo that was ~40 s of bazel before the importer had even started.

    The cache is read optimistically - initialize() hands out whatever is on disk immediately - and
    validated afterwards on a background job. A stale entry costs one refresh; a blocking validation
    would cost the startup latency this whole change exists to remove.
 */
public final class ClasspathStore {

    private static final int FORMAT_VERSION = 7;
    private static final Map<String, ClasspathStore> STORES = new ConcurrentHashMap<>();

    private final File root;
    private final Path file;

    private final Map<String, List<String>> jarsByLabel = new LinkedHashMap<>();
    private final List<BazelQuery.Target> discovery = new ArrayList<>();
    private final Map<String, List<SourceRelocation.Misplaced>> misplaced = new LinkedHashMap<>();

    private String executionRoot = "";
    private String settingsFingerprint = "";
    private String buildFilesDigest = "";
    private boolean loaded;
    private boolean dirty;

    private ClasspathStore(File root) {
        this.root = root;
        this.file = stateDirectory().resolve(Digests.shortHash(root.getAbsolutePath()) + ".json");
    }

    public static ClasspathStore get(File root) {
        return STORES.computeIfAbsent(root.getAbsolutePath(),
                path -> new ClasspathStore(new File(path)));
    }

    private static Path stateDirectory() {
        Path workspace = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
        return workspace.resolve(".metadata/.plugins/" + BazelClasspathContainerInitializer.PLUGIN_ID);
    }

    public synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.get("version") == null || json.get("version").getAsInt() != FORMAT_VERSION) {
                return;
            }
            executionRoot = optionalString(json, "executionRoot");
            settingsFingerprint = optionalString(json, "settings");
            buildFilesDigest = optionalString(json, "buildFiles");
            readDiscovery(json.getAsJsonArray("discovery"));
            readMisplaced(json.getAsJsonObject("misplaced"));
            readClasspath(json.getAsJsonObject("classpath"));
            BazelLog.info(String.format(
                    "Bazel: loaded cached import for %s (%d targets, %d classpaths)",
                    root.getName(), discovery.size(), jarsByLabel.size()));
        } catch (IOException | RuntimeException e) {
            BazelLog.info("Bazel: ignoring unreadable classpath cache " + file + ": " + e);
            jarsByLabel.clear();
            discovery.clear();
        }
    }

    public synchronized List<String> peekJars(String label) {
        load();
        return jarsByLabel.get(label);
    }

    /*
        Misplaced files are found by reading the package declaration of every source file, which is
        ten thousand reads on this repository - about a second, and not something to repeat on every
        start. It is cached beside the discovery it belongs to and refreshed with it.
     */
    public synchronized List<SourceRelocation.Misplaced> peekMisplaced(String sourceRoot) {
        load();
        return misplaced.get(sourceRoot);
    }

    public synchronized void putMisplaced(String sourceRoot,
            List<SourceRelocation.Misplaced> files) {
        load();
        misplaced.put(sourceRoot, List.copyOf(files));
        dirty = true;
    }

    public synchronized void clearMisplaced() {
        load();
        if (!misplaced.isEmpty()) {
            misplaced.clear();
            dirty = true;
        }
    }

    public synchronized List<BazelQuery.Target> peekDiscovery() {
        load();
        return discovery.isEmpty() ? null : List.copyOf(discovery);
    }

    public synchronized String peekExecutionRoot() {
        load();
        return executionRoot;
    }

    public synchronized void putJars(Map<String, List<String>> jars) {
        load();
        jarsByLabel.putAll(jars);
        dirty = true;
    }

    public synchronized void putDiscovery(List<BazelQuery.Target> targets) {
        load();
        discovery.clear();
        discovery.addAll(targets);
        dirty = true;
    }

    public synchronized void setExecutionRoot(String value) {
        load();
        if (!executionRoot.equals(value)) {
            executionRoot = value;
            dirty = true;
        }
    }

    /*
        Cheap check first: a settings change is known without touching the filesystem. The build file
        walk only runs when the cheap check passes.
     */
    public synchronized boolean isStale(BazelSettings settings) {
        load();
        if (jarsByLabel.isEmpty() || discovery.isEmpty()) {
            return true;
        }
        if (!settingsFingerprint.equals(settings.fingerprint())) {
            return true;
        }
        String current = Digests.buildFilesDigest(root.toPath());
        return current.isEmpty() || !current.equals(buildFilesDigest);
    }

    public synchronized void stamp(BazelSettings settings) {
        load();
        settingsFingerprint = settings.fingerprint();
        buildFilesDigest = Digests.buildFilesDigest(root.toPath());
        dirty = true;
    }

    public synchronized void invalidate() {
        jarsByLabel.clear();
        discovery.clear();
        buildFilesDigest = "";
        dirty = true;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            BazelLog.info("Bazel: could not delete " + file + ": " + e);
        }
    }

    public synchronized int cachedLabelCount() {
        load();
        return jarsByLabel.size();
    }

    public synchronized void save() {
        if (!dirty) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("version", FORMAT_VERSION);
        json.addProperty("root", root.getAbsolutePath());
        json.addProperty("executionRoot", executionRoot);
        json.addProperty("settings", settingsFingerprint);
        json.addProperty("buildFiles", buildFilesDigest);

        JsonArray targets = new JsonArray();
        for (BazelQuery.Target target : discovery) {
            JsonObject entry = new JsonObject();
            entry.addProperty("label", target.label());
            entry.addProperty("packagePath", target.packagePath());
            entry.addProperty("sourceRoot", target.sourceRoot());
            targets.add(entry);
        }
        json.add("discovery", targets);

        JsonObject misplacedJson = new JsonObject();
        misplaced.forEach((sourceRoot, files) -> {
            JsonArray array = new JsonArray(files.size());
            files.forEach(file -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("path", file.relativePath());
                entry.addProperty("package", file.declaredPackage());
                array.add(entry);
            });
            misplacedJson.add(sourceRoot, array);
        });
        json.add("misplaced", misplacedJson);

        JsonObject classpath = new JsonObject();
        jarsByLabel.forEach((label, jars) -> {
            JsonArray array = new JsonArray(jars.size());
            jars.forEach(array::add);
            classpath.add(label, array);
        });
        json.add("classpath", classpath);

        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            BazelLog.info("Bazel: could not write " + file + ": " + e);
        }
    }

    private void readDiscovery(JsonArray array) {
        discovery.clear();
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            JsonObject entry = element.getAsJsonObject();
            discovery.add(new BazelQuery.Target(
                    entry.get("label").getAsString(),
                    entry.get("packagePath").getAsString(),
                    entry.get("sourceRoot").getAsString(),
                    List.of()));
        }
    }

    private void readMisplaced(JsonObject object) {
        misplaced.clear();
        if (object == null) {
            return;
        }
        object.entrySet().forEach(entry -> {
            List<SourceRelocation.Misplaced> files = new ArrayList<>();
            entry.getValue().getAsJsonArray().forEach(element -> {
                JsonObject file = element.getAsJsonObject();
                String path = file.get("path").getAsString();
                int slash = path.lastIndexOf('/');
                files.add(new SourceRelocation.Misplaced(path,
                        file.get("package").getAsString(), path.substring(slash + 1)));
            });
            misplaced.put(entry.getKey(), List.copyOf(files));
        });
    }

    private void readClasspath(JsonObject object) {
        jarsByLabel.clear();
        if (object == null) {
            return;
        }
        object.entrySet().forEach(entry -> {
            List<String> jars = new ArrayList<>();
            entry.getValue().getAsJsonArray().forEach(jar -> jars.add(jar.getAsString()));
            jarsByLabel.put(entry.getKey(), List.copyOf(jars));
        });
    }

    private static String optionalString(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }
}
