package com.minsbot;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves index.html with automatic cache-bust versioning so WebView picks up
 * CSS/JS changes on every restart. Replaces {@code {{v}}} in the template with
 * a per-process startup timestamp.
 *
 * <p><strong>Dev mode:</strong> if {@code src/main/resources/static/index.html}
 * exists on disk (running from the project tree, not a packaged jar), the file
 * is re-read on every request so edits take effect on browser refresh without
 * a Spring restart. Otherwise the classpath copy cached at startup is served.
 */
@RestController
public class IndexController {

    /** Cache-bust token for this process. Changes on every app restart. */
    private final String version = Long.toString(System.currentTimeMillis());

    /** Dev-mode template path (relative to working dir). Re-read per request if present. */
    private static final Path DEV_TEMPLATE =
            Paths.get("src", "main", "resources", "static", "index.html");

    /** Fallback: cached rendered HTML from the classpath — read once at startup. */
    private volatile String cachedFromClasspath = "";

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource res = new ClassPathResource("static/index.html");
        String raw = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        cachedFromClasspath = raw.replace("{{v}}", version);
    }

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> index() {
        String body = cachedFromClasspath;
        // Dev: re-read source file so HTML edits show up on refresh.
        if (Files.isRegularFile(DEV_TEMPLATE)) {
            try {
                String raw = Files.readString(DEV_TEMPLATE, StandardCharsets.UTF_8);
                body = raw.replace("{{v}}", version);
            } catch (IOException ignored) {
                // fall through to classpath cache
            }
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(body);
    }
}
