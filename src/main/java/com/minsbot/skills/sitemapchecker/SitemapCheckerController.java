package com.minsbot.skills.sitemapchecker;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/sitemapchecker")
public class SitemapCheckerController {

    private final SitemapCheckerService service;
    private final SitemapCheckerConfig.SitemapCheckerProperties properties;

    public SitemapCheckerController(SitemapCheckerService service,
                                    SitemapCheckerConfig.SitemapCheckerProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "sitemapchecker",
                "enabled", properties.isEnabled(),
                "timeoutMs", properties.getTimeoutMs(),
                "maxUrlsToCheck", properties.getMaxUrlsToCheck()
        ));
    }

    @GetMapping("/check")
    public ResponseEntity<?> check(@RequestParam String sitemap,
                                   @RequestParam(defaultValue = "false") boolean checkStatus) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.check(sitemap, checkStatus));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "SitemapChecker skill is disabled"));
    }
}
