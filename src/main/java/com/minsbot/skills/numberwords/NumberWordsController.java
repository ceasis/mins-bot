package com.minsbot.skills.numberwords;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/numberwords")
public class NumberWordsController {
    private final NumberWordsService service;
    private final NumberWordsConfig.NumberWordsProperties properties;
    public NumberWordsController(NumberWordsService service, NumberWordsConfig.NumberWordsProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "numberwords", "enabled", properties.isEnabled())); }

    @GetMapping("/to-words") public ResponseEntity<?> toWords(@RequestParam long number) { if (!properties.isEnabled()) return disabled(); return ResponseEntity.ok(Map.of("number", number, "words", service.toWords(number))); }
    @GetMapping("/from-words") public ResponseEntity<?> fromWords(@RequestParam String words) { if (!properties.isEnabled()) return disabled(); try { return ResponseEntity.ok(Map.of("words", words, "number", service.fromWords(words))); } catch (IllegalArgumentException e) { return bad(e); } }
    @GetMapping("/to-roman") public ResponseEntity<?> toRoman(@RequestParam int n) { if (!properties.isEnabled()) return disabled(); try { return ResponseEntity.ok(Map.of("number", n, "roman", service.toRoman(n))); } catch (IllegalArgumentException e) { return bad(e); } }
    @GetMapping("/from-roman") public ResponseEntity<?> fromRoman(@RequestParam String roman) { if (!properties.isEnabled()) return disabled(); try { return ResponseEntity.ok(Map.of("roman", roman, "number", service.fromRoman(roman))); } catch (IllegalArgumentException e) { return bad(e); } }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "NumberWords skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
