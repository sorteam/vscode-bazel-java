package io.github.sorteam.bazel.jdtls;

import java.util.List;
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

    /*
        A busy bazel server is not a failure, so it gets a short fixed window instead of the
        exponential one: the terminal build that holds the lock will finish on its own, and the
        consecutive-failure counter must not grow while it does.
     */
    private static final long BUSY_RETRY_SECONDS = 15;

    private final String name;
    private final int maxDelaySeconds;

    private int consecutiveFailures;
    private long retryNotBefore;
    private boolean busyWaiting;
    private boolean needsAFix;
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
        busyWaiting = false;
        lastFailure = reason == null ? "" : reason;
        long delay = Math.min(maxDelaySeconds,
                BASE_DELAY_SECONDS << Math.min(consecutiveFailures - 1, MAX_EXPONENT));
        retryNotBefore = System.nanoTime() + TimeUnit.SECONDS.toNanos(delay);

        /*
            An unfetchable external repository - a lock file that needs repinning, most often - is a
            failure no amount of retrying can clear, and "7 consecutive failure(s), retry in 52 s"
            told the developer nothing they could act on. It is called out separately, with whatever
            remedy bazel printed (BazelWorkspace keeps the traceback now, which is where the
            "REPIN=1 bazel run @maven//:pin" line lives).
         */
        needsAFix = BazelWorkspace.isFetchFailure(List.of(lastFailure));
        if (needsAFix) {
            BazelLog.warnOnce("gate-blocked:" + name, String.format(
                    "JBazel: %s cannot run until bazel can fetch its external repositories, and"
                            + " that will not clear on its own: %s. Fix it, then editing"
                            + " MODULE.bazel or 'JBazel: Refresh Classpath' retries at once.",
                    name, lastFailure));
            return;
        }
        BazelLog.warnOnce("gate:" + name, String.format(
                "JBazel: %s failed (%s), backing off %d s; further identical failures are counted,"
                        + " not logged. Run 'JBazel: Refresh Classpath' to retry now.",
                name, lastFailure, delay));
    }

    /*
        The bazel server is occupied by someone else's command - a terminal build, typically. Opens
        a short fixed window without touching the failure counter: the situation resolves itself and
        must not escalate towards the five-minute backoff ceiling.
     */
    public synchronized void recordBusy(String reason) {
        busyWaiting = true;
        needsAFix = false;
        lastFailure = reason == null ? "" : reason;
        retryNotBefore = System.nanoTime() + TimeUnit.SECONDS.toNanos(BUSY_RETRY_SECONDS);
        BazelLog.warnOnce("gate-busy:" + name, String.format(
                "JBazel: %s is waiting for the bazel server (busy with another command, likely a"
                        + " terminal build); retrying every %d s until it frees up",
                name, BUSY_RETRY_SECONDS));
    }

    public synchronized boolean isBusyWaiting() {
        return busyWaiting && shouldSkip();
    }

    public synchronized boolean needsAFix() {
        return needsAFix;
    }

    public synchronized void recordSuccess() {
        if (consecutiveFailures > 0) {
            BazelLog.info(String.format("JBazel: %s recovered after %d failed attempt(s)",
                    name, consecutiveFailures));
        }
        consecutiveFailures = 0;
        retryNotBefore = 0;
        busyWaiting = false;
        needsAFix = false;
        lastFailure = "";
        BazelLog.clear("gate:" + name);
        BazelLog.clear("gate-busy:" + name);
        BazelLog.clear("gate-blocked:" + name);
    }

    /*
        Clears the window without pretending the last attempt succeeded. Used by the explicit refresh
        command and by BUILD/MODULE.bazel changes, so a developer who just fixed the cause does not
        have to wait out a five minute backoff.
     */
    public synchronized void reset() {
        retryNotBefore = 0;
        busyWaiting = false;
        needsAFix = false;
        BazelLog.clear("gate:" + name);
        BazelLog.clear("gate-busy:" + name);
        BazelLog.clear("gate-blocked:" + name);
    }

    public synchronized String describe() {
        if (isBusyWaiting()) {
            return String.format("bazel server busy, retry in %d s", remainingSeconds());
        }
        if (consecutiveFailures == 0) {
            return "ok";
        }
        long remaining = remainingSeconds();
        if (needsAFix) {
            return String.format("needs a fix - bazel cannot fetch an external repository"
                    + " (retry in %d s once fixed): %s", remaining, lastFailure);
        }
        return String.format("%d consecutive failure(s), retry in %d s, last: %s",
                consecutiveFailures, remaining, lastFailure);
    }
}
