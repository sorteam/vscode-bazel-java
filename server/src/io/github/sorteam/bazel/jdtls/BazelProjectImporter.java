package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.ls.core.internal.AbstractProjectImporter;

public class BazelProjectImporter extends AbstractProjectImporter {

    private static final List<String> WORKSPACE_MARKERS =
            List.of("MODULE.bazel", "REPO.bazel", "WORKSPACE.bazel", "WORKSPACE");

    /*
        When the last import of each root finished, so that a client asking for the workspace to be
        initialized over and over does not pay for the whole repository each time. Static because
        jdt.ls builds a fresh importer for every import round, which is exactly the situation this
        has to survive.
     */
    private static final Map<String, Long> LAST_IMPORT = new ConcurrentHashMap<>();
    private static final long SETTLED_NANOS = TimeUnit.SECONDS.toNanos(3);

    private BazelSession session;
    private boolean noJavaHere;

    /*
        Whether jdt.ls can stop looking for someone to import this folder. The answer decides which
        importer gets it next, and the ones that come next are gradle, maven, eclipse and - last -
        the fallback that claims the folder wholesale and infers a source root per opened file.

        So the answer is yes for a bazel workspace even when this import provisioned nothing. A
        backoff window, an import scope narrowed to a handful of targets, a client that re-initialized
        while the previous round was still settling: none of those mean the folder is not a bazel
        workspace, and all of them used to end with the fallback importer taking it and every file in
        the repository resolving against a guessed source root. The one case that does hand the folder
        on is a repository where discovery ran, succeeded, and found no java at all - then it really
        is somebody else's, and it may well also be a maven or gradle build.
     */
    @Override
    public boolean isResolved(File folder) {
        return session != null && !noJavaHere
                && session.getWorkspace().getRoot().getAbsolutePath()
                        .equals(folder.getAbsolutePath());
    }

    @Override
    public boolean applies(IProgressMonitor monitor)
            throws OperationCanceledException, CoreException {
        if (rootFolder == null) {
            return false;
        }
        boolean marked = WORKSPACE_MARKERS.stream()
                .anyMatch(name -> new File(rootFolder, name).isFile());
        if (!marked) {
            return false;
        }
        session = BazelSession.forRoot(rootFolder);

        /*
            Armed on the first bazel workspace this server is asked about. What it guards against is
            a process that cannot exit after the editor is gone, which is a state this plugin does
            not cause but does make expensive - see ExitWatchdog.
         */
        ExitWatchdog.arm();

        /*
            Convenience symlinks in the root are no longer anybody's misconfiguration: they are
            bazel's, other tooling in a repository may resolve outputs through them, and what has to
            happen is that jdt.ls stops walking into them. Raised here, before this importer answers
            applies() - and therefore before the fallback importers, whose FOLLOW_LINKS scan is the
            thing that hangs on them, ever run. See ScanFence and ImportExclusions.

            It matters that this runs before the answer as well: the dangerous case is precisely the
            one where this importer declines, because that is when jdt.ls moves on to gradle, maven,
            eclipse and invisible-project detection.
         */
        ScanFence.raise(session);

        return true;
    }

