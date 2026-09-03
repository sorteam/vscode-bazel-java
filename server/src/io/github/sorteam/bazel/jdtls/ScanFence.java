package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.util.List;

import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.jdt.ls.core.internal.preferences.Preferences;

/*
    Puts the output-tree exclusions into the language server's live preferences, and keeps them there.

    Which patterns those are, and why the scan needs fencing off at all, is ImportExclusions. This is
    the half that has to touch jdt.ls: the write itself, and the fact that the write does not last.

    Preferences are rebuilt, not edited, on every didChangeConfiguration - the client sends its whole
    java.* section, jdt.ls constructs a fresh Preferences from it and hands it to
    PreferenceManager.update, which drops the previous object along with anything written into it. A
    single injection at import time therefore survives exactly until the next settings change, and
    the next workspace scan after that follows bazel-out again. update() notifies its listeners
    before it returns, and after the new object is already the current one, so re-applying from a
    listener puts the patterns back ahead of anything the configuration change goes on to trigger.

    The listener is registered once for the whole server and walks every session, because there is
    one preferences object for all workspace folders and the importer that raised the fence for one
    root is not the one that will hear about the change.
 */
final class ScanFence {

    /* The manager this class has already subscribed to. Identity rather than a flag: jdt.ls builds
       its PreferenceManager once per server, but a second one would come with an empty listener list,
       and re-registering on the same one would only duplicate the work. */
    private static PreferenceManager listeningOn;

    private ScanFence() {
    }

    static void raise(BazelSession session) {
        PreferenceManager manager = JavaLanguageServerPlugin.getPreferencesManager();
        if (manager == null) {
            return;
        }
        keepRaisedAcrossConfigurationChanges(manager);
        List<String> symlinks = apply(session, manager.getPreferences());
        if (symlinks != null) {
            BazelLog.info(String.format(
                    "JBazel: excluded the bazel output tree from the language server's build-file"
                            + " scan (%s); the symlinks themselves are left alone",
                    symlinks.isEmpty() ? "no symlinks in the root yet" : String.join(", ", symlinks)));
        }
    }

    /*
        The symlinks the fence was raised for, or null when the patterns were already there - so the
        caller can skip the log line on every import after the first.
     */
    private static List<String> apply(BazelSession session, Preferences preferences) {
        if (preferences == null) {
            return null;
        }
        BazelWorkspace workspace = session.getWorkspace();
        File root = workspace.getRoot();
        if (workspace.peekExecutionRoot() == null) {
            String stored = session.getStore().peekExecutionRoot();
            if (stored != null && !stored.isBlank()) {
                workspace.setExecutionRoot(new File(stored));
            }
        }
        List<String> symlinks = workspace.convenienceSymlinks();
        List<String> merged = ImportExclusions.merge(preferences.getJavaImportExclusions(),
                ImportExclusions.patterns(root, symlinks, workspace.peekOutputBase(),
                        BazelRc.symlinkPrefixes(root)));
        if (merged == null) {
            return null;
        }
        preferences.setJavaImportExclusions(merged);
        return symlinks;
    }

    private static synchronized void keepRaisedAcrossConfigurationChanges(PreferenceManager manager) {
        if (listeningOn == manager) {
            return;
        }
        listeningOn = manager;
        manager.addPreferencesChangeListener((previous, current) -> {
            try {
                for (BazelSession session : BazelSession.all()) {
                    if (apply(session, current) != null) {
                        BazelLog.info("JBazel: a settings change rebuilt java.import.exclusions;"
                                + " the bazel output tree is fenced off again");
                    }
                }
            } catch (RuntimeException e) {
                // jdt.ls runs each listener through SafeRunner, so the other listeners survive this
                // either way; what the catch buys is the failure landing in the JBazel log next to
                // the rest of the import diagnostics rather than only in the Eclipse error log.
                BazelLog.exception("JBazel: could not re-apply the output-tree exclusions", e);
            }
        });
    }
}
