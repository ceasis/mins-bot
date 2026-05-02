package com.minsbot;

import com.minsbot.agent.tools.PlaywrightService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight web-search endpoints for the frontend (Sentry Mode image grid, etc.).
 *
 * <p>Image search delegates to {@link PlaywrightService#searchGoogleImages} which
 * renders the page with JS so Google's lazy-loaded grid actually populates. The
 * call is run on a CompletableFuture with a hard timeout so the UI never hangs.
 */
@RestController
@RequestMapping("/api/web-search")
public class WebSearchController {

    private static final Logger log = LoggerFactory.getLogger(WebSearchController.class);

    @Autowired(required = false)
    private PlaywrightService playwright;

    /**
     * Returns up to {@code n} image URLs for the query as JSON: {@code {"results":[...]}}
     * Falls back to {@code 503} if Playwright isn't available; the frontend handles
     * that by switching to a DuckDuckGo iframe.
     */
    @GetMapping(value = "/images", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> images(@RequestParam("q") String query,
                                      @RequestParam(value = "n", defaultValue = "24") int n) {
        if (query == null || query.isBlank()) {
            return Map.of("results", List.of(), "error", "missing query");
        }
        if (playwright == null) {
            return Map.of("results", List.of(), "error", "playwright unavailable");
        }
        int max = Math.max(1, Math.min(48, n));
        try {
            List<String> urls = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return playwright.searchGoogleImages(query, max);
                        } catch (Exception e) {
                            log.warn("[WebSearch/images] Playwright failed for '{}': {}", query, e.getMessage());
                            return List.<String>of();
                        }
                    })
                    .get(15, TimeUnit.SECONDS);

            return Map.of("results", urls == null ? List.of() : urls);
        } catch (Exception e) {
            log.warn("[WebSearch/images] timeout/failure for '{}': {}", query, e.getMessage());
            return Map.of("results", List.of(), "error", e.getMessage());
        }
    }
}
