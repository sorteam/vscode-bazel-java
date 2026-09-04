package io.github.sorteam.bazel.jdtls;

import java.util.ArrayList;
import java.util.Collection;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.ls.core.internal.managers.IBuildSupport;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager.CHANGE_TYPE;

/*
    Claims this plugin's projects so that jdt.ls stops deleting them on every start.

    Measured on a 116-project workspace: every window reload logged "created 116, updated 0,
    unchanged 0" and jdt.ls reported 116 *new* projects. The projects were not stale - they were
    deleted a second earlier by StandardProjectsManager.deleteInvalidProjects, which keeps a project
    only when its location is inside one of the workspace root folders, or its name is the
    invisible-project name for one of them, or a build support claims it and answers
    hasSpecificDeleteProjectLogic. Generated projects live in the language server's metadata area -
    their source folders are links into the repository, nothing is written to the working copy - so
    the first two conditions are false and nothing claimed them.

    The cost was not the 3-4 s of re-provisioning. A deleted project takes its JDT index with it, so
    every reload re-indexed the whole repository: minutes of CPU before completion or search worked,
    on a workspace that had not changed at all.

    Deciding what is stale stays with this plugin (ProjectProvisioner.pruneStaleProjects), which
    compares projects against the current target list instead of against where their metadata lives.
 */
public class BazelBuildSupport implements IBuildSupport {

    /*
        The workspace-root property is written by the provisioner and by nothing else, so it
        identifies a project as this plugin's without reading its classpath. Gradle and maven
        projects fall through to their own build supports (order 300 and 400 against 150 here).
     */
    @Override
    public boolean applies(IProject project) {
        return ProjectLabels.read(project) != null;
    }

    /*
        False deliberately: this answers "should jdt.ls offer to synchronise the project
        configuration when this file changes", and BUILD files are already watched by this plugin,
        which re-runs discovery itself (jbazel.buildFilesChanged -> DiscoveryRefreshJob). Saying yes
        would add a second, competing update path and a notification for every BUILD edit.
     */
    @Override
    public boolean isBuildFile(IResource resource) {
        return false;
    }

    /*
        Nothing, and that is the current behaviour rather than a new decision: until this class
        existed no build support applied to these projects, so jdt.ls's file-change handler skipped
        them entirely. The inherited default would instead refresh the resource tree on every
        watched change - refreshLocal(DEPTH_INFINITE) reaching into linked source folders - which is
        not something to start doing as a side effect of surviving startup.
     */
    @Override
    public boolean fileChanged(IResource resource, CHANGE_TYPE changeType,
            IProgressMonitor monitor) {
        return false;
    }

    @Override
    public boolean hasSpecificDeleteProjectLogic() {
        return true;
    }

    /*
        The projects handed over here are the ones deleteInvalidProjects kept because of the flag
        above - from every build support that sets it, so somebody else's projects can be in the
        list and are left alone.

        Ours are kept when the repository they were generated from is still open, which is the
        invariant jdt.ls's own rule was approximating with the project location. Being outside the
        repository is not staleness: that is where the metadata lives by design. Having no workspace
        folder left is - the folder was removed or renamed, this plugin's pruning will never run for
        that root again, and the projects would linger with links pointing nowhere.
     */
    @Override
    public void deleteInvalidProjects(Collection<IPath> rootPaths,
            ArrayList<IProject> deleteProjectCandidates, IProgressMonitor monitor) {
        for (IProject project : deleteProjectCandidates) {
            ProjectLabels labels = ProjectLabels.read(project);
            if (labels == null || isStillOpen(labels.workspaceRoot(), rootPaths)) {
                continue;
            }
            try {
                project.delete(false, true, monitor);
                BazelLog.info("JBazel: removed " + project.getName()
                        + "; its repository is no longer a workspace folder");
            } catch (CoreException e) {
                BazelLog.exception("JBazel: could not remove " + project.getName(), e);
            }
        }
    }

    private static boolean isStillOpen(String workspaceRoot, Collection<IPath> rootPaths) {
        if (workspaceRoot == null || workspaceRoot.isBlank() || rootPaths == null) {
            // Nothing to compare against - on an empty root list jdt.ls is not importing folders at
            // all, and deleting on that basis would throw away a workspace over a startup detail.
            return true;
        }
        IPath root = new Path(workspaceRoot);
        return rootPaths.stream().anyMatch(rootPath -> rootPath != null && rootPath.isPrefixOf(root));
    }

    @Override
    public String buildToolName() {
        return "Bazel";
    }
}
