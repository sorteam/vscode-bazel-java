package io.github.sorteam.bazel.jdtls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
    Where each generated project sits on disk when the repository layout is asked for, and which
    projects cannot have one.

    Why the layout exists. Parts of the java tooling assume a project's location is inside the
    workspace folder, and silently skip the projects where it is not:

    - the Spring Tools classpath bridge computes a source folder as <project location> + <entry> and
      hands its indexer a directory that does not exist, so nothing is indexed at all - no beans, no
      endpoints, no symbols. Measured on a 116-project workspace: 228 of 244 source entries pointed
      at directories that do not exist. This is the reason the layout exists;
    - jdt.ls deletes such projects at startup (StandardProjectsManager.deleteInvalidProjects), which
      is what BazelBuildSupport works around;
    - vscode-java-debug filters its main-class search by project location too
      (ResolveMainClassHandler.resolveMainClassUnderPaths). The Spring Boot Dashboard turns out not
      to depend on that - it asks with the project's own location, not the workspace folder, and what
      kept it empty was header jar names on the classpath (BazelClasspathContainer.jarFile).

    Patching all three is a losing race. The metadata layout stays the default because it writes
    nothing into the working copy; this one satisfies the assumption instead, and the price is that a
    project directory is a directory of the repository.

    The rules are all one rule: no project may become the parent of another. A project owns its whole
    directory tree in the Eclipse resource model, so overlapping locations mean one project's sources
    are also another's - and a project rooted at the repository root would own everything, including
    every other project.
 */
public final class ProjectLocations {

    /*
        directories: project name -> repository-relative directory. keptInMetadata: the projects that
        must stay where they are, so the caller can say how many and not just fall back silently.
     */
    public record Layout(Map<String, String> directories, List<String> keptInMetadata) {
    }

    private ProjectLocations() {
    }

    public static Layout inRepository(List<ProjectGrouping.ProjectSpec> specs) {
        Map<String, String> candidates = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        for (ProjectGrouping.ProjectSpec spec : specs) {
            String directory = directoryFor(spec);
            if (directory == null) {
                rejected.add(spec.name());
            } else {
                candidates.put(spec.name(), directory);
            }
        }

        Map<String, String> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : candidates.entrySet()) {
            if (clearOfOthers(entry, candidates)) {
                accepted.put(entry.getKey(), entry.getValue());
            } else {
                rejected.add(entry.getKey());
            }
        }
        return new Layout(Map.copyOf(accepted), List.copyOf(rejected));
    }

    /*
        The bazel package the project's targets live in - not the common parent of its source
        folders, which for a single source root would be the java directory itself and would make
        the project root a source folder. The package is also what a developer means by "this
        project's directory".

        Null when there is no such directory to use:

        - a project whose labels sit in the repository root package, which would own the whole tree;
        - a project whose labels span packages that only meet at the root - grouping can put source
          roots from unrelated trees into one project;
        - a project with a source folder outside that package, so the directory would not contain
          its own sources - or one whose source folder *is* the package directory, which would make
          the project root a source folder with the linked output folder inside it;
        - a project with no labels or no sources at all.
     */
    private static String directoryFor(ProjectGrouping.ProjectSpec spec) {
        List<String> labels = new ArrayList<>(spec.mainLabels());
        labels.addAll(spec.testLabels());
        if (labels.isEmpty() || spec.sourceFolders().isEmpty()) {
            return null;
        }
        String directory = null;
        for (String label : labels) {
            String packagePath = packageOf(label);
            if (packagePath == null || packagePath.isEmpty()) {
                return null;
            }
            directory = directory == null ? packagePath : sharedPrefix(directory, packagePath);
            if (directory.isEmpty()) {
                return null;
            }
        }
        for (ProjectGrouping.SourceFolder source : spec.sourceFolders()) {
            if (!inside(normalise(source.path()), directory)) {
                return null;
            }
        }
        return directory;
    }

    /*
        Kept when no other candidate is the same directory or lives underneath it. The outer project
        is the one that loses: a directory holding another project's directory would own its sources
        too, while the inner one is the more specific project and stays where it belongs. Two
        projects claiming the same directory both lose - there is nothing to choose between them.
     */
    private static boolean clearOfOthers(Map.Entry<String, String> candidate,
            Map<String, String> candidates) {
        for (Map.Entry<String, String> other : candidates.entrySet()) {
            if (other.getKey().equals(candidate.getKey())) {
                continue;
            }
            if (other.getValue().equals(candidate.getValue())
                    || inside(other.getValue(), candidate.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static String packageOf(String label) {
        if (label == null || !label.startsWith("//")) {
            return null;
        }
        String path = label.substring(2);
        int colon = path.indexOf(':');
        return normalise(colon < 0 ? path : path.substring(0, colon));
    }

    private static String sharedPrefix(String one, String other) {
        String[] left = one.split("/");
        String[] right = other.split("/");
        List<String> shared = new ArrayList<>();
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            if (!left[i].equals(right[i])) {
                break;
            }
            shared.add(left[i]);
        }
        return String.join("/", shared);
    }

    /* Strictly inside: the same directory is not "inside" itself. */
    private static boolean inside(String path, String directory) {
        return path.startsWith(directory + "/");
    }

    private static String normalise(String path) {
        String normalised = path.replace('\\', '/');
        while (normalised.startsWith("./")) {
            normalised = normalised.substring(2);
        }
        while (normalised.endsWith("/")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        return normalised;
    }
}
