package com.minsbot.skills.fileinfo;

import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;

@Service
public class FileInfoService {
    private final FileInfoConfig.FileInfoProperties props;
    public FileInfoService(FileInfoConfig.FileInfoProperties props) { this.props = props; }

    public Map<String, Object> info(String path, boolean withHash) throws Exception {
        Path p = Paths.get(path);
        if (!Files.exists(p)) throw new IllegalArgumentException("not found: " + path);
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("path", p.toAbsolutePath().toString());
        r.put("name", p.getFileName().toString());
        r.put("isFile", a.isRegularFile());
        r.put("isDirectory", a.isDirectory());
        r.put("isSymlink", a.isSymbolicLink());
        r.put("sizeBytes", a.size());
        r.put("sizeMb", Math.round(a.size() / 1_000_000.0 * 100) / 100.0);
        r.put("created", a.creationTime().toString());
        r.put("modified", a.lastModifiedTime().toString());
        r.put("accessed", a.lastAccessTime().toString());
        try {
            String mime = Files.probeContentType(p);
            r.put("mimeType", mime == null ? "unknown" : mime);
        } catch (Exception ignored) {}
        try {
            FileOwnerAttributeView owner = Files.getFileAttributeView(p, FileOwnerAttributeView.class);
            if (owner != null) r.put("owner", owner.getOwner().getName());
        } catch (Exception ignored) {}
        try {
            PosixFileAttributes posix = Files.readAttributes(p, PosixFileAttributes.class);
            r.put("permissions", PosixFilePermissions.toString(posix.permissions()));
        } catch (Exception ignored) {}

        if (withHash && a.isRegularFile() && a.size() <= props.getMaxHashBytes()) {
            r.put("sha256", sha256(p));
        } else if (withHash) r.put("sha256", "(skipped — file too big or not a regular file)");
        return r;
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
