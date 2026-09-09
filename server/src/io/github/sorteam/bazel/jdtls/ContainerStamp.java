package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.Collection;

/*
    Identity of a published classpath container: which jars, in which order, with which sources
    attached.

    Republishing a container is never free - JDT forgets what it read from every jar behind it and
    indexes them all again, on a large repository ~1.6k jars and over a gigabyte of index writes.
    BuildClasspathJob already guards its own path with a jar fingerprint; this is the same idea at
    the publish site itself, so a refresh triggered by a branch switch republishes only the projects
    whose classpath actually changed.

    The source attachment is part of the stamp because it is part of what the container hands JDT,
    and source jars downloaded after the fact ("JBazel: Fetch Library Sources") change nothing else
    about the classpath. Leaving them out is what would make freshly fetched sources invisible until
    the next window reload.

    Jar size and modification time used to be in here as well - existence still is, since that is
    what decides whether an entry appears at all - so that a jar rebuilt in place, same path and new
    content, would republish and be re-indexed. It does republish. It is not re-indexed, and the
    classes in it stay invisible: JDT compares classpath entries, finds the same paths and fires no
    delta, so the republish was pure cost. Content changes reach JDT through ExternalArchives
    instead, that being the only thing that makes it re-read an external jar, and the stamp answers
    only the question it can answer - is this a different container.

    Stamps persisted by earlier versions cannot match this one, and that costs nothing: the publish
    site asks JDT what it is holding before it acts on a mismatch, so a stamp left over from another
    format is corrected in place rather than paid for with a reindex.
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
        /*
            Resolved exactly as the container resolves it, lombok substitution included, so the
            stamp describes the file that actually goes on the classpath rather than the one aquery
            named. Which file that is can change on its own: an ABI jar whose real counterpart has
            since been built resolves elsewhere, and that is a different container.
         */
        File file = BazelClasspathContainer.jarFile(executionRoot, jar);
        hash = mix(hash, jar);
        hash = mix(hash, file.getAbsolutePath());
        /*
            Whether the jar is on disk decides whether it is an entry at all: aquery reports what a
            Javac action would consume, and the container drops what does not exist yet. So a jar
            that a build has just produced is a different container, and one that vanished is too -
            without this, the first build on a fresh clone would resolve nothing into the classpath.
         */
        hash = mix(hash, file.isFile() ? "|present|" : "|missing|");

        File sources = BazelClasspathContainer.sourcesFor(file);
        hash = mix(hash, sources == null ? "|no-sources|" : sources.getAbsolutePath());
        return hash;
    }

    private static long mix(long hash, String value) {
        return 31 * hash + value.hashCode();
    }
}
