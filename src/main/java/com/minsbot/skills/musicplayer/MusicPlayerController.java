package com.minsbot.skills.musicplayer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/musicplayer")
public class MusicPlayerController {
    private final MusicPlayerService svc;
    private final MusicPlayerConfig.MusicPlayerProperties props;
    public MusicPlayerController(MusicPlayerService svc, MusicPlayerConfig.MusicPlayerProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "musicplayer", "enabled", props.isEnabled(), "libraryPaths", props.getLibraryPaths(), "youtubeFallback", props.isYoutubeFallback())); }
    @PostMapping("/play") public ResponseEntity<?> play(@RequestParam(required = false) String query, @RequestParam(required = false) String path) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try {
            if (path != null && !path.isBlank()) return ResponseEntity.ok(svc.playPath(path));
            if (query != null && !query.isBlank()) return ResponseEntity.ok(svc.searchAndPlay(query));
            return ResponseEntity.ok(svc.playRandom());
        } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/random") public ResponseEntity<?> random() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.playRandom()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/youtube") public ResponseEntity<?> yt(@RequestParam String query) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.openYoutubeSearch(query)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @GetMapping("/library") public ResponseEntity<?> library(@RequestParam(defaultValue = "100") int limit) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.listLibrary(limit)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @GetMapping("/recent") public ResponseEntity<?> recent() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        return ResponseEntity.ok(svc.recent());
    }
}
