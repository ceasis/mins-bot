package com.botsfer.agent.tools;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;

@Component
public class QrTools {

    private final ToolExecutionNotifier notifier;

    public QrTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Generate a QR code image from text or URL. Saves to the given file path (e.g. .png).")
    public String generateQr(
            @ToolParam(description = "Text or URL to encode in the QR code") String content,
            @ToolParam(description = "Full path for the output image, e.g. C:\\Users\\Me\\qr.png") String outputPath) {
        if (content == null || content.isBlank()) return "Content is required.";
        if (outputPath == null || outputPath.isBlank()) return "Output path is required.";
        notifier.notify("Generating QR code");
        try {
            Path out = Paths.get(outputPath);
            Files.createDirectories(out.getParent() != null ? out.getParent() : out.getRoot());
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 256, 256, hints);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
            String ext = out.getFileName().toString();
            int dot = ext.lastIndexOf('.');
            String format = dot > 0 ? ext.substring(dot + 1) : "png";
            if (!ImageIO.write(image, format, out.toFile())) {
                ImageIO.write(image, "PNG", out.toFile());
            }
            return "QR code saved to: " + out.toAbsolutePath();
        } catch (WriterException | IOException e) {
            return "QR generation failed: " + e.getMessage();
        }
    }

    @Tool(description = "Decode a QR code from an image file and return the text or URL stored in it.")
    public String decodeQr(
            @ToolParam(description = "Full path to the image containing a QR code") String imagePath) {
        if (imagePath == null || imagePath.isBlank()) return "Image path is required.";
        notifier.notify("Decoding QR code");
        try {
            Path path = Paths.get(imagePath);
            if (!Files.isRegularFile(path)) return "File not found: " + imagePath;
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) return "Could not read image file.";
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            Result result = new MultiFormatReader().decode(bitmap);
            return result != null ? result.getText() : "No QR code found in image.";
        } catch (com.google.zxing.NotFoundException e) {
            return "No QR code found in image.";
        } catch (Exception e) {
            return "Decode failed: " + e.getMessage();
        }
    }
}
