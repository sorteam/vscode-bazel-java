package io.github.sorteam.bazel.jdtls;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/*
    Ends the language server process when the editor that started it is gone and the server has not
    managed to end itself.

    The language server already watches for this. ParentProcessWatcher polls every 10 s, treats the
    parent as gone only after 30 s without protocol traffic - so it notices within about 40 s - and
    then calls LanguageServer.exit() and LanguageServerApplication.exit() before scheduling its own
    hard fallback. Which means the fallback is only ever reached if those two calls return. They go
    through the workspace and the job manager, so anything holding a platform-wide lock stops the
    exit at that point, and the fallback that was supposed to guarantee it is never scheduled at all.

    That is not hypothetical. Measured on this repository, from a thread dump: the editor closed, the
    process reparented to launchd, 1.4 GB resident, still alive four minutes later, with a job-change
    listener from another language server extension holding the job manager's write lock inside a
    client round trip that was joined with no timeout - a round trip nobody was left to answer. From
    that moment nothing in the platform can run: no workspace save, no exit.

    A process in that state is not merely wasted memory. It holds the lock on its -data directory, so
    the next server started there cannot come up inside the client's start budget, which is what makes
    the client abandon it and start a second one; and it keeps writing into the metadata the editor is
    trying to delete, which is what makes "clean the workspace" stop half way and leave a workspace
    that is inconsistent rather than empty. Both of those then read as bugs in whatever is installed.

    So this is deliberately the least sophisticated thing in the plugin: one daemon thread, no Eclipse
    job, no scheduling rule, no platform API in the loop. Nothing it does can be blocked by whatever
    is blocking everything else, which is the entire point - a watchdog that can be blocked is not a
    watchdog.
 */
final class ExitWatchdog {

    /*
        Longer than the language server's own detection window (~40 s) plus a comfortable margin for
        a legitimate shutdown to finish writing. The margin matters: the workspace save on the way
        out is a real write, and interrupting one is how metadata gets truncated - so the choice here
        is to be slow and certain rather than quick and destructive. Turning "alive until somebody
        finds it in Activity Monitor" into "gone inside two minutes" is the whole win.
     */
    private static final long DEFAULT_GRACE_SECONDS = 90;
    private static final long MIN_GRACE_SECONDS = 5;
    private static final long POLL_MILLIS = 1000;
    private static final long UNSET = Long.MIN_VALUE;

    private static final AtomicBoolean ARMED = new AtomicBoolean();
    /* Only ever lowered: whichever caller wants the process gone sooner wins. */
    private static final AtomicLong GRACE_NANOS = new AtomicLong(UNSET);

    private ExitWatchdog() {
    }

    /*
        Armed from the importer rather than from a static initializer, so the thread exists only
        where it is wanted: in a language server that has been asked about a bazel workspace, and not
        in every tool that happens to load one of these classes.
     */
    static void arm() {
        armWith(graceNanos());
    }

    private static void armWith(long grace) {
        GRACE_NANOS.accumulateAndGet(grace,
                (current, proposed) -> current == UNSET ? proposed : Math.min(current, proposed));
        if (!ARMED.compareAndSet(false, true)) {
            return;
        }
        Optional<ProcessHandle> parent = ProcessHandle.current().parent();
        if (parent.isEmpty()) {
            // Nothing to watch. A server with no parent was not started by an editor.
            return;
        }
        ProcessHandle editor = parent.get();
        Thread thread = new Thread(() -> watch(editor), "jbazel-exit-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /*
        Overridable so the behaviour can be exercised in seconds rather than in minutes, and so a
        deployment that wants to be less patient than the default can be.
     */
    private static long graceNanos() {
        long seconds = DEFAULT_GRACE_SECONDS;
        try {
            String configured = System.getProperty("jbazel.exitWatchdogSeconds");
            if (configured != null && !configured.isBlank()) {
                seconds = Math.max(MIN_GRACE_SECONDS, Long.parseLong(configured.strip()));
            }
        } catch (NumberFormatException | SecurityException e) {
            seconds = DEFAULT_GRACE_SECONDS;
        }
        return TimeUnit.SECONDS.toNanos(seconds);
    }

    private static void watch(ProcessHandle editor) {
        long goneSince = 0;
        while (true) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (editor.isAlive()) {
                goneSince = 0;
                continue;
            }
            if (goneSince == 0) {
                goneSince = System.nanoTime();
            }
            // Read on every turn: a server can stand down after the watchdog was armed.
            long grace = GRACE_NANOS.get();
            if (System.nanoTime() - goneSince < grace) {
                continue;
            }
            /*
                Written to stderr rather than through the language server's log: the log is a shared
                resource behind a lock, and this thread exists precisely because locks are what has
                gone wrong. If the pipe has no reader left the write fails and is swallowed, which is
                also fine - nobody is reading it either way.
             */
            System.err.println("JBazel: the editor that started this language server (pid "
                    + editor.pid() + ") has exited" + (grace > 0
                            ? " " + TimeUnit.NANOSECONDS.toSeconds(grace) + " s ago and the server has not"
                            : " and this server had already stood down")
                    + "; ending the process so it stops holding its workspace directory");
            /*
                Non-blocking by construction - see BazelSession.shutdownAll - and done here because
                halt() runs no shutdown hooks. This is the only thing in that hook worth keeping.
             */
            try {
                BazelSession.shutdownAll();
            } catch (RuntimeException | LinkageError e) {
                // Nothing to report to and nothing to fall back on; the halt below is the point.
            }
            // halt, not exit: exit runs the shutdown hooks, and a hook is a thing that can block.
            Runtime.getRuntime().halt(0);
            return;
        }
    }
}
