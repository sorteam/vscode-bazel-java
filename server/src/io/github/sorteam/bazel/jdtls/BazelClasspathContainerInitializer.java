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
import org.eclipse.jdt.internal.core.JavaModelManager;

public class BazelClasspathContainerInitializer extends ClasspathContainerInitializer {

    public static final String PLUGIN_ID = "io.github.sorteam.bazel.jdtls";

    /* Kept for source compatibility with callers written against the previous layout. */
    public static final QualifiedName TARGET_LABEL = ProjectLabels.TARGET_LABEL;
    public static final QualifiedName WORKSPACE_ROOT = ProjectLabels.WORKSPACE_ROOT;

    private static final AtomicInteger RESTORED = new AtomicInteger();
    private static final AtomicInteger PLACEHOLDERS = new AtomicInteger();
    private static final AtomicLong SEED_NANOS = new AtomicLong();

    /*
        Must not block, and "must not" has a number attached to it.

        JDT calls this once per project while it restores the java model, and on a warm workspace it
        does that before the language server can even begin to handle initialize: jdt.ls schedules
        JavaCore.initializeAfterLoad from its plugin start and makes handleInitialize wait for it. So
        every millisecond spent here is spent inside the budget the language client gives the whole
        start. That budget is 30 s, after which the client abandons the server and starts a second
        one on the same workspace directory, which is the single most destructive failure this plugin
        has to stay clear of (StartBudget is what keeps it survivable when it happens anyway).

        Measured on a 116-project workspace, from the server's own log: a cold start, where there are
        no projects to restore, answered initialize 3.6 s after receiving it and finished the whole
        start in 19.9 s. The next start - same repository, same machine, projects now on disk -
        answered it 16.7 s after receiving it and finished in 31.5 s, with the container assembly and
        its jar checks accounting for the difference.

        So this hands JDT a container it already has, and nothing else. JDT persists every container
        it was given at the end of a session and restores the entries before the initializers run;
        handing the same object straight back is the one case JavaCore.setClasspathContainer
        short-circuits completely - no classpath delta, no re-resolution, nothing queued for the
        indexer. Anything else would be a change, and a change from "N jars" to anything else makes
        JDT drop the index of every jar that left the classpath and is not shared, then rebuild it
        when the real container arrives a moment later. An empty placeholder does exactly that flip,
        and is used only when there is no previous session to restore from - a cold workspace, or a
        project provisioned after the last save - where JDT had no index to drop anyway.

        The real container - from memory, from the cache the previous session wrote, or from bazel if
        neither has it - is what the resolve job publishes afterwards, off the start path, and it
        skips the publish when what JDT holds already matches.
     */
    @Override
    public void initialize(IPath containerPath, IJavaProject javaProject) throws CoreException {
        ProjectLabels labels = ProjectLabels.read(javaProject.getProject());
        if (labels == null) {
            return;
        }
        long started = System.nanoTime();
        BazelSession session = BazelSession.forRoot(labels.rootFile());

        IClasspathContainer seed = previousSessionContainer(containerPath, javaProject);
        boolean restored = seed != null;
        if (seed == null) {
            seed = BazelClasspathContainer.empty();
        }
        JavaCore.setClasspathContainer(containerPath, new IJavaProject[] { javaProject },
                new IClasspathContainer[] { seed }, new NullProgressMonitor());
        /*
            Only a project JDT could not restore needs resolving from here. For the restored ones the
            import that follows enqueues the whole workspace anyway, and doing it twice meant two
            passes over every container on every start to conclude that none of them had changed.
         */
        if (!restored) {
            ClasspathResolveJob.enqueue(session, javaProject,
                    labels.mainLabels(), labels.testLabels(), false);
        }
        recordSeed(restored, System.nanoTime() - started);
    }

    /*
        What JDT restored from its own variablesAndContainers.dat for this project, or null on a cold
        workspace. A map lookup on the java model manager, and internal API - which is why a model
        manager that no longer has it costs the short-circuit and not the start.
     */
    private static IClasspathContainer previousSessionContainer(IPath containerPath,
            IJavaProject javaProject) {
        try {
            return JavaModelManager.getJavaModelManager()
                    .getPreviousSessionContainer(containerPath, javaProject);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /*
        Reported in batches rather than per project, and reported at all because the cost of this
        method is the cost of the start: if the next warm start is slow again, the log has to say
        whether the time went here or somewhere else, and whether the containers came back from the
        previous session or had to be placeholders. One line per 25 projects is enough to see the
        shape and cheap enough to leave switched on.
     */
    private static void recordSeed(boolean restored, long nanos) {
        long total = SEED_NANOS.addAndGet(nanos);
        int count = (restored ? RESTORED : PLACEHOLDERS).incrementAndGet()
                + (restored ? PLACEHOLDERS : RESTORED).get();
        if (count % 25 == 0) {
            BazelLog.info(String.format(
                    "JBazel: seeded %d classpath container(s) in %d ms total - %d restored from the"
                            + " previous session as-is, %d empty placeholder(s); the resolved ones"
                            + " follow in the background",
                    count, total / 1_000_000L, RESTORED.get(), PLACEHOLDERS.get()));
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
