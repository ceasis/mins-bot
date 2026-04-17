package com.minsbot.skills.encoder;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/encoder")
public class EncoderController {

    private final EncoderService service;
    private final EncoderConfig.EncoderProperties properties;

    public EncoderController(EncoderService service, EncoderConfig.EncoderProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "encoder",
                "enabled", properties.isEnabled(),
                "maxInputBytes", properties.getMaxInputBytes()
        ));
    }

    @PostMapping("/encode")
    public ResponseEntity<?> encode(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String input = required(body, "input");
            String format = body.getOrDefault("format", "base64").toLowerCase();
            if (input.length() > properties.getMaxInputBytes()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Input exceeds maxInputBytes"));
            }
            String result = switch (format) {
                case "base64" -> service.base64Encode(input);
                case "base64url" -> service.base64UrlEncode(input);
                case "hex" -> service.hexEncode(input);
                case "url" -> service.urlEncode(input);
                default -> throw new IllegalArgumentException("Unknown format: " + format);
            };
            return ResponseEntity.ok(Map.of("format", format, "result", result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/decode")
    public ResponseEntity<?> decode(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String input = required(body, "input");
            String format = body.getOrDefault("format", "base64").toLowerCase();
            if (input.length() > properties.getMaxInputBytes()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Input exceeds maxInputBytes"));
            }
            String result = switch (format) {
                case "base64" -> service.base64Decode(input);
                case "base64url" -> service.base64UrlDecode(input);
                case "hex" -> service.hexDecode(input);
                case "url" -> service.urlDecode(input);
                default -> throw new IllegalArgumentException("Unknown format: " + format);
            };
            return ResponseEntity.ok(Map.of("format", format, "result", result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String required(Map<String, String> body, String key) {
        String value = body.get(key);
        if (value == null) throw new IllegalArgumentException("Missing required field: " + key);
        return value;
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "Encoder skill is disabled"));
    }
}
