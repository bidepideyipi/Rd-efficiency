package com.mig.mapsql.core.scan;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FcFileWalker {

    private static final Set<String> SKIP_DIR_NAMES;

    static {
        Set<String> skip = new HashSet<String>();
        skip.add(".git");
        skip.add("target");
        skip.add("node_modules");
        skip.add(".idea");
        skip.add(".svn");
        skip.add("build");
        SKIP_DIR_NAMES = Collections.unmodifiableSet(skip);
    }

    private FcFileWalker() {
    }

    public static List<Path> listFcFiles(Path root) throws IOException {
        final List<Path> files = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(root) && (name.startsWith(".") || SKIP_DIR_NAMES.contains(name))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".fc")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(files);
        return files;
    }
}