    @Override
    public void importToWorkspace(IProgressMonitor monitor)
            throws OperationCanceledException, CoreException {
        if (session == null) {
            return;
        }
        /*
            The backoff window is enforced here rather than by declining to apply. jdt.ls asks the
            importer whether it applies and, if it says yes and then throws, asks again on the next
            trigger - which on 2026-08-25 meant a full monorepo load every ~16 s for seven hours. But
            declining outright hands the folder to the fallback importer, so what backs off is the
            work, not the claim: see isResolved.
         */
        if (session.getDiscoveryGate().shouldSkip()) {
            BazelLog.warnOnce("import-skipped:" + rootFolder, String.format(
                    "JBazel: import is backing off for %d s after %d failure(s); "
                            + "run 'JBazel: Refresh Classpath' to retry now",
                    session.getDiscoveryGate().remainingSeconds(),
                    session.getDiscoveryGate().getConsecutiveFailures()));
            return;
        }
        /*
            A second full import moments after the first cannot find anything new, and there is a
            client failure mode that asks for one twice a second: a language client whose transport
            did not come up in time starts a second one against the same server, the second start
            collides with the commands the first one registered, and the retry loop that follows
            re-initializes the workspace until someone closes the window. Nothing here can stop that
            loop, but paying for the repository each time it turns is what makes it fatal rather than
            noisy - so it is not paid.
         */
        Long previous = LAST_IMPORT.get(rootFolder.getAbsolutePath());
        if (previous != null && System.nanoTime() - previous < SETTLED_NANOS) {
            BazelLog.warnOnce("import-settled:" + rootFolder,
                    "JBazel: the workspace was initialized again within seconds of the last import;"
                            + " keeping the projects already provisioned");
            return;
        }
        SubMonitor progress = SubMonitor.convert(monitor, 100);
        ImportReport report = session.getReport();
        warnAboutConvenienceSymlinks();

        long discoveryStarted = System.currentTimeMillis();
        List<BazelQuery.Target> targets;
        try {
            targets = discover(progress.split(20));
            session.getDiscoveryGate().recordSuccess();
        } catch (CoreException e) {
            if (BazelWorkspace.isServerBusy(e)) {
                // A terminal command holds the server; a short fixed window is enough, escalating
                // the exponential backoff for it would punish a normal situation.
                session.getDiscoveryGate().recordBusy(e.getMessage());
            } else {
                session.getDiscoveryGate().recordFailure(e.getMessage());
            }
            throw e;
        }
        long discoveryMillis = System.currentTimeMillis() - discoveryStarted;
        report.setDiscoveredTargets(targets.size());
        report.phase("discovery", discoveryMillis);
        BazelLog.info(String.format("JBazel: %d java targets with sources in %d ms",
                targets.size(), discoveryMillis));

        List<ProjectGrouping.ProjectSpec> specs = ProjectGrouping.group(targets,
                session.getSettings().isGroupSourceRoots(),
                session.getWorkspace().getRoot().getName());
        specs = capped(specs);

        long provisionStarted = System.currentTimeMillis();
        ProjectProvisioner provisioner = new ProjectProvisioner(session);
        List<IJavaProject> projects = provisioner.provision(specs, true, progress.split(80));
        long provisionMillis = System.currentTimeMillis() - provisionStarted;
        report.setProvisionedProjects(projects.size());
        report.setPrunedProjects(provisioner.getPruned());
        report.phase("provision", provisionMillis);
        BazelLog.info(String.format(
                "JBazel: %d projects in %d ms (created %d, updated %d, unchanged %d, pruned %d;"
                        + " nothing written to the working copy)",
                projects.size(), provisionMillis, provisioner.getCreated(),
                provisioner.getUpdated(), provisioner.getUnchanged(), provisioner.getPruned()));
        if (provisioner.getRelocatedFiles() > 0) {
            BazelLog.info(String.format(
                    "JBazel: %d source file(s) declare a package their directory does not match;"
                            + " linked into the package they declare",
                    provisioner.getRelocatedFiles()));
        }

        /*
            Classpath resolution is deliberately not awaited. This is the whole point of the change:
            "Workspace initialized" no longer waits on bazel, and the containers fill in behind it.
         */
        ClasspathResolveJob.enqueueAll(session, projects);
        session.getStore().save();

        noJavaHere = targets.isEmpty();
        LAST_IMPORT.put(rootFolder.getAbsolutePath(), System.nanoTime());

        /*
            A fallback project left over from a previous session shadows every file this import just
            provisioned, and it is dismissed here rather than waiting for someone to open one of
            them.
         */
        InvisibleProject.reclaim(session.getWorkspace().getRoot(), null);
    }

    /*
        Reported, not treated as a fault: the note exists so that "why is bazel-bin in my import
        report" has an answer, and so the status bar can say the scan was fenced off rather than
        asking anyone to delete anything.
     */
    private void warnAboutConvenienceSymlinks() {
        List<String> symlinks = session.getWorkspace().convenienceSymlinks();
        if (symlinks.isEmpty()) {
            return;
        }
        session.getReport().note("convenience symlinks",
                String.join(", ", symlinks) + " (excluded from the java build-file scan)");
    }

    /*
        Uses the cache written by the previous session when there is one, and refreshes it in the
        background. A cold repository still pays for one query; a restart pays nothing.
     */
    private List<BazelQuery.Target> discover(IProgressMonitor monitor) throws CoreException {
        List<BazelQuery.Target> cached = session.getStore().peekDiscovery();
        if (cached != null && !cached.isEmpty()) {
            session.getReport().note("discovery", "from cache");
            DiscoveryRefreshJob.scheduleFor(session);
            return cached;
        }
        BazelSettings settings = session.getSettings();
        List<BazelQuery.Target> targets = new BazelQuery(session.getWorkspace())
                .javaTargets(monitor, settings.isDiscoveryNoFetch());
        session.getStore().putDiscovery(targets);
        session.getReport().note("discovery", "from bazel query");
        return targets;
    }

    private List<ProjectGrouping.ProjectSpec> capped(List<ProjectGrouping.ProjectSpec> specs) {
        int max = session.getSettings().getMaxProjects();
        if (specs.size() <= max) {
            return specs;
        }
        BazelLog.info(String.format(
                "JBazel: %d projects exceeds maxProjects=%d, importing the first %d."
                        + " Narrow the import with the 'targets' setting or .bazelproject.",
                specs.size(), max, max));
        session.getReport().note("capped", specs.size() + " -> " + max);
        return specs.subList(0, max);
    }

    @Override
    public void reset() {
        session = null;
        noJavaHere = false;
    }
}
