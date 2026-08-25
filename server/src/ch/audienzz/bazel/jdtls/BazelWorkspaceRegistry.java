package ch.audienzz.bazel.jdtls;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BazelWorkspaceRegistry {

    private static final Map<String, BazelWorkspace> WORKSPACES = new ConcurrentHashMap<>();

    private BazelWorkspaceRegistry() {
    }

    public static BazelWorkspace forRoot(File root) {
        return WORKSPACES.computeIfAbsent(root.getAbsolutePath(),
                path -> new BazelWorkspace(new File(path)));
    }
}
