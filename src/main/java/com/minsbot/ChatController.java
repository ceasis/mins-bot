package com.minsbot;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.minsbot.agent.AutoPilotService;
import com.minsbot.agent.ModuleStatsService;
import com.minsbot.agent.ProactiveActionService;
import com.minsbot.agent.tools.AudioListeningTools;
import com.minsbot.agent.tools.IntelligenceTools;
import com.minsbot.agent.tools.ScreenClickTools;
import com.minsbot.agent.tools.ScreenWatchingTools;
import com.minsbot.agent.tools.TtsTools;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    @org.springframework.beans.factory.annotation.Value("${app.version:unknown}")
    private String appVersion;

    private final ChatService chatService;
    private final TranscriptService transcriptService;
    private final ScreenWatchingTools screenWatchingTools;
    private final AudioListeningTools audioListeningTools;
    private final ScreenClickTools screenClickTools;
    private final TtsTools ttsTools;
    private final AutoPilotService autoPilotService;
    private final ProactiveActionService proactiveActionService;
    private final IntelligenceTools intelligenceTools;
    private final ModuleStatsService moduleStatsService;
    private final ChatEventPublisher chatEventPublisher;
    private final ChatNameGeneratorService chatNameGeneratorService;

    public ChatController(ChatService chatService, TranscriptService transcriptService,
                          ScreenWatchingTools screenWatchingTools,
                          AudioListeningTools audioListeningTools,
                          ScreenClickTools screenClickTools,
                          TtsTools ttsTools,
                          AutoPilotService autoPilotService,
                          ProactiveActionService proactiveActionService,
                          IntelligenceTools intelligenceTools,
                          ModuleStatsService moduleStatsService,
                          ChatEventPublisher chatEventPublisher,
                          ChatNameGeneratorService chatNameGeneratorService) {
        this.chatService = chatService;
        this.transcriptService = transcriptService;
        this.screenWatchingTools = screenWatchingTools;
        this.audioListeningTools = audioListeningTools;
        this.screenClickTools = screenClickTools;
        this.ttsTools = ttsTools;
        this.autoPilotService = autoPilotService;
        this.proactiveActionService = proactiveActionService;
        this.intelligenceTools = intelligenceTools;
        this.moduleStatsService = moduleStatsService;
        this.chatEventPublisher = chatEventPublisher;
        this.chatNameGeneratorService = chatNameGeneratorService;
    }

    @GetMapping(value = "/version", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> version() {
        return Map.of("version", appVersion);
    }

    /** Returns recent chat history for the frontend to display on load. */
    @GetMapping(value = "/chat/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chatHistory() {
        List<Map<String, Object>> messages = transcriptService.getStructuredHistory();
        return Map.of("messages", messages);
    }

    /**
     * Live chat stream (Server-Sent Events). One connection per window —
     * every save/clear in TranscriptService is pushed here in real time,
     * so the JavaFX WebView and browser tabs stay synchronized sub-second.
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream() {
        return chatEventPublisher.register();
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        String message = body != null ? body.get("message") : null;
        String reply = chatService.getReply(message);
        if (reply != null && reply.equals(ChatService.QUIT_REPLY)) {
            return Map.of("reply", reply, "quitCountdownSeconds", 30);
        }
        return Map.of("reply", reply != null ? reply : "");
    }

    /** Poll for async agent results (background tasks like file collection) and auto-pilot suggestions. */
    @GetMapping(value = "/chat/async", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> pollAsync() {
        String result = chatService.pollAsyncResult();
        if (result != null) {
            return Map.of("hasResult", true, "reply", result);
        }
        // Also check auto-pilot suggestions
        List<String> suggestions = autoPilotService.drainSuggestions();
        if (!suggestions.isEmpty()) {
            return Map.of("hasResult", true, "reply", String.join("\n", suggestions));
        }
        return Map.of("hasResult", false);
    }

    /** Clear AI conversation memory and transcript history. Called by the UI "Clear chat" button. */
    @PostMapping(value = "/chat/clear", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> clearChat() {
        chatService.clearChatMemory();
        return Map.of("status", "ok");
    }

    /**
     * Open any file path in the OS file manager, with the file selected
     * (or open the directory if {@code path} points to a folder). Used by the
     * chat UI's inline "open in Explorer" button next to any file path the
     * bot mentions in its reply.
     */
    @PostMapping(value = "/explorer/show", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> showInExplorer(@org.springframework.web.bind.annotation.RequestParam String path) {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(path).toAbsolutePath().normalize();
            if (!java.nio.file.Files.exists(p)) {
                return Map.of("ok", false, "error", "path not found: " + p);
            }
            boolean isDir = java.nio.file.Files.isDirectory(p);
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                if (isDir) new ProcessBuilder("explorer.exe", p.toString()).start();
                else       new ProcessBuilder("explorer.exe", "/select,", p.toString()).start();
            } else if (os.contains("mac")) {
                if (isDir) new ProcessBuilder("open", p.toString()).start();
                else       new ProcessBuilder("open", "-R", p.toString()).start();
            } else {
                new ProcessBuilder("xdg-open", (isDir ? p : p.getParent()).toString()).start();
            }
            return Map.of("ok", true, "path", p.toString(), "dir", isDir);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    /**
     * Open the containing folder of a generated image in the OS file manager,
     * with the file selected (Windows: {@code explorer /select,...}, macOS:
     * {@code open -R}, Linux: {@code xdg-open} on the parent dir).
     */
    @PostMapping(value = "/generated/{filename:.+}/show-in-explorer", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> showGeneratedInExplorer(@org.springframework.web.bind.annotation.PathVariable String filename) {
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            return Map.of("ok", false, "error", "invalid filename");
        }
        java.nio.file.Path dir = java.nio.file.Paths.get(
                System.getProperty("user.home"), "mins_bot_data", "generated").toAbsolutePath();
        java.nio.file.Path file = dir.resolve(filename).normalize();
        if (!file.startsWith(dir) || !java.nio.file.Files.exists(file)) {
            return Map.of("ok", false, "error", "file not found");
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", "/select,", file.toString()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", file.toString()).start();
            } else {
                new ProcessBuilder("xdg-open", file.getParent().toString()).start();
            }
            return Map.of("ok", true, "path", file.toString());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    /**
     * Serve a locally-generated image from {@code ~/mins_bot_data/generated/}.
     * Used by the chat UI to render inline <img> tags for images produced by
     * {@code LocalImageTools.generateLocalImage}. Path-traversal protected.
     */
    @GetMapping("/generated/{filename:.+}")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> servedImage(
            @org.springframework.web.bind.annotation.PathVariable String filename) {
        // Sanity: reject anything that could climb out of the generated directory.
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            return org.springframework.http.ResponseEntity.badRequest().build();
        }
        java.nio.file.Path dir = java.nio.file.Paths.get(
                System.getProperty("user.home"), "mins_bot_data", "generated").toAbsolutePath();
        java.nio.file.Path file = dir.resolve(filename).normalize();
        if (!file.startsWith(dir) || !java.nio.file.Files.exists(file) || !java.nio.file.Files.isRegularFile(file)) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        String lower = filename.toLowerCase();
        MediaType mt = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? MediaType.IMAGE_JPEG
                : lower.endsWith(".webp") ? MediaType.parseMediaType("image/webp")
                : MediaType.IMAGE_PNG;
        return org.springframework.http.ResponseEntity.ok()
                .contentType(mt)
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(30)))
                .body(new org.springframework.core.io.FileSystemResource(file.toFile()));
    }

    /**
     * Archive the current chat (save under a name) and start fresh.
     * Called by the "New chat" button in the title bar.
     * If no name is supplied, uses a small AI model to auto-name the chat from its content.
     */
    @PostMapping(value = "/chat/archive", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> archiveChat(@RequestBody(required = false) Map<String, String> body) {
        String name = body != null ? body.get("name") : null;
        if (name == null || name.isBlank()) {
            // Auto-name from current transcript using gpt-4o-mini
            List<String> recent = transcriptService.getRecentMemory();
            name = chatNameGeneratorService.generateName(recent);
        }
        java.nio.file.Path archived = transcriptService.archiveHistory(name);
        // Always reset AI memory so the new chat starts with a clean slate
        chatService.clearChatMemory();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("status", "ok");
        out.put("name", name);
        out.put("archived", archived != null);
        if (archived != null) out.put("file", archived.getFileName().toString());
        return out;
    }

    /** List archived chats, newest first. */
    @GetMapping(value = "/chat/archives", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> listChatArchives() {
        return Map.of("archives", transcriptService.listArchives());
    }

    /** Stop the bot's current processing. */
    @PostMapping(value = "/chat/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> stopProcessing() {
        chatService.requestStop();
        return Map.of("stopped", true);
    }

    /** Poll for tool execution status updates while a request is in-flight. */
    @GetMapping(value = "/chat/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> pollToolStatus() {
        List<String> messages = chatService.drainToolStatus();
        return Map.of("messages", messages);
    }

    /** Check if screen watch mode is active (used by frontend red eye indicator). */
    @GetMapping(value = "/status/watch-mode", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> watchModeStatus() {
        return Map.of("watching", screenWatchingTools.isWatching());
    }

    /** Drain pending watch-mode observations for the sticky live panel. */
    @GetMapping(value = "/status/watch-feed", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> watchFeed() {
        List<String> observations = screenWatchingTools.drainObservations();
        return Map.of("observations", observations);
    }

    /** Toggle watch mode on/off from the UI eye button. */
    @PostMapping(value = "/watch-mode/toggle", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> toggleWatchMode() {
        if (screenWatchingTools.isWatching()) {
            screenWatchingTools.stopScreenWatch();
            return Map.of("watching", false, "message", "Watch mode stopped.");
        } else {
            String result = screenWatchingTools.startScreenWatch("observe the screen and help the user", "click");
            return Map.of("watching", true, "message", result);
        }
    }

    /** Toggle keyboard/mouse control permission from the UI keyboard button. */
    @PostMapping(value = "/control-mode/toggle", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> toggleControlMode() {
        boolean newState = !screenWatchingTools.isControlEnabled();
        screenWatchingTools.setControlEnabled(newState);
        return Map.of("controlEnabled", newState);
    }

    /** Check if keyboard/mouse control is enabled. */
    @GetMapping(value = "/status/control-mode", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> controlModeStatus() {
        return Map.of("controlEnabled", screenWatchingTools.isControlEnabled());
    }

    /** Toggle listen mode on/off from the UI ear button. Accepts optional {duration} in body. */
    @PostMapping(value = "/listen-mode/toggle", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> toggleListenMode(@RequestBody(required = false) Map<String, Object> body) {
        if (audioListeningTools.isListening()) {
            audioListeningTools.stopListening();
            return Map.of("listening", false, "message", "Listen mode stopped.");
        } else {
            if (body != null && body.containsKey("duration")) {
                try {
                    int duration = ((Number) body.get("duration")).intValue();
                    audioListeningTools.setCaptureDuration(Math.max(1, Math.min(8, duration)));
                } catch (Exception ignored) {}
            }
            String result = audioListeningTools.startListening();
            return Map.of("listening", true, "message", result);
        }
    }

    /** Check if listen mode is active (used by frontend ear indicator). */
    @GetMapping(value = "/status/listen-mode", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> listenModeStatus() {
        return Map.of("listening", audioListeningTools.isListening(),
                       "engine", audioListeningTools.getEngine(),
                       "activeEngine", audioListeningTools.getActiveEngine());
    }

    /** Set the translation engine (Gemini model name or "whisper-gpt"). Takes effect on next listen session. */
    @PostMapping(value = "/listen-mode/model", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setListenModel(@RequestBody Map<String, String> body) {
        String engine = body != null ? body.get("model") : null;
        if (engine != null && !engine.isBlank()) {
            audioListeningTools.setEngine(engine);
        }
        return Map.of("engine", audioListeningTools.getEngine());
    }

    /** Drain pending listen-mode transcriptions for the UI feed. */
    @GetMapping(value = "/status/listen-feed", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> listenFeed() {
        List<String> transcriptions = audioListeningTools.drainTranscriptions();
        return Map.of("transcriptions", transcriptions);
    }

    /** Toggle mouth/vocal mode on/off from the UI mouth button. */
    @PostMapping(value = "/mouth-mode/toggle", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> toggleMouthMode() {
        boolean newState = !audioListeningTools.isVocalMode();
        audioListeningTools.setVocalMode(newState);
        return Map.of("active", newState);
    }

    /** Check if mouth/vocal mode is active. */
    @GetMapping(value = "/status/mouth-mode", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> mouthModeStatus() {
        return Map.of("active", audioListeningTools.isVocalMode());
    }

    /** Open a file or folder in the system file explorer. */
    @PostMapping(value = "/open-path", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> openPath(@RequestBody Map<String, String> body) {
        String path = body != null ? body.get("path") : null;
        if (path == null || path.isBlank()) {
            return Map.of("status", "error", "message", "No path provided");
        }
        try {
            Path p = Paths.get(path.trim()).toAbsolutePath();
            if (!Files.exists(p)) {
                return Map.of("status", "error", "message", "Path not found: " + p);
            }
            // For files, open the parent folder and select the file
            if (Files.isRegularFile(p)) {
                new ProcessBuilder("explorer.exe", "/select,", p.toString()).start();
            } else {
                Desktop.getDesktop().open(p.toFile());
            }
            return Map.of("status", "ok", "message", "Opened: " + p);
        } catch (Exception e) {
            return Map.of("status", "error", "message", "Failed: " + e.getMessage());
        }
    }

    @GetMapping("/mouse")
    public Map<String, Object> getMousePosition() {
        java.awt.Point p = java.awt.MouseInfo.getPointerInfo().getLocation();
        boolean inside = FloatingAppLauncher.isInsideWindow(p.x, p.y);
        return Map.of("x", p.x, "y", p.y, "insideBot", inside);
    }

    @GetMapping("/screen-info")
    public Map<String, Object> getScreenInfo() {
        java.awt.Dimension logical = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        double dpiScaleX = 1.0, dpiScaleY = 1.0;
        try {
            java.awt.geom.AffineTransform tx = java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().getDefaultTransform();
            dpiScaleX = tx.getScaleX();
            dpiScaleY = tx.getScaleY();
        } catch (Exception ignored) {}
        // Capture a test screenshot to check actual image size
        int imgW = 0, imgH = 0;
        try {
            java.awt.Rectangle rect = new java.awt.Rectangle(logical);
            java.awt.image.BufferedImage img = new java.awt.Robot().createScreenCapture(rect);
            imgW = img.getWidth();
            imgH = img.getHeight();
        } catch (Exception ignored) {}
        return Map.of(
            "logicalWidth", logical.width, "logicalHeight", logical.height,
            "dpiScaleX", dpiScaleX, "dpiScaleY", dpiScaleY,
            "capturedImgWidth", imgW, "capturedImgHeight", imgH,
            "physicalWidth", (int)(logical.width * dpiScaleX),
            "physicalHeight", (int)(logical.height * dpiScaleY)
        );
    }

    @PostMapping(value = "/calibrate/screenshot", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> calibrateScreenshot() {
        screenClickTools.setSkipHideWindow(true);
        try {
            String path = screenClickTools.takeCalibrationScreenshot();
            if (path != null) {
                return Map.of("success", true, "screenshotPath", path);
            }
            return Map.of("success", false, "message", "Failed to capture screenshot");
        } finally {
            screenClickTools.setSkipHideWindow(false);
        }
    }

    @GetMapping(value = "/engine-priority", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getEnginePriority() {
        return Map.of("priority", screenClickTools.getEnginePriority());
    }

    @SuppressWarnings("unchecked")
    @PostMapping(value = "/engine-priority", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setEnginePriority(@RequestBody Map<String, Object> body) {
        List<String> priority = (List<String>) body.get("priority");
        screenClickTools.setEnginePriority(priority);
        screenClickTools.saveConfigToFile();
        return Map.of("success", true, "priority", screenClickTools.getEnginePriority());
    }

    @GetMapping(value = "/calibration-engines", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getCalibrationEngines() {
        return Map.of("engines", screenClickTools.getCalibrationEngines());
    }

    @GetMapping(value = "/engine-enabled", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getEngineEnabled() {
        return Map.of("enabled", screenClickTools.getEngineEnabled());
    }

    @SuppressWarnings("unchecked")
    @PostMapping(value = "/engine-enabled", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setEngineEnabled(@RequestBody Map<String, Object> body) {
        Map<String, Boolean> enabled = new java.util.HashMap<>();
        Map<String, Object> input = (Map<String, Object>) body.get("enabled");
        if (input != null) {
            for (var entry : input.entrySet()) {
                enabled.put(entry.getKey(), Boolean.valueOf(entry.getValue().toString()));
            }
        }
        screenClickTools.setEngineEnabled(enabled);
        screenClickTools.saveConfigToFile();
        return Map.of("success", true, "enabled", screenClickTools.getEngineEnabled());
    }

    @SuppressWarnings("unchecked")
    @PostMapping(value = "/calibration-engines", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setCalibrationEngines(@RequestBody Map<String, Object> body) {
        List<String> engines = (List<String>) body.get("engines");
        screenClickTools.setCalibrationEngines(engines);
        screenClickTools.saveConfigToFile();
        return Map.of("success", true, "engines", screenClickTools.getCalibrationEngines());
    }

    @SuppressWarnings("unchecked")
    @PostMapping(value = "/calibrate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> calibrate(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items == null || items.isEmpty()) {
            return Map.of("success", false, "message", "No items provided");
        }
        List<String> engines = body.containsKey("engines") ? (List<String>) body.get("engines") : null;
        screenClickTools.setSkipHideWindow(true);
        try {
            return screenClickTools.runCalibration(items, engines);
        } finally {
            screenClickTools.setSkipHideWindow(false);
        }
    }

    @SuppressWarnings("unchecked")
    @PostMapping(value = "/calibrate/compare", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> calibrateCompare(@RequestBody Map<String, Object> body) {
        String screenshotPath = (String) body.get("screenshotPath");
        List<Map<String, Object>> comparisons = (List<Map<String, Object>>) body.get("comparisons");
        int threshold = body.containsKey("threshold") ? ((Number) body.get("threshold")).intValue() : 5;
        return screenClickTools.generateCalibrationComparison(screenshotPath, comparisons, threshold);
    }

    // ═══ Auto-pilot mode ═══

    /** Toggle auto-pilot on/off from the UI. */
    @PostMapping(value = "/autopilot/toggle", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> toggleAutoPilot() {
        if (autoPilotService.isEnabled()) {
            String msg = autoPilotService.stop();
            return Map.of("enabled", false, "message", msg);
        } else {
            String msg = autoPilotService.start();
            return Map.of("enabled", true, "message", msg);
        }
    }

    /** Check auto-pilot status. */
    @GetMapping(value = "/status/autopilot", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> autoPilotStatus() {
        return autoPilotService.getStatus();
    }

    /** Drain pending auto-pilot suggestions for the UI. */
    @GetMapping(value = "/status/autopilot-feed", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> autoPilotFeed() {
        List<String> suggestions = autoPilotService.drainSuggestions();
        return Map.of("suggestions", suggestions);
    }

    // ═══ Proactive Action Mode ═══

    /** Toggle proactive action mode on/off from the UI. */
    @PostMapping(value = "/proactive-action/toggle", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> toggleProactiveAction() {
        if (proactiveActionService.isActive()) {
            proactiveActionService.stop();
            return Map.of("active", false, "message", "Proactive action mode disabled.");
        } else {
            proactiveActionService.start();
            return Map.of("active", true, "message", "Proactive action mode enabled.");
        }
    }

    /** Check proactive action mode status. */
    @GetMapping(value = "/status/proactive-action", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> proactiveActionStatus() {
        return proactiveActionService.getStatus();
    }

    // ═══ Daily Briefing (on-demand, works from mobile) ═══

    /** Generate a daily briefing on demand. Accepts optional location in body. */
    @PostMapping(value = "/briefing", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> generateBriefing(@RequestBody(required = false) Map<String, String> body) {
        String location = body != null ? body.getOrDefault("location", "Manila") : "Manila";
        try {
            String briefing = intelligenceTools.generateDailyBriefing(location);
            return Map.of("success", true, "briefing", briefing != null ? briefing : "");
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /** GET version for easy mobile access. */
    @GetMapping(value = "/briefing", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getBriefing(@RequestParam(defaultValue = "Manila") String location) {
        try {
            String briefing = intelligenceTools.generateDailyBriefing(location);
            return Map.of("success", true, "briefing", briefing != null ? briefing : "");
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ═══ Module stats (vision/audio counters for status bar) ═══

    @GetMapping(value = "/status/modules", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> moduleStats() {
        return moduleStatsService.getStats();
    }
}
