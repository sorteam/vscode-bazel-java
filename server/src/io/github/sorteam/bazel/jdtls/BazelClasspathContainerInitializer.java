package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.jdt.core.ClasspathContainerInitializer;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

public class BazelClasspathContainerInitializer extends ClasspathContainerInitializer {

    public static final String PLUGIN_ID = "io.github.sorteam.bazel.jdtls";

    /* Kept for source compatibility with callers written against the previous layout. */
    public static final QualifiedName TARGET_LABEL = ProjectLabels.TARGET_LABEL;
    public static final QualifiedName WORKSPACE_ROOT = ProjectLabels.WORKSPACE_ROOT;

    /*
        Must not block. JDT calls this while restoring the java model, once per project, and the
        previous implementation ran a bazel aquery here - which is how a restart cost ~40 s of bazel
        before the importer had even been asked whether it applies.

        The container is published immediately from whatever is already known (memory, then the
        cache written by the previous session), and anything unresolved is handed to a background
        job. An empty container is a deliberate placeholder: JDT treats a missing one as a broken
        classpath, an empty one merely as one with no libraries yet.
     */
    @Override
    public void initialize(IPath containerPath, IJavaProject javaProject) throws CoreException {
        ProjectLabels labels = ProjectLabels.read(javaProject.getProject());
        if (labels == null) {
            return;
        }

        BazelSession session = BazelSession.forRoot(labels.rootFile());
        File executionRoot = knownExecutionRoot(session);

        Set<String> mainJars = new LinkedHashSet<>();
        Set<String> testJars = new LinkedHashSet<>();
        boolean complete = collect(session, labels.mainLabels(), mainJars)
                & collect(session, labels.testLabels(), testJars);
        testJars.removeAll(mainJars);

        BazelClasspathContainer container = complete && executionRoot != null
                ? BazelClasspathContainer.fromJars(executionRoot, mainJars, testJars)
                : BazelClasspathContainer.empty();

        JavaCore.setClasspathContainer(containerPath, new IJavaProject[] { javaProject },
                new IClasspathContainer[] { container }, new NullProgressMonitor());
        session.getReport().countJars(container.getResolvedCount(), container.getMissingCount());

        if (!complete || executionRoot == null) {
            ClasspathResolveJob.enqueue(session, javaProject,
                    labels.mainLabels(), labels.testLabels(), false);
        }
    }

    private static boolean collect(BazelSession session, List<String> labels, Set<String> into) {
        boolean complete = true;
        for (String label : labels) {
            List<String> jars = session.getCache().peek(label);
            if (jars == null) {
                complete = false;
                continue;
            }
            into.addAll(jars);
        }
        return complete;
    }

    /*
        The execution root is needed to turn the relative paths aquery reports into absolute ones.
        Asking bazel for it is a process launch, so the value persisted by the previous session is
        used when it still points at a real directory.
     */
    private static File knownExecutionRoot(BazelSession session) {
        File cached = session.getWorkspace().peekExecutionRoot();
        if (cached != null) {
            return cached;
        }
        String stored = session.getStore().peekExecutionRoot();
        if (!stored.isBlank() && new File(stored).isDirectory()) {
            File root = new File(stored);
            session.getWorkspace().setExecutionRoot(root);
            return root;
        }
        return null;
    }

    @Override
    public boolean canUpdateClasspathContainer(IPath containerPath, IJavaProject project) {
        return true;
    }

    @Override
    public void requestClasspathContainerUpdate(IPath containerPath, IJavaProject javaProject,
            IClasspathContainer suggestedUpdate) throws CoreException {
        ProjectLabels labels = ProjectLabels.read(javaProject.getProject());
        if (labels == null) {
            return;
        }
        BazelSession session = BazelSession.forRoot(labels.rootFile());
        ClasspathResolveJob.enqueue(session, javaProject,
                labels.mainLabels(), labels.testLabels(), true);
    }

    @Override
    public String getDescription(IPath containerPath, IJavaProject project) {
        return "Bazel Dependencies";
    }
}
