package io.github.sorteam.bazel.jdtls;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final AtomicInteger PLACEHOLDERS = new AtomicInteger();
    private static final AtomicLong PLACEHOLDER_NANOS = new AtomicLong();

    /*
        Must not block, and "must not" has a number attached to it.

        JDT calls this once per project while it restores the java model, and on a warm workspace it
        does that inside the language server's initialize request - so every millisecond spent here
        is spent inside the budget the language client gives the whole start. That budget is 30 s,
        after which the client abandons the server and starts a second one on the same workspace
        directory, which is the single most destructive failure this plugin has to stay clear of.

        Measured on a 116-project workspace, from the server's own log: a cold start, where there are
        no projects to restore, answered initialize 3.6 s after receiving it and finished the whole
        start in 19.9 s. The next start - same repository, same machine, projects now on disk -
        answered it 16.7 s after receiving it and finished in 31.5 s. It missed by a second and a
        half, and everything that followed came from that. The 13 s of difference is the model
        restore and the containers built with it, and none of it has to happen before the client is
        told the server is up.

        So this hands JDT an empty container and nothing else. Empty is a deliberate placeholder,
        not a failure: JDT treats a *missing* container as a broken classpath, an empty one merely as
        one with no libraries yet. The real container - from memory, or from the cache the previous
        session wrote, or from bazel if neither has it - is published by the resolve job a moment
        later, which is the same path a cold import already used and the same single publish per
        project either way.
     */
    @Override
    public void initialize(IPath containerPath, IJavaProject javaProject) throws CoreException {
        ProjectLabels labels = ProjectLabels.read(javaProject.getProject());
        if (labels == null) {
            return;
        }
        long started = System.nanoTime();
        BazelSession session = BazelSession.forRoot(labels.rootFile());

        JavaCore.setClasspathContainer(containerPath, new IJavaProject[] { javaProject },
                new IClasspathContainer[] { BazelClasspathContainer.empty() },
                new NullProgressMonitor());
        ClasspathResolveJob.enqueue(session, javaProject,
                labels.mainLabels(), labels.testLabels(), false);
        recordPlaceholder(System.nanoTime() - started);
    }

    /*
        Reported in batches rather than per project, and reported at all because the cost of this
        method is the cost of the start: if the next warm start is slow again, the log has to say
        whether the time went here or somewhere else. One line per 25 projects is enough to see the
        shape and cheap enough to leave switched on.
     */
    private static void recordPlaceholder(long nanos) {
        long total = PLACEHOLDER_NANOS.addAndGet(nanos);
        int count = PLACEHOLDERS.incrementAndGet();
        if (count % 25 == 0) {
            BazelLog.info(String.format(
                    "JBazel: handed JDT %d placeholder container(s) in %d ms total; the resolved"
                            + " ones follow in the background", count, total / 1_000_000L));
        }
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
        return "JBazel Dependencies";
    }
}
