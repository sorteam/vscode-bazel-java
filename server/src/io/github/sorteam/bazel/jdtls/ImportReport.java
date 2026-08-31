package io.github.sorteam.bazel.jdtls;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/*
    Counters and phase timings for the last import, surfaced by the "JJBazel: Show Import Report"
    command. The performance work was originally measured by grepping the jdt.ls log after the
    fact; this makes the same numbers available without that archaeology.
 */
public final class ImportReport {

    private final Map<String, Long> phaseMillis = new LinkedHashMap<>();
    private final Map<String, String> notes = new LinkedHashMap<>();

    private final AtomicInteger aqueryBatches = new AtomicInteger();
    private final AtomicInteger aquerySingles = new AtomicInteger();
    private final AtomicInteger cacheHits = new AtomicInteger();
    private final AtomicInteger missingJars = new AtomicInteger();
    private final AtomicInteger resolvedJars = new AtomicInteger();
    private final AtomicInteger jarsWithSources = new AtomicInteger();
    private final AtomicInteger publishedContainers = new AtomicInteger();
    private final AtomicInteger unchangedContainers = new AtomicInteger();
    private final AtomicInteger keptStaleClasspaths = new AtomicInteger();

    private volatile int discoveredTargets;
    private volatile int provisionedProjects;
    private volatile int prunedProjects;

    public synchronized void phase(String name, long millis) {
        phaseMillis.put(name, millis);
    }

    public synchronized void note(String key, String value) {
        notes.put(key, value);
    }

    public void countBatch() {
        aqueryBatches.incrementAndGet();
    }

    public void countSingle() {
        aquerySingles.incrementAndGet();
    }

    public void countCacheHit() {
        cacheHits.incrementAndGet();
    }

    public void countJars(int resolved, int missing, int withSources) {
        resolvedJars.addAndGet(resolved);
        missingJars.addAndGet(missing);
        jarsWithSources.addAndGet(withSources);
    }

    public void countContainerPublished() {
        publishedContainers.incrementAndGet();
    }

    /* A container whose stamp matched and was therefore not republished (no reindex). */
    public void countContainerUnchanged() {
        unchangedContainers.incrementAndGet();
    }

    /* Labels whose aquery answer came back empty while the cache had jars; the cache won. */
    public void countKeptStale(int labels) {
        keptStaleClasspaths.addAndGet(labels);
    }

    public void setDiscoveredTargets(int value) {
        discoveredTargets = value;
    }

    public void setProvisionedProjects(int value) {
        provisionedProjects = value;
    }

    public void setPrunedProjects(int value) {
        prunedProjects = value;
    }

    public int getMissingJars() {
        return missingJars.get();
    }

    public int getResolvedJars() {
        return resolvedJars.get();
    }

    /*
        Classpath entries that opened with real sources. The ratio against resolvedJars is the only
        honest answer to "why does Ctrl+Click land in decompiled bytecode": rules_jvm_external never
        fetches source jars on its own, so on an untouched repository this is close to zero for
        everything except the repository's own targets.
     */
    public int getJarsWithSources() {
        return jarsWithSources.get();
    }

    public int getProvisionedProjects() {
        return provisionedProjects;
    }

    public synchronized String render() {
        StringBuilder out = new StringBuilder();
        out.append("JBazel import report\n");
        out.append("  targets discovered : ").append(discoveredTargets).append('\n');
        out.append("  projects           : ").append(provisionedProjects)
                .append(" (pruned ").append(prunedProjects).append(")\n");
        out.append("  aquery batches     : ").append(aqueryBatches.get()).append('\n');
        out.append("  aquery single-target: ").append(aquerySingles.get()).append('\n');
        out.append("  classpath cache hits: ").append(cacheHits.get()).append('\n');
        out.append("  classpath jars     : ").append(resolvedJars.get())
                .append(" resolved, ").append(missingJars.get()).append(" missing on disk\n");
        out.append("  source attachments : ").append(jarsWithSources.get())
                .append(" of ").append(resolvedJars.get());
        if (resolvedJars.get() > 0 && jarsWithSources.get() * 2 < resolvedJars.get()) {
            out.append(" - run 'JBazel: Fetch Library Sources' for the rest");
        }
        out.append('\n');
        out.append("  containers         : ").append(publishedContainers.get())
                .append(" published, ").append(unchangedContainers.get())
                .append(" unchanged (kept, no reindex)\n");
        if (keptStaleClasspaths.get() > 0) {
            out.append("  kept stale classpaths: ").append(keptStaleClasspaths.get())
                    .append(" label(s) answered empty by a partial aquery\n");
        }
        phaseMillis.forEach((name, millis) ->
                out.append("  phase ").append(name).append(" : ").append(millis).append(" ms\n"));
        notes.forEach((key, value) ->
                out.append("  ").append(key).append(" : ").append(value).append('\n'));
        return out.toString();
    }
}
