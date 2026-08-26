package io.github.sorteam.bazel.jdtls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class BazelQuery {

    public record Target(String label, String packagePath, String sourceRoot, List<String> sources) {

        /* Project name used when source-root grouping is switched off. */
        public String projectName() {
            return label.substring(2).replace('/', '.').replace(':', '-');
        }
    }

    private static final String JAVA_KINDS = "java_library|java_binary|java_test rule";

    private final BazelWorkspace workspace;

    public BazelQuery(BazelWorkspace workspace) {
        this.workspace = workspace;
    }

    /*
        Discovery over the configured universe.

        `offline` asks bazel not to touch the network during the loading phase. IDE indexing has no
        business fetching image manifests from a container registry, and on 2026-08-25 exactly that
        turned a transient outage into hours of retries. Once the repository has been fetched at
        least once, everything the java targets need is on disk, so the offline attempt is tried
        first and only falls back to a fetching run when it produces nothing.
     */
    public List<Target> javaTargets(IProgressMonitor monitor, boolean offlineFirst)
            throws CoreException {
        String expression = "kind('" + JAVA_KINDS + "', " + workspace.getSettings().universe() + ")";
        if (!offlineFirst) {
            return run(monitor, expression, false);
        }
        try {
            List<Target> targets = run(monitor, expression, true);
            if (!targets.isEmpty()) {
                return targets;
            }
            BazelLog.info("Bazel: offline discovery found nothing, retrying with fetching enabled");
        } catch (CoreException e) {
            BazelLog.info("Bazel: offline discovery failed (" + e.getMessage()
                    + "), retrying with fetching enabled");
        }
        return run(monitor, expression, false);
    }

    /*
        Single package lookup used when a java file is opened outside the imported scope.
     */
    public List<Target> javaTargetsIn(IProgressMonitor monitor, String packagePath)
            throws CoreException {
        String expression = "kind('" + JAVA_KINDS + "', //" + packagePath + ":*)";
        return run(monitor, expression, true);
    }

    private List<Target> run(IProgressMonitor monitor, String expression, boolean offline)
            throws CoreException {
        Path queryFile = workspace.writeQueryFile(expression);
        List<String> arguments = new ArrayList<>(List.of("query",
                "--query_file=" + queryFile, "--output=xml", "--keep_going", "--noshow_progress"));
        if (offline) {
            arguments.add("--nofetch");
        }
        List<String> lines = workspace.run(monitor, arguments.toArray(String[]::new));
        return parse(String.join("\n", lines), workspace.getRoot());
    }

    static String stripInvalidXmlCharacters(String xml) {
        StringBuilder sanitized = new StringBuilder(xml.length());
        xml.codePoints().forEach(codePoint -> {
            boolean valid = codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                    || (codePoint >= 0x20 && codePoint <= 0x7E)
                    || (codePoint >= 0xA0 && codePoint <= 0xD7FF)
                    || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                    || codePoint >= 0x10000;
            sanitized.appendCodePoint(valid ? codePoint : '?');
        });
        return sanitized.toString();
    }

    static List<Target> parse(String xml) throws CoreException {
        return parse(xml, null);
    }

    static List<Target> parse(String xml, java.io.File workspaceRoot) throws CoreException {
        if (xml.isBlank()) {
            return List.of();
        }
        Element root;
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            root = builder.parse(new ByteArrayInputStream(
                    stripInvalidXmlCharacters(xml).getBytes(StandardCharsets.UTF_8)))
                    .getDocumentElement();
        } catch (Exception e) {
            throw new CoreException(new Status(IStatus.ERROR, "io.github.sorteam.bazel.jdtls",
                    "Unable to parse bazel query output", e));
        }

        List<Target> targets = new ArrayList<>();
        NodeList rules = root.getElementsByTagName("rule");
        for (int i = 0; i < rules.getLength(); i++) {
            Element rule = (Element) rules.item(i);
            String label = rule.getAttribute("name");
            List<String> sources = sourceLabels(rule);
            if (sources.isEmpty()) {
                continue;
            }
            String packagePath = packageOf(label);
            String sourceRoot = rootFromPackages(workspaceRoot, packagePath, sources);
            if (sourceRoot == null) {
                sourceRoot = commonSourceRoot(packagePath, sources);
            }
            if (sourceRoot == null) {
                continue;
            }
            targets.add(new Target(label, packagePath, sourceRoot, sources));
        }
        return targets;
    }

    private static List<String> sourceLabels(Element rule) {
        NodeList lists = rule.getElementsByTagName("list");
        for (int i = 0; i < lists.getLength(); i++) {
            Element list = (Element) lists.item(i);
            if (!"srcs".equals(list.getAttribute("name"))) {
                continue;
            }
            List<String> values = new ArrayList<>();
            NodeList children = list.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                String value = ((Element) child).getAttribute("value");
                if (value.endsWith(".java")) {
                    values.add(value);
                }
            }
            return values;
        }
        return List.of();
    }

    static String packageOf(String label) {
        int colon = label.indexOf(':');
        String path = colon < 0 ? label : label.substring(0, colon);
        return path.startsWith("//") ? path.substring(2) : path;
    }

    private static final int SAMPLED_SOURCES = 50;

    /*
        Source root taken from what the files actually declare, rather than from the shape of the
        path.

        The path is not a reliable guide. //platform/openapi-spring-generator:cli has its sources
        under src/main/java/com/github/..., so the first path segment says the root is src/ and
        every file in it then "declares package com.github... but should declare
        main.java.com.github...". Bazel does not care - javac compiles the list of files it is
        given - but JDT enforces package == directory, so the root has to be the one the packages
        imply: the file's directory with its package suffix removed.

        The winner has to cover at least half the files that were read; below that the layout is
        inconsistent enough that guessing from it is worse than the path heuristic. Files that
        disagree with the winner are the repository's own inconsistencies and are reported by JDT
        as such, which is the correct outcome.
     */
    static String rootFromPackages(java.io.File workspaceRoot, String packagePath,
            List<String> sources) {
        if (workspaceRoot == null) {
            return null;
        }
        Map<String, Integer> candidates = new HashMap<>();
        int read = 0;
        for (String source : sources) {
            if (read >= SAMPLED_SOURCES) {
                break;
            }
            int colon = source.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String relative = source.substring(colon + 1);
            if (!relative.endsWith(".java")) {
                continue;
            }
            String declared = SourceRelocation.declaredPackage(
                    workspaceRoot.toPath().resolve(packagePath).resolve(relative));
            if (declared == null) {
                continue;
            }
            read++;
            int slash = relative.lastIndexOf('/');
            String directory = slash < 0 ? packagePath : packagePath + "/" + relative.substring(0, slash);
            String suffix = declared.replace('.', '/');
            String root;
            if (suffix.isEmpty()) {
                root = directory;
            } else if (directory.equals(suffix)) {
                root = "";
            } else if (directory.endsWith("/" + suffix)) {
                root = directory.substring(0, directory.length() - suffix.length() - 1);
            } else {
                continue;
            }
            candidates.merge(root, 1, Integer::sum);
        }
        if (read == 0) {
            return null;
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : candidates.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        /*
            An empty root means the package spans the whole repository - a file at platform/starter
            declaring "package platform.starter". Linking the repository root as a source folder
            would index everything twice over, so that answer is refused.
         */
        return best == null || best.isEmpty() || bestCount * 2 < read ? null : best;
    }

    static String commonSourceRoot(String packagePath, List<String> sources) {
        Set<String> roots = new LinkedHashSet<>();
        for (String source : sources) {
            int colon = source.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String relative = source.substring(colon + 1);
            int slash = relative.indexOf('/');
            roots.add(slash < 0 ? "" : relative.substring(0, slash));
        }
        if (roots.size() != 1) {
            return null;
        }
        String segment = roots.iterator().next();
        return segment.isEmpty() ? packagePath : packagePath + "/" + segment;
    }
}
