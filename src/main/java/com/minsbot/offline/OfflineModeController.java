package com.minsbot.offline;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/offline-mode")
public class OfflineModeController {

    private final OfflineModeService offlineMode;

    public OfflineModeController(OfflineModeService offlineMode) {
        this.offlineMode = offlineMode;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("offline", offlineMode.isOffline());
        if (offlineMode.enabledAt() != null) out.put("enabledAt", offlineMode.enabledAt().toString());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/enable")
    public ResponseEntity<?> enable() { offlineMode.enable(); return status(); }

    @PostMapping("/disable")
    public ResponseEntity<?> disable() { offlineMode.disable(); return status(); }

    @PostMapping("/toggle")
    public ResponseEntity<?> toggle() { offlineMode.toggle(); return status(); }
}
