package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.ls.core.internal.IDelegateCommandHandler;

/*
    Commands the VS Code side can invoke through java.execute.workspaceCommand.

    Settings are read from disk rather than pushed from the client (see BazelSettings), but anything
    that happens after startup - refreshing, reporting, building the jars a classpath points at,
    pulling in a file that falls outside the imported scope - arrives here.
 */
public class BazelCommandHandler implements IDelegateCommandHandler {

    /*
        The jbazel. prefix rather than bazel.: these ids live in one namespace shared by every jdt.ls
        bundle in the language server, and "bazel" is exactly what another bazel extension would
        reach for. The VS Code side is prefixed for the same reason - see the command titles.
     */
    public static final String REFRESH = "jbazel.refreshClasspath";
    public static final String REPORT = "jbazel.showImportReport";
    public static final String BUILD_CLASSPATH = "jbazel.buildClasspath";
    public static final String FETCH_SOURCES = "jbazel.fetchLibrarySources";
    public static final String DOCTOR = "jbazel.doctor";
    public static final String IMPORT_FILE = "jbazel.importFile";
    public static final String STATUS = "jbazel.status";
    public static final String BUILD_FILES_CHANGED = "jbazel.buildFilesChanged";

    @Override
    public Object executeCommand(String commandId, List<Object> arguments,
            IProgressMonitor monitor) throws Exception {
        switch (commandId) {
            case REFRESH:
                return refresh();
            case REPORT:
                return report();
            case BUILD_CLASSPATH:
                return BuildClasspathJob.start(sessions());
            case FETCH_SOURCES:
                return FetchSourcesJob.start(sessions());
            case DOCTOR:
                return doctor();
            case IMPORT_FILE:
                return LazyImport.forFile(stringArgument(arguments, 0), monitor);
            case STATUS:
                return status();
            case BUILD_FILES_CHANGED:
                return buildFilesChanged();
            default:
                throw new UnsupportedOperationException("Unknown command " + commandId);
        }
    }

    private static Object refresh() {
        int refreshed = 0;
        for (BazelSession session : sessions()) {
            session.refresh(true);
            DiscoveryRefreshJob.scheduleFor(session);
            refreshed++;
        }
        return refreshed == 0
                ? "No bazel workspace is imported."
                : "Refreshing " + refreshed + " bazel workspace(s); the classpath will update"
                        + " in the background.";
    }

    /*
        Sent by the extension when a BUILD, .bzl or MODULE.bazel file changed. It also lifts the
        backoff window: the edit is very often the fix for whatever the import was failing on, and
        making a developer wait out five minutes after fixing their own BUILD file would be absurd.
     */
    private static Object buildFilesChanged() {
        int affected = 0;
        for (BazelSession session : sessions()) {
            session.getDiscoveryGate().reset();
            session.getClasspathGate().reset();
            DiscoveryRefreshJob.scheduleFor(session, true);
            affected++;
        }
        return affected;
    }

    private static Object doctor() {
        if (sessions().isEmpty()) {
            return "No bazel workspace is imported.";
        }
        StringBuilder out = new StringBuilder("JBazel doctor\n\n");
        for (BazelSession session : sessions()) {
            out.append(Doctor.render(session));
        }
        return out.toString();
    }

    private static Object report() {
        if (sessions().isEmpty()) {
            return "No bazel workspace is imported.";
        }
        StringBuilder out = new StringBuilder();
        for (BazelSession session : sessions()) {
            out.append("Workspace ").append(session.getWorkspace().getRoot()).append('\n');
            out.append(session.getReport().render());
            out.append("  scope              : ").append(session.getSettings().universe())
                    .append('\n');
            out.append("  cached classpaths  : ")
                    .append(session.getStore().cachedLabelCount()).append('\n');
            out.append("  discovery gate     : ")
                    .append(session.getDiscoveryGate().describe()).append('\n');
            out.append("  classpath gate     : ")
                    .append(session.getClasspathGate().describe()).append('\n');
            out.append("  output base        : ")
                    .append(session.getSettings().hasDedicatedOutputBase()
                            ? session.getWorkspace()
                                    .outputBaseDirectory(session.getSettings()).toString()
                            : "shared with the terminal")
                    .append('\n');
        }
        return out.toString();
    }

    private static Object status() {
        Map<String, Object> status = new LinkedHashMap<>();
        for (BazelSession session : sessions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("scope", session.getSettings().universe());
            entry.put("discovery", session.getDiscoveryGate().describe());
            entry.put("classpath", session.getClasspathGate().describe());
            entry.put("backoffSeconds", Math.max(
                    session.getDiscoveryGate().remainingSeconds(),
                    session.getClasspathGate().remainingSeconds()));
            entry.put("serverBusy", session.getWorkspace().wasBusyRecently()
                    || session.getDiscoveryGate().isBusyWaiting()
                    || session.getClasspathGate().isBusyWaiting());
            entry.put("missingJars", session.getReport().getMissingJars());
            entry.put("classpathJars", session.getReport().getResolvedJars());
            entry.put("jarsWithSources", session.getReport().getJarsWithSources());
            entry.put("convenienceSymlinks", session.getWorkspace().convenienceSymlinks());
            status.put(session.getWorkspace().getRoot().getAbsolutePath(), entry);
        }
        return status;
    }

    static Set<BazelSession> sessions() {
        return new LinkedHashSet<>(BazelSession.all());
    }

    static BazelSession sessionFor(File file) {
        BazelSession best = null;
        int bestLength = -1;
        for (BazelSession session : BazelSession.all()) {
            String root = session.getWorkspace().getRoot().getAbsolutePath();
            if (file.getAbsolutePath().startsWith(root + File.separator)
                    && root.length() > bestLength) {
                best = session;
                bestLength = root.length();
            }
        }
        return best;
    }

    private static String stringArgument(List<Object> arguments, int index) {
        if (arguments == null || arguments.size() <= index || arguments.get(index) == null) {
            return "";
        }
        return String.valueOf(arguments.get(index));
    }
}
