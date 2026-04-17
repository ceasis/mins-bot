package com.minsbot.skills.flashcardmaker;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/flashcardmaker")
public class FlashcardMakerController {
    private final FlashcardMakerService service;
    private final FlashcardMakerConfig.FlashcardMakerProperties properties;
    public FlashcardMakerController(FlashcardMakerService service, FlashcardMakerConfig.FlashcardMakerProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "flashcardmaker", "enabled", properties.isEnabled())); }

    @PostMapping("/from-pairs") @SuppressWarnings("unchecked") public ResponseEntity<?> fromPairs(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.fromQaPairs((List<Map<String, String>>) body.get("pairs"))); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @PostMapping("/from-text") public ResponseEntity<?> fromText(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.fromDelimitedText(body.get("text"), body.get("separator"))); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @PostMapping("/from-markdown") public ResponseEntity<?> fromMarkdown(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.fromMarkdownHeaders(body.get("markdown"))); } catch (IllegalArgumentException e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "FlashcardMaker skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
