package com.minsbot;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Serves index.html with automatic cache-bust versioning so WebView picks up
 * CSS/JS changes on every restart. Replaces {@code {{v}}} in the template with
 * a per-process startup timestamp.
 */
@RestController
public class IndexController {

    /** Cache-bust token for this process. Changes on every app restart. */
    private final String version = Long.toString(System.currentTimeMillis());

    /** Cached rendered HTML — read once at startup. */
    private volatile String renderedHtml = "";

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource res = new ClassPathResource("static/index.html");
        String raw = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        renderedHtml = raw.replace("{{v}}", version);
    }

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> index() {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(renderedHtml);
    }
}
