package com.minsbot.skills.keywordextractor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/keywordextractor")
public class KeywordExtractorController {

    private final KeywordExtractorService service;
    private final KeywordExtractorConfig.KeywordExtractorProperties properties;

    public KeywordExtractorController(KeywordExtractorService service, KeywordExtractorConfig.KeywordExtractorProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "keywordextractor",
                "enabled", properties.isEnabled(),
                "maxTextChars", properties.getMaxTextChars()
        ));
    }

    @PostMapping("/text")
    public ResponseEntity<?> text(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        String text = (String) body.get("text");
        if (text == null) return ResponseEntity.badRequest().body(Map.of("error", "text required"));
        int topN = ((Number) body.getOrDefault("topN", 20)).intValue();
        int ngramMax = ((Number) body.getOrDefault("ngramMax", 2)).intValue();
        boolean keep = Boolean.TRUE.equals(body.getOrDefault("keepStopwords", false));
        return ResponseEntity.ok(service.extractFromText(text, topN, ngramMax, keep));
    }

    @GetMapping("/url")
    public ResponseEntity<?> url(@RequestParam String url,
                                 @RequestParam(defaultValue = "20") int topN,
                                 @RequestParam(defaultValue = "2") int ngramMax,
                                 @RequestParam(defaultValue = "false") boolean keepStopwords) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.extractFromUrl(url, topN, ngramMax, keepStopwords));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "KeywordExtractor skill is disabled"));
    }
}
