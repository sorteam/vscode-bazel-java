package ch.audienzz.bazel.jdtls;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.QualifiedName;

/*
    The bazel identity of an Eclipse project, kept in persistent properties so it survives a restart
    and is available to the classpath container initializer, which runs long before any import.

    Grouping main and test targets into one project means a project now owns a list of labels rather
    than a single one; the properties store them comma separated. TARGET_LABEL keeps its name so
    projects written by earlier versions still resolve.
 */
public record ProjectLabels(String workspaceRoot, List<String> mainLabels,
        List<String> testLabels) {

    public static final QualifiedName TARGET_LABEL =
            new QualifiedName(BazelClasspathContainerInitializer.PLUGIN_ID, "targetLabel");
    public static final QualifiedName TEST_TARGET_LABEL =
            new QualifiedName(BazelClasspathContainerInitializer.PLUGIN_ID, "testTargetLabel");
    public static final QualifiedName WORKSPACE_ROOT =
            new QualifiedName(BazelClasspathContainerInitializer.PLUGIN_ID, "workspaceRoot");

    public static ProjectLabels read(IProject project) {
        try {
            String root = project.getPersistentProperty(WORKSPACE_ROOT);
            String main = project.getPersistentProperty(TARGET_LABEL);
            if (root == null || main == null || main.isBlank()) {
                return null;
            }
            return new ProjectLabels(root, split(main),
                    split(project.getPersistentProperty(TEST_TARGET_LABEL)));
        } catch (CoreException e) {
            return null;
        }
    }

    /*
        Only writes when a value actually changes: persistent properties go through the resource
        tree, and 223 projects times three unconditional writes is a measurable part of the 22.7 s
        provisioning phase.
     */
    public void writeTo(IProject project) throws CoreException {
        set(project, WORKSPACE_ROOT, workspaceRoot);
        set(project, TARGET_LABEL, String.join(",", mainLabels));
        set(project, TEST_TARGET_LABEL, String.join(",", testLabels));
    }

    private static void set(IProject project, QualifiedName name, String value)
            throws CoreException {
        if (!value.equals(project.getPersistentProperty(name))) {
            project.setPersistentProperty(name, value);
        }
    }

    public List<String> allLabels() {
        List<String> all = new ArrayList<>(mainLabels);
        all.addAll(testLabels);
        return all;
    }

    public File rootFile() {
        return new File(workspaceRoot);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                parts.add(part.strip());
            }
        }
        return List.copyOf(parts);
    }
}
