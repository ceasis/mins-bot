package com.minsbot;

import com.minsbot.agent.ScreenMemoryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** UI surface for browsing the screen-capture memory folder. */
@RestController
@RequestMapping("/api/screen-memory")
public class ScreenMemoryController {

    private static final Path SCREENSHOT_ROOT =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "screenshots");

    private final ScreenMemoryService screenMemory;
    private final ScreenshotService screenshotService;

    public ScreenMemoryController(ScreenMemoryService screenMemory,
                                   ScreenshotService screenshotService) {
        this.screenMemory = screenMemory;
        this.screenshotService = screenshotService;
    }

    @GetMapping(value = "/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> settings() {
        return Map.of(
                "enabled", screenshotService.isEnabled(),
                "intervalSeconds", screenshotService.getIntervalSeconds(),
                "maxAgeDays", screenshotService.getMaxAgeDays()
        );
    }

    @PostMapping(value = "/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> body) {
        if (body != null) {
            Object en = body.get("enabled");
            if (en != null) screenshotService.setEnabled(Boolean.parseBoolean(en.toString()));
            Object iv = body.get("intervalSeconds");
            if (iv != null) { try { screenshotService.setIntervalSeconds(Integer.parseInt(iv.toString())); } catch (NumberFormatException ignored) {} }
            Object ma = body.get("maxAgeDays");
            if (ma != null) { try { screenshotService.setMaxAgeDays(Integer.parseInt(ma.toString())); } catch (NumberFormatException ignored) {} }
        }
        return settings();
    }

    /** Folder layout: screenshots/YYYY_MMM/dd/*.png. Return newest day first. */
    @GetMapping(value = "/days", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> days() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(SCREENSHOT_ROOT)) return out;
        try (Stream<Path> months = Files.list(SCREENSHOT_ROOT)) {
            List<Path> monthDirs = months.filter(Files::isDirectory).toList();
            for (Path monthDir : monthDirs) {
                try (Stream<Path> days = Files.list(monthDir)) {
                    days.filter(Files::isDirectory).forEach(dayDir -> {
                        long count;
                        long bytes;
                        try (Stream<Path> pngs = Files.list(dayDir)) {
                            List<Path> files = pngs.filter(p -> p.toString().endsWith(".png")).toList();
                            count = files.size();
                            bytes = files.stream().mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum();
                        } catch (IOException e) { return; }
                        if (count == 0) return;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("month", monthDir.getFileName().toString());   // "2026_Feb"
                        m.put("day", dayDir.getFileName().toString());       // "23"
                        m.put("count", count);
                        m.put("bytes", bytes);
                        out.add(m);
                    });
                }
            }
        } catch (IOException ignored) {}
        out.sort(Comparator.<Map<String, Object>, String>comparing(m -> (String) m.get("month"))
                .thenComparing(m -> (String) m.get("day"))
                .reversed());
        return out;
    }

    /** List screenshot filenames for a specific day. */
    @GetMapping(value = "/files", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> files(@RequestParam String month, @RequestParam String day) {
        Path dir = SCREENSHOT_ROOT.resolve(safeSegment(month)).resolve(safeSegment(day));
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".png"))
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** OCR-extracted text for a date string (YYYY-MM-DD), piped through ScreenMemoryService. */
    @GetMapping(value = "/ocr", produces = MediaType.TEXT_PLAIN_VALUE)
    public String ocr(@RequestParam String date) {
        return screenMemory.readMemory(date);
    }

    @PostMapping(value = "/capture-now", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> captureNow() {
        String text = screenMemory.captureNow();
        return Map.of("ocr", text == null ? "" : text);
    }

    /** Path-traversal guard: only allow folder-name characters. */
    private static String safeSegment(String s) {
        if (s == null) return "";
        return s.replaceAll("[^A-Za-z0-9._-]", "");
    }
}
