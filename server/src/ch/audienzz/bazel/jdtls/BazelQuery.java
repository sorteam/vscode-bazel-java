package ch.audienzz.bazel.jdtls;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        public String projectName() {
            return label.substring(2).replace('/', '.').replace(':', '-');
        }
    }

    private static final String JAVA_RULES =
            "kind('java_library|java_binary|java_test rule', //...)";

    private final BazelWorkspace workspace;

    public BazelQuery(BazelWorkspace workspace) {
        this.workspace = workspace;
    }

    public List<Target> javaTargets(IProgressMonitor monitor) throws CoreException {
        List<String> lines = workspace.run(monitor, "query", JAVA_RULES,
                "--output=xml", "--keep_going", "--noshow_progress");
        return parse(String.join("\n", lines));
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
        Element root;
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            root = builder.parse(new ByteArrayInputStream(
                    stripInvalidXmlCharacters(xml).getBytes(StandardCharsets.UTF_8)))
                    .getDocumentElement();
        } catch (Exception e) {
            throw new CoreException(new Status(IStatus.ERROR, "ch.audienzz.bazel.jdtls",
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
            String sourceRoot = commonSourceRoot(packagePath, sources);
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

    private static String packageOf(String label) {
        int colon = label.indexOf(':');
        String path = colon < 0 ? label : label.substring(0, colon);
        return path.startsWith("//") ? path.substring(2) : path;
    }

    private static String commonSourceRoot(String packagePath, List<String> sources) {
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

    public static Map<String, List<Target>> byProjectName(List<Target> targets) {
        Map<String, List<Target>> grouped = new LinkedHashMap<>();
        for (Target target : targets) {
            grouped.computeIfAbsent(target.projectName(), key -> new ArrayList<>()).add(target);
        }
        return grouped;
    }
}
