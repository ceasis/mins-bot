package com.minsbot;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.minsbot.agent.tools.AudioListeningTools;
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

    public ChatController(ChatService chatService, TranscriptService transcriptService,
                          ScreenWatchingTools screenWatchingTools,
                          AudioListeningTools audioListeningTools,
                          ScreenClickTools screenClickTools,
                          TtsTools ttsTools) {
        this.chatService = chatService;
        this.transcriptService = transcriptService;
        this.screenWatchingTools = screenWatchingTools;
        this.audioListeningTools = audioListeningTools;
        this.screenClickTools = screenClickTools;
        this.ttsTools = ttsTools;
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

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        String message = body != null ? body.get("message") : null;
        String reply = chatService.getReply(message);
        if (reply != null && reply.equals(ChatService.QUIT_REPLY)) {
            return Map.of("reply", reply, "quitCountdownSeconds", 30);
        }
        return Map.of("reply", reply != null ? reply : "");
    }

    /** Poll for async agent results (background tasks like file collection). */
    @GetMapping(value = "/chat/async", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> pollAsync() {
        String result = chatService.pollAsyncResult();
        if (result != null) {
            return Map.of("hasResult", true, "reply", result);
        }
        return Map.of("hasResult", false);
    }

    /** Clear AI conversation memory and transcript history. Called by the UI "Clear chat" button. */
    @PostMapping(value = "/chat/clear", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> clearChat() {
        chatService.clearChatMemory();
        return Map.of("status", "ok");
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
        return Map.of("x", p.x, "y", p.y);
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
}
