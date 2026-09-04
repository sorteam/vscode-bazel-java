package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
    Everything that is per bazel workspace and has to outlive a single import: the process runner,
    the in-memory and on-disk classpath caches, the backoff gates and the last import's counters.

    Held statically because the pieces are reached from three unrelated entry points - the project
    importer, the classpath container initializer that JDT calls on its own schedule, and the
    delegate command handler - none of which can pass state to the others.
 */
public final class BazelSession {

    private static final Map<String, BazelSession> SESSIONS = new ConcurrentHashMap<>();

    /*
        The bazel server outlives its client by design: it detaches, and with the default
        --max_idle_secs of three hours an orphaned one holds ~1.15 GB for that long. jdt.ls does not
        always get a clean OSGi stop - it exits through "Parent process stopped running, forcing
        server exit" - so the hook is registered on the JVM, which covers both paths. It only ever
        touches a server this plugin started under its own output base.
     */
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(BazelSession::shutdownAll, "bazel-shutdown"));
    }

    private final BazelWorkspace workspace;
    private final ClasspathStore store;
    private final BazelClasspathCache cache;
    private final ImportReport report = new ImportReport();
    private final java.util.concurrent.atomic.AtomicBoolean classpathBuildStarted =
            new java.util.concurrent.atomic.AtomicBoolean();

    /*
        ContainerStamp of the container last handed to JDT, per project. Publishing an identical
        container again makes JDT drop and re-index every jar behind it, so the resolve job compares
        against this before calling setClasspathContainer. Seeded by the container initializer with
        what it published from the disk cache, and cleared by an explicit refresh so that one always
        republishes.
     */
    private final Map<String, Long> publishedContainerStamps = new ConcurrentHashMap<>();

    private final FailureGate discoveryGate;
    private final FailureGate classpathGate;

    private BazelSession(File root) {
        this.workspace = new BazelWorkspace(root);
        this.store = ClasspathStore.get(root);
        this.cache = new BazelClasspathCache(this);
        int backoff = workspace.getSettings().getBackoffMaxSeconds();
        this.discoveryGate = new FailureGate("discovery(" + root.getName() + ")", backoff);
        this.classpathGate = new FailureGate("classpath(" + root.getName() + ")", backoff);
    }

    public static BazelSession forRoot(File root) {
        return SESSIONS.computeIfAbsent(root.getAbsolutePath(),
                path -> new BazelSession(new File(path)));
    }

    public static Collection<BazelSession> all() {
        return SESSIONS.values();
    }

    public BazelWorkspace getWorkspace() {
        return workspace;
    }

    public BazelSettings getSettings() {
        return workspace.getSettings();
    }

    public ClasspathStore getStore() {
        return store;
    }

    public BazelClasspathCache getCache() {
        return cache;
    }

    public ImportReport getReport() {
        return report;
    }

    /* True exactly once per session, so the automatic build does not loop with its own refresh. */
    public boolean markClasspathBuildStarted() {
        return classpathBuildStarted.compareAndSet(false, true);
    }

    public FailureGate getDiscoveryGate() {
        return discoveryGate;
    }

    public FailureGate getClasspathGate() {
        return classpathGate;
    }

    /*
        Falls back to the previous session's record. The projects outlive a restart, so JDT restores
        their containers without asking this plugin to initialise them, and an empty in-memory map
        would make the first resolve of every session republish - and reindex - the whole classpath.
     */
    public Long getPublishedContainerStamp(String projectName) {
        Long known = publishedContainerStamps.get(projectName);
        if (known != null) {
            return known;
        }
        Long stored = store.peekPublishedStamp(projectName);
        if (stored != null) {
            publishedContainerStamps.putIfAbsent(projectName, stored);
        }
        return stored;
    }

    public void setPublishedContainerStamp(String projectName, long stamp) {
        publishedContainerStamps.put(projectName, stamp);
        store.putPublishedStamp(projectName, stamp);
    }

    /*
        Explicit refresh: forget everything cached and lift any backoff window, so a developer who
        just fixed a BUILD file or reconnected to the network does not wait out the backoff.
     */
    public void refresh(boolean dropDiskCache) {
        workspace.reloadSettings();
        BazelBinary.invalidate();
        cache.clear();
        publishedContainerStamps.clear();
        store.clearPublishedStamps();
        if (dropDiskCache) {
            store.invalidate();
        }
        discoveryGate.reset();
        classpathGate.reset();
        classpathBuildStarted.set(false);
        BazelLog.clearAll();
    }

    public static void shutdownAll() {
        SESSIONS.values().forEach(session -> {
            session.store.save();
            session.workspace.shutdownOwnedServer();
        });
    }
}
