package com.minsbot.skills.encryptionaes;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/encryptionaes")
public class EncryptionAesController {
    private final EncryptionAesService service;
    private final EncryptionAesConfig.EncryptionAesProperties properties;
    public EncryptionAesController(EncryptionAesService service, EncryptionAesConfig.EncryptionAesProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "encryptionaes", "enabled", properties.isEnabled(), "algorithm", "AES-256-GCM with PBKDF2 key derivation")); }

    @PostMapping("/encrypt") public ResponseEntity<?> encrypt(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String plain = body.get("plaintext"); String pass = body.get("passphrase");
            if (plain != null && plain.length() > properties.getMaxInputBytes()) return bad("exceeds maxInputBytes");
            return ResponseEntity.ok(service.encryptText(plain, pass));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); } catch (Exception e) { return ResponseEntity.status(500).body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/decrypt") public ResponseEntity<?> decrypt(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.decryptText(body.get("ciphertextBase64"), body.get("passphrase"))); }
        catch (IllegalArgumentException e) { return bad(e.getMessage()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "decryption failed — wrong passphrase or corrupted data")); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "EncryptionAes skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(String msg) { return ResponseEntity.badRequest().body(Map.of("error", msg)); }
}
