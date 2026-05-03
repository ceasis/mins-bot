package com.minsbot.skills.screenshotter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/screenshotter")
public class ScreenshotterController {
    private final ScreenshotterService svc;
    private final ScreenshotterConfig.ScreenshotterProperties props;
    public ScreenshotterController(ScreenshotterService svc, ScreenshotterConfig.ScreenshotterProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "screenshotter", "enabled", props.isEnabled(), "storageDir", props.getStorageDir())); }
    @PostMapping("/full") public ResponseEntity<?> full() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.captureFullScreen()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/rect") public ResponseEntity<?> rect(@RequestParam int x, @RequestParam int y, @RequestParam int w, @RequestParam int h) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.captureRect(x, y, w, h)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/all") public ResponseEntity<?> all() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.captureAllScreens()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
