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

    /*
        Bumped to 8 when the runtime jars moved out of "classpath" into their own map: a cache
        written by 0.8.0 has them merged in, and there is no telling the two apart afterwards.

        Not bumped for the published-stamp map: a cache written by an older version simply has no
        "published" object, which reads as "nothing published yet" and costs one republish, while a
        version bump would throw away the discovery and the resolved jars too - a cold bazel query
        and a full aquery warm on the first start after every upgrade.
     */
    private static final int FORMAT_VERSION = 8;
    private static final Map<String, ClasspathStore> STORES = new ConcurrentHashMap<>();

    private final File root;
    private final Path file;

    private final Map<String, List<String>> jarsByLabel = new LinkedHashMap<>();
    private final List<BazelQuery.Target> discovery = new ArrayList<>();
    private final Map<String, List<SourceRelocation.Misplaced>> misplaced = new LinkedHashMap<>();

    /*
        ContainerStamp of the container last handed to JDT, per project, persisted across sessions.

        In memory alone it was not enough. Since the generated projects survive a restart
        (BazelBuildSupport), JDT restores their containers from its own state and never asks this
        plugin to initialise them - so a fresh session knew nothing about what it had published, and
        the first resolve after every start republished all of them. Republishing makes JDT drop and
        re-index every jar behind the container: on a large repository ~1.6k jars, a gigabyte of
        index writes, and a real chance of tripping over an index file a previous kill left
        truncated.
     */
    private final Map<String, Long> publishedStamps = new LinkedHashMap<>();

    /*
        The jars a label needs to run, kept apart from the ones it needs to compile. They must not
        go into the project's classpath - see BazelRuntimeClasspathResolver for where they do go.
     */
    private final Map<String, List<String>> runtimeJarsByLabel = new LinkedHashMap<>();

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
            readPublishedStamps(json.getAsJsonObject("published"));
            readInto(json.getAsJsonObject("runtime"), runtimeJarsByLabel);
            BazelLog.info(String.format(
                    "JBazel: loaded cached import for %s (%d targets, %d classpaths)",
                    root.getName(), discovery.size(), jarsByLabel.size()));
        } catch (IOException | RuntimeException e) {
            BazelLog.info("JBazel: ignoring unreadable classpath cache " + file + ": " + e);
            jarsByLabel.clear();
            discovery.clear();
        }
    }

    public synchronized Long peekPublishedStamp(String projectName) {
        load();
        return publishedStamps.get(projectName);
    }

    public synchronized void putPublishedStamp(String projectName, long stamp) {
        load();
        Long previous = publishedStamps.put(projectName, stamp);
        dirty = dirty || previous == null || previous != stamp;
    }

    /* An explicit refresh means "hand JDT everything again", so the record of what it already has
       goes with it. */
    public synchronized void clearPublishedStamps() {
        load();
        if (!publishedStamps.isEmpty()) {
            publishedStamps.clear();
            dirty = true;
        }
    }

    public synchronized List<String> peekRuntimeJars(String label) {
        load();
        return runtimeJarsByLabel.get(label);
    }

    public synchronized void putRuntimeJars(Map<String, List<String>> jars) {
        load();
        runtimeJarsByLabel.putAll(jars);
        dirty = true;
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
        return isStale(settings, Digests.buildFilesDigest(root.toPath()));
    }

    /*
        Variant for callers that already walked the build files. The digest a refresh stamps must be
        the one taken BEFORE its discovery ran - stamping a digest computed afterwards marks data
        from the old tree as current when the tree moved mid-refresh (a second branch switch).
     */
    public synchronized boolean isStale(BazelSettings settings, String currentDigest) {
        load();
        if (jarsByLabel.isEmpty() || discovery.isEmpty()) {
            return true;
        }
        if (!settingsFingerprint.equals(settings.fingerprint())) {
            return true;
        }
        return currentDigest.isEmpty() || !currentDigest.equals(buildFilesDigest);
    }

    /* False until the first completed import stamps the cache (and again after invalidate()). */
    public synchronized boolean hasStamp() {
        load();
        return !buildFilesDigest.isEmpty();
    }

    public synchronized void stamp(BazelSettings settings) {
        stamp(settings, Digests.buildFilesDigest(root.toPath()));
    }

    public synchronized void stamp(BazelSettings settings, String digest) {
        load();
        settingsFingerprint = settings.fingerprint();
        buildFilesDigest = digest;
        dirty = true;
    }

    public synchronized void invalidate() {
        jarsByLabel.clear();
        runtimeJarsByLabel.clear();
        discovery.clear();
        publishedStamps.clear();
        buildFilesDigest = "";
        dirty = true;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            BazelLog.info("JBazel: could not delete " + file + ": " + e);
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

        JsonObject runtime = new JsonObject();
        runtimeJarsByLabel.forEach((label, jars) -> {
            JsonArray array = new JsonArray(jars.size());
            jars.forEach(array::add);
            runtime.add(label, array);
        });
        json.add("runtime", runtime);

        JsonObject published = new JsonObject();
        publishedStamps.forEach(published::addProperty);
        json.add("published", published);

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
            BazelLog.info("JBazel: could not write " + file + ": " + e);
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

    private void readPublishedStamps(JsonObject json) {
        publishedStamps.clear();
        if (json == null) {
            return;
        }
        json.entrySet().forEach(entry -> {
            try {
                publishedStamps.put(entry.getKey(), entry.getValue().getAsLong());
            } catch (RuntimeException e) {
                // A stamp that cannot be read means "unknown", which republishes once. Dropping the
                // whole cache over it would cost a full reindex instead.
            }
        });
    }

    private void readClasspath(JsonObject object) {
        readInto(object, jarsByLabel);
    }

    private static void readInto(JsonObject object, Map<String, List<String>> into) {
        into.clear();
        if (object == null) {
            return;
        }
        object.entrySet().forEach(entry -> {
            List<String> jars = new ArrayList<>();
            entry.getValue().getAsJsonArray().forEach(jar -> jars.add(jar.getAsString()));
            into.put(entry.getKey(), List.copyOf(jars));
        });
    }

    private static String optionalString(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }
}
