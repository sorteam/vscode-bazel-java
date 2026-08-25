package ch.audienzz.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;

public class BazelClasspathContainer implements IClasspathContainer {

    public static final String CONTAINER_ID = "ch.audienzz.bazel.jdtls.BAZEL_CONTAINER";
    public static final IPath CONTAINER_PATH = new Path(CONTAINER_ID);

    private final IClasspathEntry[] entries;

    private BazelClasspathContainer(List<IClasspathEntry> entries) {
        this.entries = entries.toArray(IClasspathEntry[]::new);
    }

    public static BazelClasspathContainer fromJars(File executionRoot, List<String> jars) {
        List<IClasspathEntry> resolved = new ArrayList<>();
        if (jars != null) {
            for (String jar : jars) {
                File file = new File(executionRoot, jar);
                if (!file.isFile()) {
                    continue;
                }
                IPath jarPath = new Path(file.getAbsolutePath());
                resolved.add(JavaCore.newLibraryEntry(jarPath, sourcesFor(file), null));
            }
        }
        return new BazelClasspathContainer(resolved);
    }

    private static IPath sourcesFor(File jar) {
        String name = jar.getName();
        String base = name.startsWith("header_") ? name.substring("header_".length()) : name;
        File candidate = new File(jar.getParentFile(),
                base.substring(0, base.length() - ".jar".length()) + "-sources.jar");
        return candidate.isFile() ? new Path(candidate.getAbsolutePath()) : null;
    }

    @Override
    public IClasspathEntry[] getClasspathEntries() {
        return entries;
    }

    @Override
    public String getDescription() {
        return "Bazel Dependencies";
    }

    @Override
    public int getKind() {
        return IClasspathContainer.K_APPLICATION;
    }

    @Override
    public IPath getPath() {
        return CONTAINER_PATH;
    }
}
