package com.minsbot.skills.archiver;

import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.*;

/**
 * Pure-Java zip create/extract. Tar.gz / 7z deferred — JDK only ships zip.
 */
@Service
public class ArchiverService {

    public Map<String, Object> zip(String sourcePath, String destZip) throws IOException {
        Path src = Paths.get(sourcePath);
        if (!Files.exists(src)) throw new IllegalArgumentException("source not found: " + sourcePath);
        Path zipFile = Paths.get(destZip);
        Files.createDirectories(zipFile.toAbsolutePath().getParent() == null ? Paths.get(".") : zipFile.toAbsolutePath().getParent());

        long[] count = {0}; long[] size = {0};
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFile)))) {
            if (Files.isDirectory(src)) {
                Files.walkFileTree(src, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String entry = src.relativize(file).toString().replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(entry));
                        Files.copy(file, zos);
                        zos.closeEntry();
                        count[0]++; size[0] += attrs.size();
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                zos.putNextEntry(new ZipEntry(src.getFileName().toString()));
                Files.copy(src, zos);
                zos.closeEntry();
                count[0] = 1; size[0] = Files.size(src);
            }
        }
        return Map.of("ok", true, "zipFile", zipFile.toAbsolutePath().toString(),
                "files", count[0], "totalUncompressedBytes", size[0],
                "compressedBytes", Files.size(zipFile));
    }

    public Map<String, Object> unzip(String zipPath, String destDir) throws IOException {
        Path zipFile = Paths.get(zipPath);
        if (!Files.exists(zipFile)) throw new IllegalArgumentException("zip not found: " + zipPath);
        Path dest = Paths.get(destDir);
        Files.createDirectories(dest);
        long[] count = {0};
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) throw new IOException("zip slip: " + e.getName());
                if (e.isDirectory()) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                    count[0]++;
                }
                zis.closeEntry();
            }
        }
        return Map.of("ok", true, "extractedTo", dest.toAbsolutePath().toString(), "files", count[0]);
    }

    public Map<String, Object> listEntries(String zipPath) throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(zipPath))))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                entries.add(Map.of("name", e.getName(),
                        "size", e.getSize(),
                        "compressed", e.getCompressedSize(),
                        "directory", e.isDirectory()));
                zis.closeEntry();
            }
        }
        return Map.of("zip", zipPath, "count", entries.size(), "entries", entries);
    }
}
