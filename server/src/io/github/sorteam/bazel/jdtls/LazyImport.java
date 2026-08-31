package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;

/*
    On-demand provisioning for a java file that no imported project covers.

    With a narrowed import scope a developer will sooner or later open something outside it -
    following a definition into another service, say. Rather than widening the scope for everyone,
    the single owning package is queried and provisioned on the spot, which costs one scoped query
    instead of a repository-wide import.
 */
public final class LazyImport {

    private LazyImport() {
    }

    public static String forFile(String path, IProgressMonitor monitor) {
        if (path == null || path.isBlank()) {
            return "No file given.";
        }
        File file = new File(path);
        BazelSession session = BazelCommandHandler.sessionFor(file);
        if (session == null) {
            return "No bazel workspace owns " + path;
        }
        if (isCovered(session, file)) {
            return "Already imported.";
        }

        if (!session.getSettings().isLazyImport()) {
            return "Outside the configured import scope, and importMode is eager.";
        }

        String packagePath = enclosingPackage(session.getWorkspace().getRoot(), file);
        if (packagePath == null) {
            return "No BUILD file found above " + path;
        }
        if (session.getDiscoveryGate().shouldSkip()) {
            return "JBazel is backing off for "
                    + session.getDiscoveryGate().remainingSeconds() + " s.";
        }

        try {
            List<BazelQuery.Target> targets =
                    new BazelQuery(session.getWorkspace()).javaTargetsIn(monitor, packagePath);
            if (targets.isEmpty()) {
                return "No java target with sources in //" + packagePath;
            }
            List<ProjectGrouping.ProjectSpec> specs = ProjectGrouping.group(targets,
                    session.getSettings().isGroupSourceRoots(),
                    session.getWorkspace().getRoot().getName());
            // prune = false: this is an addition to the imported set, not a redefinition of it.
            List<IJavaProject> projects =
                    new ProjectProvisioner(session).provision(specs, false, monitor);
            projects.forEach(project -> {
                ProjectLabels labels = ProjectLabels.read(project.getProject());
                if (labels != null) {
                    ClasspathResolveJob.enqueue(session, project,
                            labels.mainLabels(), labels.testLabels(), true);
                }
            });
            session.getStore().save();
            return "Imported " + projects.size() + " project(s) for //" + packagePath;
        } catch (CoreException e) {
            if (BazelWorkspace.isServerBusy(e)) {
                session.getDiscoveryGate().recordBusy(e.getMessage());
                return "The bazel server is busy with another command; open the file again in a"
                        + " moment.";
            }
            session.getDiscoveryGate().recordFailure(e.getMessage());
            return "Failed to import //" + packagePath + ": " + e.getMessage();
        }
    }

    private static boolean isCovered(BazelSession session, File file) {
        List<BazelQuery.Target> discovered = session.getStore().peekDiscovery();
        if (discovered == null) {
            return false;
        }
        String relative = relativise(session.getWorkspace().getRoot(), file);
        if (relative == null) {
            return false;
        }
        return discovered.stream()
                .anyMatch(target -> relative.startsWith(target.sourceRoot() + "/"));
    }

    static String relativise(File root, File file) {
        String rootPath = root.getAbsolutePath() + File.separator;
        String filePath = file.getAbsolutePath();
        if (!filePath.startsWith(rootPath)) {
            return null;
        }
        return filePath.substring(rootPath.length()).replace(File.separatorChar, '/');
    }

    /*
        Walks up from the file to the nearest directory holding a BUILD file, which is bazel's own
        definition of the package a source file belongs to.
     */
    static String enclosingPackage(File root, File file) {
        File directory = file.isDirectory() ? file : file.getParentFile();
        while (directory != null && directory.getAbsolutePath().startsWith(root.getAbsolutePath())) {
            if (new File(directory, "BUILD.bazel").isFile()
                    || new File(directory, "BUILD").isFile()) {
                String relative = relativise(root, directory);
                return relative == null ? "" : relative;
            }
            directory = directory.getParentFile();
        }
        return null;
    }
}
