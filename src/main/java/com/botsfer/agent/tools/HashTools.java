package com.botsfer.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class HashTools {

    private final ToolExecutionNotifier notifier;

    public HashTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Compute SHA-256 checksum of a file. Use for verification or when the user asks for file hash.")
    public String fileSha256(
            @ToolParam(description = "Full path to the file") String filePath) {
        if (filePath == null || filePath.isBlank()) return "File path is required.";
        notifier.notify("Computing SHA-256: " + filePath);
        return hashFile(filePath, "SHA-256");
    }

    @Tool(description = "Compute SHA-1 checksum of a file.")
    public String fileSha1(
            @ToolParam(description = "Full path to the file") String filePath) {
        if (filePath == null || filePath.isBlank()) return "File path is required.";
        notifier.notify("Computing SHA-1: " + filePath);
        return hashFile(filePath, "SHA-1");
    }

    private static String hashFile(String filePath, String algorithm) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.isRegularFile(path)) return "File not found: " + filePath;
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[8192];
            try (var in = Files.newInputStream(path)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return algorithm + " failed: " + e.getMessage();
        }
    }
}
