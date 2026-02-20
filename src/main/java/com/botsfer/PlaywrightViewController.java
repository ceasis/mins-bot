package com.botsfer;

import com.botsfer.agent.tools.PlaywrightService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/browser")
public class PlaywrightViewController {

    private final PlaywrightService playwrightService;

    public PlaywrightViewController(PlaywrightService playwrightService) {
        this.playwrightService = playwrightService;
    }

    @GetMapping("/screenshot")
    public ResponseEntity<byte[]> screenshot() {
        byte[] img = playwrightService.viewerScreenshot();
        if (img == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(img);
    }

    @GetMapping("/info")
    public Map<String, String> info() {
        return playwrightService.viewerInfo();
    }

    @PostMapping("/navigate")
    public Map<String, String> navigate(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("url", "");
        if (!url.startsWith("http")) url = "https://" + url;
        String title = playwrightService.viewerNavigate(url);
        Map<String, String> info = playwrightService.viewerInfo();
        return Map.of("title", title, "url", info.get("url"));
    }

    @PostMapping("/back")
    public Map<String, String> back() {
        playwrightService.viewerBack();
        return playwrightService.viewerInfo();
    }

    @PostMapping("/forward")
    public Map<String, String> forward() {
        playwrightService.viewerForward();
        return playwrightService.viewerInfo();
    }

    @PostMapping("/refresh")
    public Map<String, String> refresh() {
        playwrightService.viewerRefresh();
        return playwrightService.viewerInfo();
    }
}
