package ch.audienzz.bazel.jdtls;

import java.io.File;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.jdt.core.ClasspathContainerInitializer;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

public class BazelClasspathContainerInitializer extends ClasspathContainerInitializer {

    public static final String PLUGIN_ID = "ch.audienzz.bazel.jdtls";
    public static final QualifiedName TARGET_LABEL = new QualifiedName(PLUGIN_ID, "targetLabel");
    public static final QualifiedName WORKSPACE_ROOT = new QualifiedName(PLUGIN_ID, "workspaceRoot");

    @Override
    public void initialize(IPath containerPath, IJavaProject javaProject) throws CoreException {
        IProject project = javaProject.getProject();
        String label = project.getPersistentProperty(TARGET_LABEL);
        String root = project.getPersistentProperty(WORKSPACE_ROOT);
        if (label == null || root == null) {
            return;
        }

        BazelClasspathCache cache = BazelClasspathCache.getInstance();
        BazelWorkspace workspace = BazelWorkspaceRegistry.forRoot(new File(root));
        List<String> jars = cache.jarsFor(workspace, label, new NullProgressMonitor());
        BazelClasspathContainer container = BazelClasspathContainer.fromJars(
                workspace.executionRoot(new NullProgressMonitor()), jars);

        JavaCore.setClasspathContainer(containerPath, new IJavaProject[] { javaProject },
                new IClasspathContainer[] { container }, new NullProgressMonitor());
    }

    @Override
    public boolean canUpdateClasspathContainer(IPath containerPath, IJavaProject project) {
        return true;
    }

    @Override
    public String getDescription(IPath containerPath, IJavaProject project) {
        return "Bazel Dependencies";
    }
}
