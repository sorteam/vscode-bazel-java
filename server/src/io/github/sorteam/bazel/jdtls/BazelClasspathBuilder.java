package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

/*
    Asks bazel to rebuild a project's outputs when that project's own files change, and at no other
    time.

    The question "did anything change that this project is built from" is not one to answer with a
    guess. The platform already answers it: a builder registered on a project is called with a
    resource delta rooted at that project, reflecting the net effect of every change since this
    builder last ran on it. A commit that touches only files outside the project produces no delta
    for it and no build - which is the entire point, and the reason the alternatives were wrong. A
    trigger on "the repository moved to another revision" rebuilds the back end because someone
    changed the front end; a trigger on "the editor started" rebuilds everything for nothing.

    Why a build is needed at all, given the classpath is correct: an output built from an earlier
    state of the sources keeps its path and its name, so the container is byte-identical and there
    is nothing to republish. What differs is the content, and it shows wherever the repository
    generates code into those outputs - a type added to an interface description is simply not in
    the archive the editor reads, and every reference to it is an unresolved type that no amount of
    re-resolving can clear. Only building produces it.

    The build itself does not happen here. This method runs inside the platform's build cycle,
    holding the workspace build lock, and a bazel invocation is seconds at best and minutes on a
    cold analysis cache; holding that lock for minutes stops every other builder and every workspace
    operation in the process. So the delta decides, and the work is handed to a background job that
    coalesces the labels from however many projects were touched into one invocation.
 */
public class BazelClasspathBuilder extends IncrementalProjectBuilder {

    public static final String BUILDER_ID = "io.github.sorteam.bazel.jdtls.classpathBuilder";

    @Override
    protected IProject[] build(int kind, Map<String, String> args, IProgressMonitor monitor)
            throws CoreException {
        IProject project = getProject();
        ProjectLabels labels = ProjectLabels.read(project);
        if (labels == null || labels.allLabels().isEmpty()) {
            return null;
        }
        BazelSession session = BazelSession.forRoot(labels.rootFile());
        if (!session.getSettings().isBuildOnImport()) {
            return null;
        }
        /*
            A full build carries no delta by definition, and the platform's own advice for that case
            is to treat everything as changed. Here that means this project's labels - not the
            workspace's, which is what keeps even a full build scoped to the project asked about.
         */
        IResourceDelta delta = kind == FULL_BUILD ? null : getDelta(project);
        if (delta == null) {
            BuildClasspathJob.enqueue(session, labels.allLabels(),
                    BuildClasspathJob.Reason.fullBuild(project.getName()));
            return null;
        }
        IResource changed = firstChangedInput(delta);
        if (changed != null) {
            BuildClasspathJob.enqueue(session, labels.allLabels(),
                    BuildClasspathJob.Reason.fileChanged(project.getName(),
                            repositoryPath(changed, labels.rootFile())));
        }
        return null;
    }

    /*
        The first file in the delta that the build reads, or null when there is none - the file
        doubles as the example the status names when it says why a build is running. Two things are
        deliberately ignored: derived resources, which are the compiler's own output and would make
        every build trigger the next one; and the output folders, which are derived but not always
        marked so early enough to rely on it.
     */
    private static IResource firstChangedInput(IResourceDelta delta) throws CoreException {
        IResource[] found = new IResource[1];
        delta.accept(visited -> {
            if (found[0] != null) {
                return false;
            }
            IResource resource = visited.getResource();
            if (resource.isDerived(IResource.CHECK_ANCESTORS)) {
                return false;
            }
            if (resource.getType() == IResource.FOLDER && isOutputFolder(resource.getName())) {
                return false;
            }
            if (resource.getType() == IResource.FILE) {
                found[0] = resource;
            }
            return true;
        });
        return found[0];
    }

    /*
        Where the file is in the repository, which is how the developer knows it. In the metadata
        layout the path inside the project goes through a linked folder whose name the provisioner
        made up, and names nothing anyone would recognise.
     */
    private static String repositoryPath(IResource resource, File root) {
        IPath location = resource.getLocation();
        if (location == null) {
            return resource.getProjectRelativePath().toString();
        }
        String path = location.toFile().getAbsolutePath();
        String prefix = root.getAbsolutePath() + File.separator;
        return path.startsWith(prefix)
                ? path.substring(prefix.length()).replace(File.separatorChar, '/')
                : path;
    }

    /* The names this plugin gives a project's class output; see ProjectProvisioner. */
    static boolean isOutputFolder(String name) {
        return "bin".equals(name) || "bin-test".equals(name);
    }
}
