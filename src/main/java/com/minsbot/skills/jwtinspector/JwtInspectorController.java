package com.minsbot.skills.jwtinspector;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/jwtinspector")
public class JwtInspectorController {

    private final JwtInspectorService service;
    private final JwtInspectorConfig.JwtInspectorProperties properties;

    public JwtInspectorController(JwtInspectorService service,
                                  JwtInspectorConfig.JwtInspectorProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "jwtinspector",
                "enabled", properties.isEnabled(),
                "maxTokenChars", properties.getMaxTokenChars(),
                "verifyAlgorithms", "HS256, HS384, HS512, RS256, RS384, RS512"
        ));
    }

    @PostMapping("/decode")
    public ResponseEntity<?> decode(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String token = body.get("token");
        if (token == null) return ResponseEntity.badRequest().body(Map.of("error", "token required"));
        if (token.length() > properties.getMaxTokenChars()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token exceeds maxTokenChars"));
        }
        try {
            return ResponseEntity.ok(service.decode(token));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String token = body.get("token");
        if (token == null) return ResponseEntity.badRequest().body(Map.of("error", "token required"));
        if (token.length() > properties.getMaxTokenChars()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token exceeds maxTokenChars"));
        }
        try {
            String secret = body.get("secret");
            String publicKey = body.get("publicKey");
            if (secret != null) return ResponseEntity.ok(service.verifyHmac(token, secret));
            if (publicKey != null) return ResponseEntity.ok(service.verifyRsa(token, publicKey));
            return ResponseEntity.badRequest().body(Map.of("error", "secret (HMAC) or publicKey (RSA) required"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "JwtInspector skill is disabled"));
    }
}
