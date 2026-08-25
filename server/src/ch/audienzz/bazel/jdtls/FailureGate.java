package ch.audienzz.bazel.jdtls;

import java.util.concurrent.TimeUnit;

/*
    Negative caching with exponential backoff.

    Deliberately independent of the exit-code handling in BazelWorkspace. Accepting exit 3 fixes the
    one trigger seen on 2026-08-25 (unreachable external repositories during the loading phase), but
    any other non-zero code - a genuinely broken BUILD file, an expired registry token, no network -
    would bring back the same "fail, retry immediately, fail" loop that ran ~1400 times overnight and
    kept the bazel server from ever going idle.

    The gate is consulted from BazelProjectImporter.applies(): while the window is open the importer
    reports that it does not apply, so jdt.ls never enters importToWorkspace() at all.
 */
public final class FailureGate {

    private static final long BASE_DELAY_SECONDS = 2;
    private static final int MAX_EXPONENT = 8;

    private final String name;
    private final int maxDelaySeconds;

    private int consecutiveFailures;
    private long retryNotBefore;
    private String lastFailure = "";

    public FailureGate(String name, int maxDelaySeconds) {
        this.name = name;
        this.maxDelaySeconds = Math.max(1, maxDelaySeconds);
    }

    public synchronized boolean shouldSkip() {
        return retryNotBefore != 0 && System.nanoTime() < retryNotBefore;
    }

    public synchronized long remainingSeconds() {
        if (!shouldSkip()) {
            return 0;
        }
        return TimeUnit.NANOSECONDS.toSeconds(retryNotBefore - System.nanoTime()) + 1;
    }

    public synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized String getLastFailure() {
        return lastFailure;
    }

    public synchronized void recordFailure(String reason) {
        consecutiveFailures++;
        lastFailure = reason == null ? "" : reason;
        long delay = Math.min(maxDelaySeconds,
                BASE_DELAY_SECONDS << Math.min(consecutiveFailures - 1, MAX_EXPONENT));
        retryNotBefore = System.nanoTime() + TimeUnit.SECONDS.toNanos(delay);
        BazelLog.warnOnce("gate:" + name, String.format(
                "Bazel: %s failed (%s), backing off %d s; further identical failures are counted,"
                        + " not logged. Run 'Bazel: Refresh Classpath' to retry now.",
                name, lastFailure, delay));
    }

    public synchronized void recordSuccess() {
        if (consecutiveFailures > 0) {
            BazelLog.info(String.format("Bazel: %s recovered after %d failed attempt(s)",
                    name, consecutiveFailures));
        }
        consecutiveFailures = 0;
        retryNotBefore = 0;
        lastFailure = "";
        BazelLog.clear("gate:" + name);
    }

    /*
        Clears the window without pretending the last attempt succeeded. Used by the explicit refresh
        command and by BUILD/MODULE.bazel changes, so a developer who just fixed the cause does not
        have to wait out a five minute backoff.
     */
    public synchronized void reset() {
        retryNotBefore = 0;
        BazelLog.clear("gate:" + name);
    }

    public synchronized String describe() {
        if (consecutiveFailures == 0) {
            return "ok";
        }
        long remaining = remainingSeconds();
        return String.format("%d consecutive failure(s), retry in %d s, last: %s",
                consecutiveFailures, remaining, lastFailure);
    }
}
