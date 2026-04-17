package com.minsbot.skills.hashtagsuggest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/hashtagsuggest")
public class HashtagSuggestController {

    private final HashtagSuggestService service;
    private final HashtagSuggestConfig.HashtagSuggestProperties properties;

    public HashtagSuggestController(HashtagSuggestService service,
                                    HashtagSuggestConfig.HashtagSuggestProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "hashtagsuggest",
                "enabled", properties.isEnabled(),
                "maxTextChars", properties.getMaxTextChars(),
                "defaultTopN", properties.getDefaultTopN()
        ));
    }

    @PostMapping("/suggest")
    public ResponseEntity<?> suggest(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        String text = (String) body.get("text");
        if (text == null) return ResponseEntity.badRequest().body(Map.of("error", "text required"));
        if (text.length() > properties.getMaxTextChars()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text exceeds maxTextChars"));
        }
        int topN = body.get("topN") == null ? properties.getDefaultTopN() : ((Number) body.get("topN")).intValue();
        boolean includeExisting = Boolean.TRUE.equals(body.getOrDefault("includeExisting", true));
        return ResponseEntity.ok(service.suggest(text, topN, includeExisting));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "HashtagSuggest skill is disabled"));
    }
}
