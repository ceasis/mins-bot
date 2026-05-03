package com.minsbot.skills.mediactl;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/mediactl")
public class MediaCtlController {
    private final MediaCtlService svc;
    private final MediaCtlConfig.MediaCtlProperties props;
    public MediaCtlController(MediaCtlService svc, MediaCtlConfig.MediaCtlProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "mediactl", "enabled", props.isEnabled())); }
    @PostMapping("/play-pause") public ResponseEntity<?> pp() { return guard(svc::playPause); }
    @PostMapping("/next") public ResponseEntity<?> next() { return guard(svc::next); }
    @PostMapping("/prev") public ResponseEntity<?> prev() { return guard(svc::prev); }
    @PostMapping("/stop") public ResponseEntity<?> stop() { return guard(svc::stop); }
    @PostMapping("/volume-up") public ResponseEntity<?> vu() { return guard(svc::volumeUp); }
    @PostMapping("/volume-down") public ResponseEntity<?> vd() { return guard(svc::volumeDown); }
    @PostMapping("/mute") public ResponseEntity<?> mute() { return guard(svc::mute); }
    @PostMapping("/set-volume") public ResponseEntity<?> set(@RequestParam int pct) { return guard(() -> svc.setVolumePercent(pct)); }

    interface T { Object call() throws Exception; }
    private ResponseEntity<?> guard(T t) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(t.call()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
