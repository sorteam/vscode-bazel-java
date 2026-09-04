package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.IRuntimeClasspathEntryResolver;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;

/*
    Hands a launch the jars bazel would run with, without putting them in the project's classpath.

    The classpath this plugin publishes comes from the Javac action, so it describes compilation
    exactly - and cannot describe running: runtime_deps are not inputs to javac, so a jdbc driver
    declared there is absent and the application dies on startup while bazel run works.

    Merging the runtime closure into the container fixed the launch and broke two other things: on a
    116-project workspace the classpath went from 67k entries to 106k and the language server's heap
    with it (12 GB), and the editor started accepting code the build rejects, because JDT has one
    classpath per project and no notion of a runtime scope.

    JDT already has the seam for this. A container can contribute different entries to a *runtime*
    classpath through org.eclipse.jdt.launching.runtimeClasspathEntryResolvers - the same mechanism
    m2e uses to keep maven scopes apart - and JavaRuntime asks the resolver whenever it computes a
    launch classpath. So the container stays compile-only, and the runtime jars exist only in the
    answer given here.
 */
public class BazelRuntimeClasspathResolver implements IRuntimeClasspathEntryResolver {

    @Override
    public IRuntimeClasspathEntry[] resolveRuntimeClasspathEntry(IRuntimeClasspathEntry entry,
            ILaunchConfiguration configuration) throws CoreException {
        return resolveRuntimeClasspathEntry(entry, JavaRuntime.getJavaProject(configuration));
    }

    @Override
    public IRuntimeClasspathEntry[] resolveRuntimeClasspathEntry(IRuntimeClasspathEntry entry,
            IJavaProject project) throws CoreException {
        if (project == null) {
            return new IRuntimeClasspathEntry[0];
        }
        ProjectLabels labels = ProjectLabels.read(project.getProject());
        if (labels == null) {
            return new IRuntimeClasspathEntry[0];
        }
        BazelSession session = BazelSession.forRoot(labels.rootFile());

        /*
            The compile jars first, in the order the container publishes them - the launch has to
            keep working exactly as it did - and the runtime-only ones after, deduplicated.
         */
        Set<String> paths = new LinkedHashSet<>();
        for (IClasspathEntry published : containerEntries(project)) {
            paths.add(published.getPath().toOSString());
        }
        File executionRoot = session.getWorkspace().peekExecutionRoot();
        if (executionRoot == null) {
            String stored = session.getStore().peekExecutionRoot();
            executionRoot = stored.isBlank() ? null : new File(stored);
        }
        int added = 0;
        for (String label : labels.allLabels()) {
            for (String jar : session.getCache().peekRuntimeJars(label)) {
                File file = BazelClasspathContainer.jarFile(executionRoot, jar);
                if (file.isFile() && paths.add(file.getAbsolutePath())) {
                    added++;
                }
            }
        }
        if (added > 0) {
            BazelLog.info(String.format("JBazel: %s launches with %d runtime_deps jar(s) added to"
                    + " its %d compile jar(s)", project.getElementName(), added,
                    paths.size() - added));
        }

        List<IRuntimeClasspathEntry> resolved = new ArrayList<>(paths.size());
        for (String path : paths) {
            IRuntimeClasspathEntry archive =
                    JavaRuntime.newArchiveRuntimeClasspathEntry(new Path(path));
            archive.setClasspathProperty(IRuntimeClasspathEntry.USER_CLASSES);
            resolved.add(archive);
        }
        return resolved.toArray(IRuntimeClasspathEntry[]::new);
    }

    /* Whatever the container currently holds, or nothing if JDT has not resolved it yet. */
    private static IClasspathEntry[] containerEntries(IJavaProject project) {
        try {
            IClasspathContainer container = JavaCore.getClasspathContainer(
                    BazelClasspathContainer.CONTAINER_PATH, project);
            return container == null ? new IClasspathEntry[0] : container.getClasspathEntries();
        } catch (CoreException e) {
            return new IClasspathEntry[0];
        }
    }

    /* Not a JRE container, so there is no vm install behind it. */
    @Override
    public IVMInstall resolveVMInstall(IClasspathEntry entry) throws CoreException {
        return null;
    }
}
