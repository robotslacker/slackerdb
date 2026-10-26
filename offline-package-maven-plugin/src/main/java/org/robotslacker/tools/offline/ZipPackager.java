package org.robotslacker.tools.offline;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a directory into a zip, preserving the directory name as the top-level entry.
 * Java's ZipOutputStream writes Zip64 structures automatically when needed.
 */
public final class ZipPackager {

    public void zip(Path sourceDir, Path zipFile) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            throw new IOException("Not a directory: " + sourceDir);
        }
        Path base = sourceDir.toAbsolutePath().normalize().getParent();
        Files.createDirectories(zipFile.toAbsolutePath().getParent());

        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                Files.newOutputStream(zipFile, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING)))) {
            zip.setLevel(Deflater.BEST_SPEED);
            List<Path> paths;
            try (var stream = Files.walk(sourceDir)) {
                paths = stream.sorted(Comparator.naturalOrder()).toList();
            }
            for (Path path : paths) {
                String name = base.relativize(path.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                if (name.isEmpty()) {
                    continue;
                }
                if (Files.isDirectory(path)) {
                    ZipEntry entry = new ZipEntry(name + "/");
                    zip.putNextEntry(entry);
                    zip.closeEntry();
                } else if (Files.isRegularFile(path)) {
                    ZipEntry entry = new ZipEntry(name);
                    entry.setTime(Files.getLastModifiedTime(path).toMillis());
                    zip.putNextEntry(entry);
                    try (InputStream in = Files.newInputStream(path)) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
        }
    }
}
