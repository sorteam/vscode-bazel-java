package ch.audienzz.bazel.jdtls;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Digests {

    private static final Set<String> BUILD_FILE_NAMES =
            Set.of("BUILD", "BUILD.bazel", "MODULE.bazel", "WORKSPACE", "WORKSPACE.bazel",
                    "REPO.bazel", "MODULE.bazel.lock");

    /*
        Directories that never contain a BUILD file worth stamping and would dominate the walk.
        bazel-* are the convenience symlinks into the output base; following them would descend into
        the entire action output tree.
     */
    private static final Set<String> PRUNED_DIRECTORIES =
            Set.of("node_modules", ".git", ".bazel", ".idea", ".vscode", "dist", "target");

    private Digests() {
    }

    public static String sha256(String value) {
        return hex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static String shortHash(String value) {
        return sha256(value).substring(0, 16);
    }

    /*
        A stamp over every build definition in the repository: path, size and modification time. It
        exists to answer "can the persisted classpath still be trusted" without running bazel, so it
        deliberately avoids reading file contents.
     */
    public static String buildFilesDigest(Path root) {
        MessageDigest digest = digest();
        List<String> entries = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    String name = directory.getFileName() == null
                            ? "" : directory.getFileName().toString();
                    if (!directory.equals(root)
                            && (PRUNED_DIRECTORIES.contains(name) || name.startsWith("bazel-"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (BUILD_FILE_NAMES.contains(name) || name.endsWith(".bzl")) {
                        entries.add(root.relativize(file) + ":" + attrs.size() + ":"
                                + attrs.lastModifiedTime().toMillis());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // A partial walk would produce a stamp that silently differs every time; better to say
            // "unknown" and let the caller treat the cache as stale.
            return "";
        }
        entries.sort(String::compareTo);
        entries.forEach(entry -> digest.update(entry.getBytes(StandardCharsets.UTF_8)));
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16));
            out.append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }
}
