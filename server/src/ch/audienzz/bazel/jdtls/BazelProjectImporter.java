package ch.audienzz.bazel.jdtls;

import java.io.File;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.ls.core.internal.AbstractProjectImporter;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;

public class BazelProjectImporter extends AbstractProjectImporter {

    private static final List<String> WORKSPACE_MARKERS =
            List.of("MODULE.bazel", "REPO.bazel", "WORKSPACE.bazel", "WORKSPACE");

    private BazelWorkspace workspace;
    private boolean imported;

    @Override
    public boolean isResolved(File folder) {
        return imported && workspace != null
                && workspace.getRoot().getAbsolutePath().equals(folder.getAbsolutePath());
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
        workspace = BazelWorkspaceRegistry.forRoot(rootFolder);
        return true;
    }

    @Override
    public void importToWorkspace(IProgressMonitor monitor)
            throws OperationCanceledException, CoreException {
        if (workspace == null) {
            return;
        }
        SubMonitor progress = SubMonitor.convert(monitor, 100);

        long queryStarted = System.currentTimeMillis();
        List<BazelQuery.Target> targets =
                new BazelQuery(workspace).javaTargets(progress.split(20));
        JavaLanguageServerPlugin.logInfo(String.format(
                "Bazel: %d java targets with sources in %d ms",
                targets.size(), System.currentTimeMillis() - queryStarted));

        long provisionStarted = System.currentTimeMillis();
        ProjectProvisioner provisioner = new ProjectProvisioner(workspace);
        List<IProject> projects = provisioner.provision(targets, progress.split(80));
        JavaLanguageServerPlugin.logInfo(String.format(
                "Bazel: %d projects in %d ms (created %d, updated %d, unchanged %d;"
                        + " nothing written to the working copy)",
                projects.size(), System.currentTimeMillis() - provisionStarted,
                provisioner.getCreated(), provisioner.getUpdated(),
                provisioner.getUnchanged()));

        imported = !projects.isEmpty();
    }

    @Override
    public void reset() {
        workspace = null;
        imported = false;
    }
}
