package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.ls.core.internal.AbstractProjectImporter;

public class BazelProjectImporter extends AbstractProjectImporter {

    private static final List<String> WORKSPACE_MARKERS =
            List.of("MODULE.bazel", "REPO.bazel", "WORKSPACE.bazel", "WORKSPACE");

    private BazelSession session;
    private boolean imported;

    @Override
    public boolean isResolved(File folder) {
        return imported && session != null
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
            The backoff window is enforced here rather than inside the import itself. jdt.ls asks the
            importer whether it applies and, if it says yes and then throws, asks again on the next
            trigger - which on 2026-08-25 meant a full monorepo load every ~16 s for seven hours.
            Declining to apply keeps jdt.ls out of importToWorkspace() entirely until the window
            expires.
         */
        if (session.getDiscoveryGate().shouldSkip()) {
            BazelLog.warnOnce("import-skipped:" + rootFolder, String.format(
                    "Bazel: import is backing off for %d s after %d failure(s); "
                            + "run 'Bazel: Refresh Classpath' to retry now",
                    session.getDiscoveryGate().remainingSeconds(),
                    session.getDiscoveryGate().getConsecutiveFailures()));
            return false;
        }
        return true;
    }

    @Override
    public void importToWorkspace(IProgressMonitor monitor)
            throws OperationCanceledException, CoreException {
        if (session == null) {
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
                // A terminal command holds the server; a short fixed window in applies() is enough,
                // escalating the exponential backoff for it would punish a normal situation.
                session.getDiscoveryGate().recordBusy(e.getMessage());
            } else {
                session.getDiscoveryGate().recordFailure(e.getMessage());
            }
            throw e;
        }
        long discoveryMillis = System.currentTimeMillis() - discoveryStarted;
        report.setDiscoveredTargets(targets.size());
        report.phase("discovery", discoveryMillis);
        BazelLog.info(String.format("Bazel: %d java targets with sources in %d ms",
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
                "Bazel: %d projects in %d ms (created %d, updated %d, unchanged %d, pruned %d;"
                        + " nothing written to the working copy)",
                projects.size(), provisionMillis, provisioner.getCreated(),
                provisioner.getUpdated(), provisioner.getUnchanged(), provisioner.getPruned()));
        if (provisioner.getRelocatedFiles() > 0) {
            BazelLog.info(String.format(
                    "Bazel: %d source file(s) declare a package their directory does not match;"
                            + " linked into the package they declare",
                    provisioner.getRelocatedFiles()));
        }

        /*
            Classpath resolution is deliberately not awaited. This is the whole point of the change:
            "Workspace initialized" no longer waits on bazel, and the containers fill in behind it.
         */
        ClasspathResolveJob.enqueueAll(session, projects);
        session.getStore().save();

        imported = !projects.isEmpty();
    }

    /*
        Uses the cache written by the previous session when there is one, and refreshes it in the
        background. A cold repository still pays for one query; a restart pays nothing.
     */
    /*
        A bazel-* convenience symlink in the repository root is the one misconfiguration that can
        hang the whole language server rather than merely degrade it: jdt.ls follows symlinks in its
        first workspace scan, and that scan runs before java.project.resourceFilters exists, so it
        descends the entire action output tree - millions of files on a monorepo - and the import
        never finishes. The IDE's own builds pass --experimental_convenience_symlinks=ignore, so
        anything found here came from a build outside the IDE and only the repository's bazelrc can
        stop it coming back.
     */
    private void warnAboutConvenienceSymlinks() {
        List<String> symlinks = session.getWorkspace().convenienceSymlinks();
        if (symlinks.isEmpty()) {
            return;
        }
        session.getReport().note("convenience symlinks", String.join(", ", symlinks)
                + " (add 'common --experimental_convenience_symlinks=ignore' to the bazelrc)");
        BazelLog.warnOnce("convenience-symlinks:" + session.getWorkspace().getRoot(), String.format(
                "Bazel: the repository root holds the convenience symlink(s) %s. jdt.ls follows"
                        + " symlinks during its first workspace scan, before resource filters"
                        + " apply, so these can park the import in the bazel output tree. Add"
                        + " 'common --experimental_convenience_symlinks=ignore' to the bazelrc and"
                        + " delete them.",
                String.join(", ", symlinks)));
    }

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
                "Bazel: %d projects exceeds maxProjects=%d, importing the first %d."
                        + " Narrow the import with the 'targets' setting or .bazelproject.",
                specs.size(), max, max));
        session.getReport().note("capped", specs.size() + " -> " + max);
        return specs.subList(0, max);
    }

    @Override
    public void reset() {
        session = null;
        imported = false;
    }
}
