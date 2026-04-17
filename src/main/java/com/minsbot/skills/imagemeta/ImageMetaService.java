package com.minsbot.skills.imagemeta;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class ImageMetaService {

    public Map<String, Object> inspect(String pathStr, long maxBytes) throws IOException {
        if (pathStr == null || pathStr.isBlank()) throw new IllegalArgumentException("path required");
        Path path = Paths.get(pathStr).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("file not found");
        long size = Files.size(path);
        if (size > maxBytes) throw new IllegalArgumentException("file exceeds maxFileBytes");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path.toString());
        out.put("sizeBytes", size);
        out.put("sizeFormatted", formatSize(size));

        try (ImageInputStream iis = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                out.put("readable", false);
                out.put("reason", "no ImageReader available for this file");
                return out;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                out.put("readable", true);
                out.put("format", reader.getFormatName());
                out.put("width", reader.getWidth(0));
                out.put("height", reader.getHeight(0));
                out.put("numImages", reader.getNumImages(true));
                int aspectGcd = gcd(reader.getWidth(0), reader.getHeight(0));
                out.put("aspectRatio", (reader.getWidth(0) / aspectGcd) + ":" + (reader.getHeight(0) / aspectGcd));
                out.put("megapixels", Math.round((reader.getWidth(0) * reader.getHeight(0) / 1_000_000.0) * 100.0) / 100.0);
            } finally {
                reader.dispose();
            }
        }
        return out;
    }

    public Map<String, Object> compareDimensions(String pathA, String pathB, long maxBytes) throws IOException {
        Map<String, Object> a = inspect(pathA, maxBytes);
        Map<String, Object> b = inspect(pathB, maxBytes);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("a", a);
        out.put("b", b);
        Object wa = a.get("width"), ha = a.get("height"), wb = b.get("width"), hb = b.get("height");
        if (wa instanceof Integer && wb instanceof Integer && ha instanceof Integer && hb instanceof Integer) {
            out.put("sameDimensions", wa.equals(wb) && ha.equals(hb));
        }
        return out;
    }

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.1f GB", mb / 1024.0);
    }
}
