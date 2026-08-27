package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/*
    Answers "is git rewriting the working tree right now".

    A branch switch touches hundreds of BUILD files one after another. The file watcher debounces,
    but on a large repository the pauses between event bursts exceed any debounce, so a refresh can
    start against a half-written tree: discovery sees a mix of two branches, provisioning prunes
    projects that come back seconds later, and a partial aquery answers with holes. Waiting the
    operation out costs a handful of File.exists() calls.
 */
final class GitState {

    private GitState() {
    }

    static boolean operationInProgress(File root) {
        File gitDirectory = gitDirectory(root);
        if (gitDirectory == null) {
            return false;
        }
        return new File(gitDirectory, "index.lock").exists()
                || new File(gitDirectory, "MERGE_HEAD").exists()
                || new File(gitDirectory, "rebase-merge").exists()
                || new File(gitDirectory, "rebase-apply").exists()
                || new File(gitDirectory, "BISECT_LOG").exists();
    }

    /*
        .git is a directory in a normal clone and a one-line "gitdir: <path>" file in a worktree.
     */
    static File gitDirectory(File root) {
        File dotGit = new File(root, ".git");
        if (dotGit.isDirectory()) {
            return dotGit;
        }
        if (!dotGit.isFile()) {
            return null;
        }
        try {
            String content = Files.readString(dotGit.toPath(), StandardCharsets.UTF_8).strip();
            if (!content.startsWith("gitdir:")) {
                return null;
            }
            String path = content.substring("gitdir:".length()).strip();
            File resolved = new File(path);
            if (!resolved.isAbsolute()) {
                resolved = new File(root, path);
            }
            return resolved.isDirectory() ? resolved : null;
        } catch (IOException e) {
            return null;
        }
    }
}
