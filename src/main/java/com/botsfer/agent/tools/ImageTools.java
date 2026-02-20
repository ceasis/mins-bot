package com.botsfer.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Image manipulation tools: flip, rotate, grayscale, resize.
 * Uses Java ImageIO and BufferedImage; outputs are saved with a suffix so originals are not overwritten.
 */
@Component
public class ImageTools {

    private static final int MAX_DIMENSION = 8000;

    private final ToolExecutionNotifier notifier;

    public ImageTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Flip an image vertically (top becomes bottom). Saves to a new file with _vflip before the extension (e.g. photo.png -> photo_vflip.png).")
    public String flipImageVertical(
            @ToolParam(description = "Full path to the image file (e.g. C:\\Users\\me\\Pictures\\photo.png)") String imagePath) {
        notifier.notify("Flipping image vertically...");
        return transform(imagePath, "vflip", (src, w, h) -> {
            AffineTransform tx = AffineTransform.getScaleInstance(1, -1);
            tx.translate(0, -h);
            return new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR).filter(src, null);
        });
    }

    @Tool(description = "Flip an image horizontally (left becomes right). Saves to a new file with _hflip before the extension.")
    public String flipImageHorizontal(
            @ToolParam(description = "Full path to the image file") String imagePath) {
        notifier.notify("Flipping image horizontally...");
        return transform(imagePath, "hflip", (src, w, h) -> {
            AffineTransform tx = AffineTransform.getScaleInstance(-1, 1);
            tx.translate(-w, 0);
            return new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR).filter(src, null);
        });
    }

    @Tool(description = "Convert an image to black and white (grayscale). Saves to a new file with _bw before the extension.")
    public String imageToBlackAndWhite(
            @ToolParam(description = "Full path to the image file") String imagePath) {
        notifier.notify("Converting image to black and white...");
        return transform(imagePath, "bw", (src, w, h) -> {
            ColorConvertOp op = new ColorConvertOp(
                    ColorSpace.getInstance(ColorSpace.CS_GRAY), null);
            BufferedImage dest = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            return op.filter(src, dest);
        });
    }

    @Tool(description = "Rotate an image by 90, 180, or 270 degrees clockwise. Saves to a new file with _rot90, _rot180, or _rot270 before the extension.")
    public String rotateImage(
            @ToolParam(description = "Full path to the image file") String imagePath,
            @ToolParam(description = "Degrees to rotate clockwise: 90, 180, or 270") int degrees) {
        if (degrees != 90 && degrees != 180 && degrees != 270) {
            return "Rotation must be 90, 180, or 270 degrees.";
        }
        notifier.notify("Rotating image " + degrees + "°...");
        String suffix = "rot" + degrees;
        double rad = Math.toRadians(degrees);
        return transform(imagePath, suffix, (src, w, h) -> {
            AffineTransform tx = new AffineTransform();
            if (degrees == 90) {
                tx.translate(h, 0);
                tx.rotate(rad);
            } else if (degrees == 180) {
                tx.translate(w, h);
                tx.rotate(rad);
            } else {
                tx.translate(0, w);
                tx.rotate(rad);
            }
            int outW = (degrees == 90 || degrees == 270) ? h : w;
            int outH = (degrees == 90 || degrees == 270) ? w : h;
            BufferedImage dest = new BufferedImage(outW, outH, src.getType());
            Graphics2D g2 = dest.createGraphics();
            g2.drawImage(src, tx, null);
            g2.dispose();
            return dest;
        });
    }

    @Tool(description = "Resize an image to a new width and height. Saves to a new file with _WxH before the extension (e.g. photo_800x600.png). Aspect ratio may change unless you choose dimensions that match.")
    public String resizeImage(
            @ToolParam(description = "Full path to the image file") String imagePath,
            @ToolParam(description = "New width in pixels") int width,
            @ToolParam(description = "New height in pixels") int height) {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            return "Width and height must be between 1 and " + MAX_DIMENSION + ".";
        }
        notifier.notify("Resizing image to " + width + "x" + height + "...");
        String suffix = width + "x" + height;
        return transform(imagePath, suffix, (src, w, h) -> {
            BufferedImage dest = new BufferedImage(width, height, src.getType());
            Graphics2D g2 = dest.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(src, 0, 0, width, height, null);
            g2.dispose();
            return dest;
        });
    }

    @Tool(description = "Get basic info about an image: dimensions, file size, and format.")
    public String getImageInfo(
            @ToolParam(description = "Full path to the image file") String imagePath) {
        notifier.notify("Reading image info...");
        try {
            Path p = Paths.get(imagePath).normalize().toAbsolutePath();
            File f = p.toFile();
            if (!f.exists() || !f.isFile()) {
                return "File not found: " + imagePath;
            }
            BufferedImage img = ImageIO.read(f);
            if (img == null) {
                return "Could not read as image (unsupported format?): " + imagePath;
            }
            String format = formatFromPath(imagePath);
            long size = f.length();
            return String.format("Image: %s\n  Dimensions: %d x %d px\n  File size: %s\n  Format: %s",
                    p, img.getWidth(), img.getHeight(), formatSize(size), format);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @FunctionalInterface
    private interface ImageTransform {
        BufferedImage apply(BufferedImage src, int w, int h) throws Exception;
    }

    private String transform(String imagePath, String suffix, ImageTransform op) {
        try {
            Path p = Paths.get(imagePath).normalize().toAbsolutePath();
            File in = p.toFile();
            if (!in.exists() || !in.isFile()) {
                return "File not found: " + imagePath;
            }
            BufferedImage src = ImageIO.read(in);
            if (src == null) {
                return "Could not read as image (unsupported format?): " + imagePath;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            BufferedImage dest = op.apply(src, w, h);
            if (dest == null) {
                return "Transform produced no output.";
            }
            String baseName = p.getFileName().toString();
            int dot = baseName.lastIndexOf('.');
            String nameWithoutExt = dot > 0 ? baseName.substring(0, dot) : baseName;
            String ext = (dot > 0 && dot < baseName.length() - 1) ? baseName.substring(dot + 1) : "png";
            String outName = nameWithoutExt + "_" + suffix + "." + ext;
            Path outPath = p.getParent().resolve(outName);
            File out = outPath.toFile();
            String formatName = formatFromPath(outName);
            if (!ImageIO.write(dest, formatName, out)) {
                return "Failed to write image (format " + formatName + " may not support this image type).";
            }
            return "Saved: " + outPath;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static String formatFromPath(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "jpg";
        if (lower.endsWith(".gif")) return "gif";
        if (lower.endsWith(".bmp")) return "bmp";
        return "png";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
