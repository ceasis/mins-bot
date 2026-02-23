package com.minsbot;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves screenshot images from ~/mins_bot_data/screenshots/ so the chat UI can display them.
 * URL: GET /api/screenshot?file=2026_Feb/23/2026-02-23_23-54-01_manual.png
 */
@RestController
public class ScreenshotController {

    private static final Path SCREENSHOTS_DIR = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "screenshots");

    @GetMapping("/api/screenshot")
    public ResponseEntity<Resource> getScreenshot(@RequestParam String file) {
        // Security: resolve and verify the path stays within the screenshots directory
        Path resolved = SCREENSHOTS_DIR.resolve(file).normalize();
        if (!resolved.startsWith(SCREENSHOTS_DIR)) {
            return ResponseEntity.badRequest().build();
        }
        if (!resolved.toFile().exists() || !resolved.toFile().isFile()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(resolved));
    }
}
