package com.minsbot.skills.exifstripper;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ExifStripperService {

    public Map<String, Object> strip(String inputPath, String outputPath, long maxBytes) throws IOException {
        if (inputPath == null || inputPath.isBlank()) throw new IllegalArgumentException("inputPath required");
        Path in = Paths.get(inputPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(in)) throw new IllegalArgumentException("input file not found");
        long size = Files.size(in);
        if (size > maxBytes) throw new IllegalArgumentException("file exceeds maxFileBytes");

        BufferedImage image = ImageIO.read(in.toFile());
        if (image == null) throw new IllegalArgumentException("not a decodable image");

        Path out = (outputPath == null || outputPath.isBlank())
                ? deriveOutputPath(in)
                : Paths.get(outputPath).toAbsolutePath().normalize();

        String ext = ext(out.toString()).toLowerCase();
        if (ext.isEmpty()) throw new IllegalArgumentException("output path must have an extension (png/jpg/webp)");

        // Write back without any metadata by using ImageIO which only persists pixel data.
        // This effectively strips EXIF/IPTC/XMP chunks.
        boolean ok = ImageIO.write(image, ext.equals("jpg") ? "jpeg" : ext, out.toFile());
        if (!ok) throw new IOException("no writer available for format: " + ext);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("input", in.toString());
        result.put("output", out.toString());
        result.put("inputSize", size);
        result.put("outputSize", Files.size(out));
        result.put("format", ext);
        result.put("stripped", "EXIF, IPTC, XMP (all non-pixel metadata removed)");
        return result;
    }

    private static Path deriveOutputPath(Path in) {
        String file = in.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String stem = dot < 0 ? file : file.substring(0, dot);
        String ext = dot < 0 ? "" : file.substring(dot);
        return in.resolveSibling(stem + "_no-exif" + ext);
    }

    private static String ext(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1);
    }
}
