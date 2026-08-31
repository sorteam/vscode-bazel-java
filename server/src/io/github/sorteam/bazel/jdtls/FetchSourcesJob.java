package io.github.sorteam.bazel.jdtls;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/*
    Downloads the source jars of the third-party artifacts on the classpath.

    Without them Ctrl+Click into a library type lands in decompiled bytecode: no parameter names, no
    javadoc, no comments. rules_jvm_external supports fetch_sources = True, but even with it set the
    source jars are never fetched: they are inputs to no action, so nothing in a normal build pulls
    them, and bazel downloads external files lazily. They have to be asked for by name.

    Deliberately a command and never automatic. This is a couple of gigabytes of downloads on a
    repository the size the plugin was built for, and it changes nothing about what compiles - only
    what a developer sees when they navigate into a dependency.
 */
public final class FetchSourcesJob extends Job {

    private static final long TIMEOUT_SECONDS = TimeUnit.HOURS.toSeconds(2);

    /*
        The listing gets its own generous timeout rather than the short one the indexing path uses:
        it is the first command that touches the artifact repository, so on a repository that has
        never fetched it this is a download, not a lookup. The indexing timeout (120 s by default)
        exists to keep a hung query from hanging the IDE, and nothing here is on that path.
     */
    private static final long LISTING_TIMEOUT_SECONDS = TimeUnit.MINUTES.toSeconds(15);

    /* Same politeness as the classpath build: ten minutes of deferring, then give up quietly. */
    private static final int MAX_BUSY_DEFERRALS = 20;
    private static final long BUSY_DEFER_MILLIS = 30_000;

    private final BazelSession session;

    private int busyDeferrals;

    private FetchSourcesJob(BazelSession session) {
        super("Fetching library sources for " + session.getWorkspace().getRoot().getName());
        this.session = session;
        setPriority(Job.LONG);
        setUser(true);
    }

    public static String start(Collection<BazelSession> sessions) {
        int started = 0;
        for (BazelSession session : sessions) {
            new FetchSourcesJob(session).schedule();
            started++;
        }
        return started == 0
                ? "No bazel workspace is imported."
                : "Fetching library sources for " + started + " workspace(s) in the background."
                        + " This downloads a source jar per artifact and can take a while; the"
                        + " classpath updates by itself when it finishes.";
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        if (session.getWorkspace().wasBusyRecently() && ++busyDeferrals <= MAX_BUSY_DEFERRALS) {
            schedule(BUSY_DEFER_MILLIS);
            return Status.OK_STATUS;
        }
        long started = System.currentTimeMillis();
        try {
            List<String> labels = sourceJarLabels(monitor);
            if (labels.isEmpty()) {
                BazelLog.info(String.format(
                        "JBazel: no source jar targets found in @%s//:all. Set fetch_sources = True"
                                + " on the maven extension, or point bazelJava.mavenRepository at"
                                + " the repository that holds the artifacts.",
                        session.getSettings().getMavenRepository()));
                return Status.OK_STATUS;
            }

            BazelLog.info(String.format("JBazel: fetching %d source jar(s)", labels.size()));
            java.nio.file.Path targetFile =
                    session.getWorkspace().writeQueryFile(String.join("\n", labels));
            List<String> arguments = new ArrayList<>(List.of(
                    "build", "--target_pattern_file=" + targetFile,
                    "--keep_going", "--noshow_progress"));
            session.getSettings().buildJobsArgument().ifPresent(arguments::add);
            session.getWorkspace().runStreaming(monitor, line -> { }, TIMEOUT_SECONDS,
                    arguments.toArray(String[]::new));

            BazelLog.info(String.format("JBazel: fetched %d source jar(s) in %d ms; refreshing"
                    + " the classpath so they get attached",
                    labels.size(), System.currentTimeMillis() - started));
            /*
                A forced refresh, and it only republishes what changed: the container stamp covers
                each jar's source attachment as well as the jar itself, so exactly the projects
                whose dependencies gained sources are handed back to JDT.
             */
            DiscoveryRefreshJob.scheduleFor(session, true);
        } catch (CoreException e) {
            if (BazelWorkspace.isServerBusy(e) && ++busyDeferrals <= MAX_BUSY_DEFERRALS) {
                schedule(BUSY_DEFER_MILLIS);
                return Status.OK_STATUS;
            }
            BazelLog.info("JBazel: fetching library sources failed: " + e.getMessage());
        }
        return Status.OK_STATUS;
    }

    /*
        Which targets to build.

        The artifacts live in an external repository whose name the plugin cannot know - it is
        whatever the maven extension was given in MODULE.bazel - so it is a setting, defaulting to
        the conventional "maven". Listing that repository and keeping the labels that name a sources
        artifact needs no knowledge of rules_jvm_external's target naming, which differs between
        versions and between the bzlmod and WORKSPACE paths.

        --keep_going matters here more than elsewhere: an artifact that publishes no sources jar at
        all is normal (18 of 840 on the repository this was measured on), and one of them must not
        take the other 822 down with it.
     */
    private List<String> sourceJarLabels(IProgressMonitor monitor) throws CoreException {
        String repository = session.getSettings().getMavenRepository();
        List<String> output = new ArrayList<>();
        session.getWorkspace().runStreaming(monitor, output::add, LISTING_TIMEOUT_SECONDS,
                "query", "@" + repository + "//:all", "--output=label",
                "--keep_going", "--noshow_progress");
        return sourceLabels(output);
    }

    /*
        The labels naming a sources artifact, out of everything the repository lists. Matching on the
        name rather than on the rule kind is deliberate: rules_jvm_external has named these targets
        differently across versions and between its bzlmod and WORKSPACE paths, while "sources" has
        stayed in the name throughout. Lines that are not labels at all - the loading-phase warnings
        --keep_going prints - are dropped by the leading @.
     */
    static List<String> sourceLabels(List<String> queryOutput) {
        Set<String> labels = new LinkedHashSet<>();
        for (String line : queryOutput) {
            String label = line.strip();
            if (label.startsWith("@") && label.toLowerCase().contains("sources")) {
                labels.add(label);
            }
        }
        return new ArrayList<>(labels);
    }
}
