package io.github.sorteam.bazel.jdtls;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
    Files whose declared package does not match the directory they sit in.

    Bazel hands javac an explicit list of files and never looks at the directory layout, so a file
    under com/example/api/expose/ declaring package com.example.api.external compiles without
    complaint. Eclipse derives the package from the folder and reports the mismatch as an error that
    has no severity setting, and every reference to such a file from its declared package then fails
    to resolve as well - one misplaced file costs a handful of errors in files that are themselves
    fine.

    Rather than mirror a rule bazel does not have, the provisioner puts these files where their own
    package says they belong: excluded from the real source folder and linked into a second one at
    the path their package implies. The file on disk is untouched; only what Eclipse sees changes.

    No Eclipse types here, so the scan can be unit tested with plain javac.
 */
public final class SourceRelocation {

    /*
        relativePath is relative to the source root, and doubles as the exclusion pattern.
     */
    public record Misplaced(String relativePath, String declaredPackage, String fileName) {
    }

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private SourceRelocation() {
    }

    /*
        skip holds source roots of the same project nested inside this one, relative to it. Their
        files belong to that root and are already correct there; scanning them from out here would
        judge them against the wrong root and relocate files that are not misplaced at all.
     */
    public static List<Misplaced> scan(File sourceRoot, Collection<String> skip) {
        List<Misplaced> misplaced = new ArrayList<>();
        collect(sourceRoot, "", Set.copyOf(skip), misplaced);
        return misplaced;
    }

    private static void collect(File directory, String relative, Set<String> skip,
            List<Misplaced> misplaced) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String childRelative = relative.isEmpty()
                    ? child.getName() : relative + "/" + child.getName();
            if (child.isDirectory()) {
                if (!skip.contains(childRelative)) {
                    collect(child, childRelative, skip, misplaced);
                }
                continue;
            }
            if (!child.getName().endsWith(".java")) {
                continue;
            }
            String declared = declaredPackage(child.toPath());
            if (declared == null || declared.replace('.', '/').equals(relative)) {
                continue;
            }
            misplaced.add(new Misplaced(childRelative, declared, child.getName()));
        }
    }

    /*
        The declaration, "" for the default package, or null when the file cannot be read. Only the
        head of the file is examined: a package declaration further in than 8 KB of comment is not
        worth the read.
     */
    public static String declaredPackage(Path file) {
        char[] buffer = new char[8192];
        int length;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            length = reader.read(buffer);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (length <= 0) {
            return "";
        }
        Matcher matcher = PACKAGE_DECLARATION.matcher(new String(buffer, 0, length));
        return matcher.find() ? matcher.group(1) : "";
    }
}
