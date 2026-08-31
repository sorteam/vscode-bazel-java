package io.github.sorteam.bazel.jdtls;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;

/*
    Deduplicating logger.

    During the 2026-08-25 incident the same block of bazel loading-phase errors was written on every
    one of ~1400 retries: 10 MB in seven hours, which rotated the jdt.ls log every 42 minutes and
    destroyed the rest of the server's history. Repeated messages are therefore logged once and then
    counted; the count is flushed when the situation changes or when the import report is requested.
 */
public final class BazelLog {

    private static final Map<String, AtomicInteger> SUPPRESSED = new ConcurrentHashMap<>();

    private BazelLog() {
    }

    public static void info(String message) {
        write(message);
    }

    /*
        Logging must never be able to fail the import. JavaLanguageServerPlugin resolves a static
        plugin instance, which is absent outside an OSGi runtime - in the offline harness used to
        exercise the bazel pipeline, for one - and a NoClassDefFoundError from a log call would
        otherwise surface as a failed import.
     */
    private static void write(String message) {
        try {
            JavaLanguageServerPlugin.logInfo(message);
        } catch (Throwable ignored) {
            System.err.println(message);
        }
    }

    /*
        Logs the message the first time this key is seen and only counts every repeat. Returns true
        when the message actually reached the log, so callers can skip building expensive detail.
     */
    public static boolean warnOnce(String key, String message) {
        AtomicInteger seen = SUPPRESSED.computeIfAbsent(key, ignored -> new AtomicInteger());
        int count = seen.incrementAndGet();
        if (count == 1) {
            write(message);
            return true;
        }
        // Occasional heartbeat so a permanently broken workspace still shows up in the log, but at
        // a rate that cannot rotate it away.
        if (count % 100 == 0) {
            write(message + " (repeated " + count + " times)");
            return true;
        }
        return false;
    }

    /*
        Called after a success so the next failure is reported in full instead of being swallowed as
        a repeat of an issue that has since been fixed.
     */
    public static void clear(String key) {
        AtomicInteger seen = SUPPRESSED.remove(key);
        if (seen != null && seen.get() > 1) {
            write(String.format(
                    "JBazel: recovered after %d suppressed repeats of [%s]", seen.get() - 1, key));
        }
    }

    public static void clearAll() {
        SUPPRESSED.clear();
    }

    public static void exception(String message, Throwable cause) {
        try {
            JavaLanguageServerPlugin.logException(message, cause);
        } catch (Throwable ignored) {
            System.err.println(message + ": " + cause);
        }
    }
}
