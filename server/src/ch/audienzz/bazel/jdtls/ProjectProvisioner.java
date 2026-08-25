package ch.audienzz.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;

public class ProjectProvisioner {

    public static final String SOURCE_FOLDER = "src";

    private final BazelWorkspace workspace;

    private int created;
    private int updated;
    private int unchanged;

    public ProjectProvisioner(BazelWorkspace workspace) {
        this.workspace = workspace;
    }

    public int getCreated() {
        return created;
    }

    public int getUpdated() {
        return updated;
    }

    public int getUnchanged() {
        return unchanged;
    }

    public List<IProject> provision(List<BazelQuery.Target> targets, IProgressMonitor monitor)
            throws CoreException {
        SubMonitor progress = SubMonitor.convert(monitor, targets.size());
        List<IProject> projects = new ArrayList<>();
        for (BazelQuery.Target target : targets) {
            if (progress.isCanceled()) {
                break;
            }
            try {
                projects.add(provisionOne(target, progress.split(1)));
            } catch (CoreException e) {
                JavaLanguageServerPlugin.logException(
                        "Bazel: failed to provision " + target.label(), e);
            }
        }
        return projects;
    }

    private IProject provisionOne(BazelQuery.Target target, IProgressMonitor monitor)
            throws CoreException {
        SubMonitor progress = SubMonitor.convert(monitor, 4);

        IWorkspace eclipseWorkspace = ResourcesPlugin.getWorkspace();
        IProject project = eclipseWorkspace.getRoot().getProject(target.projectName());
        boolean fresh = !project.exists();
        if (fresh) {
            IProjectDescription description =
                    eclipseWorkspace.newProjectDescription(target.projectName());
            description.setNatureIds(new String[] { JavaCore.NATURE_ID });
            project.create(description, progress.split(1));
            created++;
        } else {
            progress.worked(1);
        }
        if (!project.isOpen()) {
            project.open(progress.split(1));
        } else {
            progress.worked(1);
        }

        IFolder link = project.getFolder(SOURCE_FOLDER);
        File sourceRoot = new File(workspace.getRoot(), target.sourceRoot());
        IPath location = new Path(sourceRoot.getAbsolutePath());
        if (!link.exists() || !location.equals(link.getLocation())) {
            link.createLink(location, IResource.REPLACE | IResource.ALLOW_MISSING_LOCAL,
                    progress.split(1));
        } else {
            progress.worked(1);
        }

        if (!target.label().equals(project.getPersistentProperty(
                BazelClasspathContainerInitializer.TARGET_LABEL))) {
            project.setPersistentProperty(
                    BazelClasspathContainerInitializer.TARGET_LABEL, target.label());
        }
        String root = workspace.getRoot().getAbsolutePath();
        if (!root.equals(project.getPersistentProperty(
                BazelClasspathContainerInitializer.WORKSPACE_ROOT))) {
            project.setPersistentProperty(
                    BazelClasspathContainerInitializer.WORKSPACE_ROOT, root);
        }

        IJavaProject javaProject = JavaCore.create(project);
        IClasspathEntry[] desired = classpath(link);
        IPath output = outputLocation(project);

        if (!fresh
                && Arrays.equals(desired, javaProject.getRawClasspath())
                && output.equals(javaProject.getOutputLocation())) {
            unchanged++;
            progress.worked(1);
            return project;
        }

        javaProject.setRawClasspath(desired, output, progress.split(1));
        if (!fresh) {
            updated++;
        }
        return project;
    }

    private IClasspathEntry[] classpath(IFolder sourceLink) {
        return new IClasspathEntry[] {
                JavaCore.newSourceEntry(sourceLink.getFullPath()),
                JavaRuntime.getDefaultJREContainerEntry(),
                JavaCore.newContainerEntry(BazelClasspathContainer.CONTAINER_PATH),
        };
    }

    private IPath outputLocation(IProject project) {
        return project.getFullPath().append("bin");
    }
}
