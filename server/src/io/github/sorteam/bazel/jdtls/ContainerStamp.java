package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.Collection;

/*
    Identity of a published classpath container: which jars, in which order, in which state on disk.

    Republishing a container is never free - JDT forgets what it read from every jar behind it and
    indexes them all again, on a large repository ~1.6k jars and over a gigabyte of index writes.
    BuildClasspathJob already guards its own path with a jar fingerprint; this is the same idea at
    the publish site itself, so a refresh triggered by a branch switch republishes only the projects
    whose classpath actually changed.

    Size and modification time are part of the stamp so that a jar rebuilt in place - same path, new
    content - still counts as a change and gets republished and reindexed.
 */
final class ContainerStamp {

    private ContainerStamp() {
    }

    static long of(File executionRoot, Collection<String> mainJars, Collection<String> testJars) {
        long hash = 1125899906842597L;
        hash = mix(hash, executionRoot == null ? "" : executionRoot.getAbsolutePath());
        for (String jar : mainJars) {
            hash = mixFile(hash, executionRoot, jar);
        }
        hash = mix(hash, "||test||");
        for (String jar : testJars) {
            hash = mixFile(hash, executionRoot, jar);
        }
        return hash;
    }

    private static long mixFile(long hash, File executionRoot, String jar) {
        File file = jar.startsWith("/") ? new File(jar) : new File(executionRoot, jar);
        hash = mix(hash, jar);
        hash = 31 * hash + file.lastModified();
        hash = 31 * hash + file.length();
        return hash;
    }

    private static long mix(long hash, String value) {
        return 31 * hash + value.hashCode();
    }
}
