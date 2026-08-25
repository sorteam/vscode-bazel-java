package ch.audienzz.bazel.jdtls;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

/*
    Classpath jars per bazel label.

    The original implementation ran one `aquery mnemonic("Javac", <label>)` per target from inside
    ClasspathContainerInitializer.initialize(), synchronously. On the audienzz monorepo that is 223
    sequential bazel invocations at ~310 ms each, roughly 69 s, all of it blocking JDT.

    The comment that justified it claimed a repository-wide aquery was more expensive and that the
    action-to-label correlation would be lost. Measured on 2026-08-25 (bazel 9.2.0):

        223 x per-target aquery              ~69 s
        1 x aquery mnemonic("Javac", //...)  20.4 s
        1 x aquery --query_file set(442)      4.6 s   <- what this class does now

    And the correlation is in the output: `targets { id, label }` plus `actions { target_id }`.

    Naming the labels explicitly rather than using //... matters for more than the 4x: //... pulls
    every js, oci and helm target into analysis, which is precisely the set of packages that failed
    to load during the 2026-08-25 outage. An explicit label set keeps analysis inside the java
    closure.
 */
public final class BazelClasspathCache {

    /* One batch of 442 labels is 17.6 KB of query file and 4.6 s; chunking bounds both. */
    private static final int MAX_LABELS_PER_BATCH = 1000;

    private final BazelSession session;
    private final Map<String, List<String>> jarsByLabel = new HashMap<>();

    BazelClasspathCache(BazelSession session) {
        this.session = session;
    }

    public synchronized void clear() {
        jarsByLabel.clear();
    }

    /*
        A note on what is deliberately NOT done here: subtracting a target's own output jar from its
        own project's classpath. It looks right - a project owning both halves of a service has the
        main jar on the test classpath, so its classes appear twice - but on this repository the own
        jar is the only place some members exist at all: lombok runs on 216 of 223 targets, and the
        openapi generator feeds whole classes in through a -gensrc.jar. Removing it can only lose
        information, and the duplication is harmless because JDT resolves a type from the source
        folder when it has both.
     */

    /*
        Never runs bazel. Used by the container initializer, which must not block: it answers from
        memory, falling back to the persisted cache from the previous session.
     */
    public List<String> peek(String label) {
        synchronized (this) {
            List<String> cached = jarsByLabel.get(label);
            if (cached != null) {
                return cached;
            }
        }
        List<String> stored = session.getStore().peekJars(label);
        if (stored != null) {
            synchronized (this) {
                jarsByLabel.putIfAbsent(label, stored);
            }
            session.getReport().countCacheHit();
        }
        return stored;
    }

    /*
        Resolves one label, running a single-target aquery if nothing is cached. Reserved for the
        priority path - the project that owns the file the developer just opened - so that it does
        not have to wait for the batch covering the whole repository.
     */
    public List<String> jarsFor(String label, IProgressMonitor monitor) throws CoreException {
        List<String> cached = peek(label);
        if (cached != null) {
            return cached;
        }

        long started = System.currentTimeMillis();
        AqueryParser parser = new AqueryParser();
        runAquery(monitor, parser, "mnemonic(\"Javac\", " + label + ")");
        List<String> jars = parser.jarsByLabel().getOrDefault(label, List.of());

        session.getReport().countSingle();
        BazelLog.info(String.format("Bazel: %d classpath jars for %s in %d ms (single)",
                jars.size(), label, System.currentTimeMillis() - started));

        store(Map.of(label, jars), true);
        return jars;
    }

    /*
        Resolves every label in one go. This is the batch that replaces the per-target loop.
     */
    public Map<String, List<String>> warmAll(List<String> labels, IProgressMonitor monitor)
            throws CoreException {
        return warmAll(labels, monitor, false);
    }

    /*
        force re-queries labels that are already cached. Used after a BUILD file changed, where the
        cached answer is exactly the thing that can no longer be trusted. The old values stay in
        place until the new ones arrive, so containers never blink empty in between.
     */
    public Map<String, List<String>> refreshAll(List<String> labels, IProgressMonitor monitor)
            throws CoreException {
        return warmAll(labels, monitor, true);
    }

    private Map<String, List<String>> warmAll(List<String> labels, IProgressMonitor monitor,
            boolean force) throws CoreException {
        List<String> pending = new ArrayList<>();
        synchronized (this) {
            labels.stream().filter(label -> force || !jarsByLabel.containsKey(label))
                    .forEach(pending::add);
        }
        if (pending.isEmpty()) {
            return Map.of();
        }

        long started = System.currentTimeMillis();
        Map<String, List<String>> resolved = new LinkedHashMap<>();
        for (int offset = 0; offset < pending.size(); offset += MAX_LABELS_PER_BATCH) {
            if (monitor != null && monitor.isCanceled()) {
                break;
            }
            List<String> chunk =
                    pending.subList(offset, Math.min(pending.size(), offset + MAX_LABELS_PER_BATCH));
            AqueryParser parser = new AqueryParser();
            runAquery(monitor, parser, "mnemonic(\"Javac\", set(" + String.join(" ", chunk) + "))");
            session.getReport().countBatch();
            resolved.putAll(parser.jarsByLabel());
            // A label with no Javac action (an empty java_library, say) must be remembered as empty
            // or every pass would query it again.
            chunk.forEach(label -> resolved.putIfAbsent(label, List.of()));
        }

        store(resolved, force);
        long elapsed = System.currentTimeMillis() - started;
        long withJars = resolved.values().stream().filter(jars -> !jars.isEmpty()).count();
        BazelLog.info(String.format(
                "Bazel: warmed %d labels in %d ms (batch, %d with a Javac action)",
                resolved.size(), elapsed, withJars));
        session.getReport().phase("classpath", elapsed);
        return resolved;
    }

    private void runAquery(IProgressMonitor monitor, AqueryParser parser, String expression)
            throws CoreException {
        BazelWorkspace workspace = session.getWorkspace();
        Path queryFile = workspace.writeQueryFile(expression);
        workspace.runStreaming(monitor, parser::accept, "aquery",
                "--query_file=" + queryFile,
                "--output=textproto",
                // The artifact table is roughly 70% of the output and nothing here reads it; only
                // the action command line matters.
                "--include_artifacts=false",
                "--noshow_progress", "--keep_going", "--ui_event_filters=-info");
        parser.finish();
    }

    private void store(Map<String, List<String>> resolved, boolean overwrite) {
        synchronized (this) {
            if (overwrite) {
                jarsByLabel.putAll(resolved);
            } else {
                resolved.forEach(jarsByLabel::putIfAbsent);
            }
        }
        session.getStore().putJars(resolved);
    }
}
