package com.minsbot.agent.tools;

import com.minsbot.agent.ClaudeVisionService;
import com.minsbot.agent.DocumentAiService;
import com.minsbot.agent.MouseModule;
import com.minsbot.agent.RekognitionService;
import com.minsbot.agent.ScreenMemoryService;
import com.minsbot.agent.TextractService;
import com.minsbot.agent.VisionModelConfig;
import com.minsbot.agent.VisionService;
import com.minsbot.agent.ScreenMemoryService.OcrWord;
import com.minsbot.agent.SystemControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Screen-first click logic using Gemini Vision.
 * Takes a full screenshot, asks Gemini for the CLICKABLE element coordinates,
 * clicks it, and verifies via before/after pixel comparison.
 */
@Component
public class ScreenClickTools {

    private static final Logger log = LoggerFactory.getLogger(ScreenClickTools.class);

    private final SystemControlService systemControl;
    private final ScreenMemoryService screenMemoryService;
    private final DocumentAiService documentAiService;
    private final TextractService textractService;
    private final RekognitionService rekognitionService;
    private final VisionService visionService;
    private final ClaudeVisionService claudeVisionService;
    private final VisionModelConfig visionModelConfig;
    private final ToolExecutionNotifier notifier;
    private final MouseModule mouseModule;
    private final com.minsbot.agent.CursorDetector cursorDetector;

    private static final Path NAV_DIR = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "navigation_screenshots");

    private static final Path CONFIG_PATH = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "minsbot_config.txt");

    private volatile boolean skipHideWindow = true;

    public void setSkipHideWindow(boolean skip) {
        this.skipHideWindow = skip;
    }

    private static final int MAX_RETRIES = 3;

    // Engine priority for screenClick — tried in order, first success wins
    // (gemini and textract removed — using GPT Vision only for coordinate detection)
    private volatile List<String> enginePriority = new ArrayList<>(List.of(
            "gpt5.4", "gpt4o-mini", "ocr", "claude", "rek", "docai"
    ));

    // Calibration engine checkboxes — which engines are selected for calibration runs
    private volatile List<String> calibrationEngines = new ArrayList<>(List.of(
            "gpt5.4", "claude"
    ));

    // Per-engine enabled/disabled toggle — disabled engines are skipped in screenClick + navigate
    private static final List<String> ALL_ENGINE_KEYS = List.of(
            "gpt5.4", "gpt4o-mini", "ocr", "claude", "rek", "docai"
    );
    private volatile Map<String, Boolean> engineEnabled = new HashMap<>(Map.of(
            "gpt5.4", true, "gpt4o-mini", false, "ocr", true,
            "claude", true, "rek", true, "docai", true
    ));
    // Priority order per engine (1 = highest) — used to rebuild enginePriority from config
    private volatile Map<String, Integer> enginePriorityOrder = new HashMap<>();

    public List<String> getEnginePriority() { return new ArrayList<>(enginePriority); }

    /** Returns engine priority filtered to only enabled engines. */
    public List<String> getActiveEnginePriority() {
        List<String> active = new ArrayList<>();
        for (String e : enginePriority) {
            if (engineEnabled.getOrDefault(e, true)) active.add(e);
        }
        return active;
    }

    public void setEnginePriority(List<String> priority) {
        if (priority != null && !priority.isEmpty()) {
            this.enginePriority = new ArrayList<>(priority);
            // Sync priority order map
            for (int i = 0; i < priority.size(); i++) {
                enginePriorityOrder.put(priority.get(i), i + 1);
            }
            log.info("[screenClick] Engine priority updated: {}", this.enginePriority);
        }
    }

    public Map<String, Boolean> getEngineEnabled() { return new HashMap<>(engineEnabled); }

    public void setEngineEnabled(Map<String, Boolean> enabled) {
        if (enabled != null) {
            for (var entry : enabled.entrySet()) {
                engineEnabled.put(entry.getKey(), entry.getValue());
            }
            log.info("[screenClick] Engine enabled updated: {}", engineEnabled);
        }
    }

    public List<String> getCalibrationEngines() { return new ArrayList<>(calibrationEngines); }

    public void setCalibrationEngines(List<String> engines) {
        if (engines != null) {
            this.calibrationEngines = new ArrayList<>(engines);
            log.info("[screenClick] Calibration engines updated: {}", this.calibrationEngines);
        }
    }

    public ScreenClickTools(SystemControlService systemControl,
                            ScreenMemoryService screenMemoryService,
                            DocumentAiService documentAiService,
                            TextractService textractService,
                            RekognitionService rekognitionService,
                            VisionService visionService,
                            ClaudeVisionService claudeVisionService,
                            VisionModelConfig visionModelConfig,
                            ToolExecutionNotifier notifier,
                            MouseModule mouseModule,
                            com.minsbot.agent.CursorDetector cursorDetector) {
        this.systemControl = systemControl;
        this.screenMemoryService = screenMemoryService;
        this.documentAiService = documentAiService;
        this.textractService = textractService;
        this.rekognitionService = rekognitionService;
        this.visionService = visionService;
        this.claudeVisionService = claudeVisionService;
        this.visionModelConfig = visionModelConfig;
        this.notifier = notifier;
        this.mouseModule = mouseModule;
        this.cursorDetector = cursorDetector;
    }

    @PostConstruct
    void initOnStartup() {
        loadConfigFromFile();
        clearScreenshotsOnStartup();
    }

    public void reloadConfig() {
        loadConfigFromFile();
        log.info("[screenClick] Config reloaded — priority={}, enabled={}, calibrationEngines={}",
                enginePriority, engineEnabled, calibrationEngines);
    }

    private void loadConfigFromFile() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            String currentSection = "";
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("## ")) {
                    currentSection = trimmed.toLowerCase();
                    continue;
                }
                if (!currentSection.equals("## vision engines")) continue;
                if (!trimmed.startsWith("- ")) continue;

                String kv = trimmed.substring(2).trim();
                int colon = kv.indexOf(':');
                if (colon < 0) continue;
                String key = kv.substring(0, colon).trim().toLowerCase();
                String val = kv.substring(colon + 1).trim();

                switch (key) {
                    case "calibration_engines" -> {
                        List<String> parsed = new ArrayList<>();
                        for (String s : val.split(",")) {
                            String e = s.trim();
                            if (!e.isEmpty()) parsed.add(e);
                        }
                        calibrationEngines = parsed;
                    }
                    default -> {
                        // Per-engine config: e.g. "gpt5: true, 1" (enabled, priority 1)
                        // Legacy: "gpt" maps to "gpt5.4"
                        String resolvedKey = "gpt".equals(key) ? "gpt5.4" : key;
                        if (ALL_ENGINE_KEYS.contains(resolvedKey)) {
                            key = resolvedKey;
                            String[] parts = val.split(",");
                            boolean enabled = parts[0].trim().equalsIgnoreCase("true");
                            engineEnabled.put(key, enabled);
                            if (parts.length >= 2) {
                                try {
                                    int prio = Integer.parseInt(parts[1].trim());
                                    enginePriorityOrder.put(key, prio);
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
            }
            // Rebuild enginePriority from priority order numbers
            if (!enginePriorityOrder.isEmpty()) {
                List<String> sorted = new ArrayList<>(ALL_ENGINE_KEYS);
                sorted.sort((a, b) -> {
                    int pa = enginePriorityOrder.getOrDefault(a, 99);
                    int pb = enginePriorityOrder.getOrDefault(b, 99);
                    return Integer.compare(pa, pb);
                });
                enginePriority = sorted;
            }
        } catch (IOException e) {
            log.warn("[screenClick] Could not read config: {}", e.getMessage());
        }
    }

    /** Save vision engine settings to the ## Vision engines section of minsbot_config.txt */
    public void saveConfigToFile() {
        try {
            List<String> lines = Files.exists(CONFIG_PATH) ? new ArrayList<>(Files.readAllLines(CONFIG_PATH)) : new ArrayList<>();

            // Remove existing ## Vision engines section
            int sectionStart = -1;
            int sectionEnd = -1;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.equalsIgnoreCase("## Vision engines")) {
                    sectionStart = i;
                } else if (sectionStart >= 0 && sectionEnd < 0 && trimmed.startsWith("## ")) {
                    sectionEnd = i;
                }
            }
            if (sectionStart >= 0) {
                if (sectionEnd < 0) sectionEnd = lines.size();
                lines.subList(sectionStart, sectionEnd).clear();
                // Remove trailing blank line if present
                if (sectionStart < lines.size() && lines.get(sectionStart).isBlank()) {
                    lines.remove(sectionStart);
                }
            }

            // Append new section
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add("## Vision engines (enabled, priority — e.g. true, 1 = enabled + 1st priority)");
            for (int i = 0; i < enginePriority.size(); i++) {
                String eng = enginePriority.get(i);
                boolean enabled = engineEnabled.getOrDefault(eng, true);
                lines.add("- " + eng + ": " + enabled + ", " + (i + 1));
            }
            // Include any engines not in priority list
            for (String eng : ALL_ENGINE_KEYS) {
                if (!enginePriority.contains(eng)) {
                    boolean enabled = engineEnabled.getOrDefault(eng, true);
                    lines.add("- " + eng + ": " + enabled + ", " + (enginePriority.size() + 1));
                }
            }
            lines.add("- calibration_engines: " + String.join(",", calibrationEngines));

            Files.createDirectories(CONFIG_PATH.getParent());
            Files.write(CONFIG_PATH, lines);
            log.info("[screenClick] Config saved to {}", CONFIG_PATH);
        } catch (IOException e) {
            log.warn("[screenClick] Could not save config: {}", e.getMessage());
        }
    }

    private void clearScreenshotsOnStartup() {
        try {
            if (Files.exists(NAV_DIR)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(NAV_DIR)) {
                    int count = 0;
                    for (Path file : stream) {
                        Files.deleteIfExists(file);
                        count++;
                    }
                    log.info("[screenClick] Cleared {} old screenshots from {}", count, NAV_DIR);
                }
            }
        } catch (IOException e) {
            log.warn("[screenClick] Failed to clear screenshots: {}", e.getMessage());
        }
    }

    // ═══ Public tools ═══

    @Tool(description = "Scan the ENTIRE screen for a UI element and click it. "
            + "Takes a full screenshot, uses vision engines to find the CLICKABLE element (not labels), "
            + "then moves the mouse and clicks. Verifies the click worked via screen change detection. "
            + "ALWAYS call this FIRST — do NOT focus/switch/open apps before calling this. "
            + "If this returns 'NOT_FOUND', THEN switch to the correct app and call again. "
            + "CRITICAL: Always pass the EXACT FULL TEXT of the button/link. If there are two similar buttons "
            + "like 'Submit As Human' and 'Submit As Bot', pass the COMPLETE label 'Submit As Bot' — "
            + "NEVER just 'Submit'. When buttons share words, use the FULL distinguishing text. "
            + "IMPORTANT: If you need to click multiple elements in sequence (e.g. 'click History then Shorts then Music'), "
            + "use screenNavigate('History, Shorts, Music') instead — it has tree-based backtracking for when "
            + "there are multiple instances of an element and clicking the wrong one leads to a dead end. "
            + "Example: screenClick('Pricing') — screenshot, finds it, clicks it.")
    public String screenClick(
            @ToolParam(description = "The EXACT visible text of the element to click. Use FULL button text to avoid ambiguity — e.g. 'Submit As Bot' not just 'Submit', 'Sign In with Google' not just 'Sign In'")
            String targetText) {

        String search = targetText.trim();
        notifier.notify("Scanning screen for '" + search + "'...");
        log.info("[screenClick] Looking for '{}'", search);

        if (!skipHideWindow) hideMinsBotWindow();
        try {
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                java.awt.Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
                int sw = screenSize.width, sh = screenSize.height;

                // ── Step 1: Full screenshot → try engines in priority order ──
                Path fullShot = captureFullScreen();
                if (fullShot == null) return "ERROR: Failed to capture screenshot.";

                int[] coords = null;
                String usedEngine = null;
                for (String eng : getActiveEnginePriority()) {
                    notifier.notify("__vision__Asking " + engineDisplayName(eng) + " to locate '" + search + "'"
                            + (attempt > 1 ? " (attempt " + attempt + ")" : "") + "...");
                    notifier.notify("Step 1: Asking " + engineDisplayName(eng) + " to locate '" + search + "'"
                            + (attempt > 1 ? " (attempt " + attempt + ")" : "") + "...");
                    coords = findWithEngine(eng, fullShot, search, sw, sh);
                    if (coords != null) { usedEngine = eng; break; }
                    log.info("[screenClick] '{}' not found by {} — trying next engine", search, engineDisplayName(eng));
                }

                if (coords == null) {
                    notifier.notify("'" + search + "' not found by any engine");
                    return "NOT_FOUND: '" + search + "' is not visible on screen. "
                            + "If you clicked something BEFORE this that led to the wrong page, "
                            + "use screenNavigate('previous_click, " + search + "') to automatically "
                            + "try all instances of the previous element with backtracking. "
                            + "Otherwise, switch to the correct app and try again.";
                }

                int targetX = coords[0], targetY = coords[1];
                annotateScreenshot(fullShot, targetX, targetY, search);
                log.info("[screenClick] Step 1 (full): '{}' at ({},{}) via {}", search, targetX, targetY, engineDisplayName(usedEngine));
                notifier.notify("Found '" + search + "' via " + engineDisplayName(usedEngine) + " at (" + targetX + ", " + targetY + ")");

                // ── Step 2: 1/4 screen zoom → verify + refine with marker (disabled) ──
                // int[] step2 = zoomVerify(search, targetX, targetY, sw / 2, sh / 2, "step2_quarter");
                // if (step2 != null) {
                //     targetX = step2[0];
                //     targetY = step2[1];
                // }
                // notifier.notify("Step 2 (1/4 zoom): (" + targetX + ", " + targetY + ")");

                // ── Step 3: 1/8 screen zoom → final precise verify (disabled) ──
                // int[] step3 = zoomVerify(search, targetX, targetY, sw / 4, sh / 4, "step3_eighth");
                // if (step3 != null) {
                //     targetX = step3[0];
                //     targetY = step3[1];
                // }
                // notifier.notify("Step 3 (1/8 zoom): (" + targetX + ", " + targetY + ")");

                // ── Step 4: Smart hover probe + Click ──
                if (com.minsbot.FloatingAppLauncher.isInsideWindow(targetX, targetY)) {
                    return "SKIPPED: Target (" + targetX + "," + targetY + ") is inside the Mins Bot window. "
                            + "Move or minimize the bot window first, or use a different element.";
                }

                // Smart hover probe: move mouse, detect hover effects to confirm button
                int[] confirmed = hoverProbe(targetX, targetY, search);
                if (confirmed != null) {
                    targetX = confirmed[0];
                    targetY = confirmed[1];
                }

                notifier.notify("Left button clicked '" + search + "' at (" + targetX + ", " + targetY + ")");
                BufferedImage before = captureRegion(targetX, targetY);
                mouseModule.click(targetX, targetY);

                try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

                BufferedImage after = captureRegion(targetX, targetY);
                double changePercent = compareImages(before, after);
                log.info("[screenClick] Screen change: {}%", String.format("%.1f", changePercent));

                if (changePercent > 5.0) {
                    notifier.notify("'" + search + "' clicked — screen changed " + String.format("%.0f", changePercent) + "%");
                    return "OK: Clicked '" + search + "' at (" + targetX + ", " + targetY
                            + "), screen changed " + String.format("%.0f", changePercent) + "%.";
                }

                notifier.notify("Click at (" + targetX + ", " + targetY + ") no screen change — retrying...");
                log.info("[screenClick] No change at ({},{}), attempt {}/{}", targetX, targetY, attempt, MAX_RETRIES);
            }

            notifier.notify("'" + search + "' — all " + MAX_RETRIES + " attempts failed");
            return "CLICKED_BUT_NO_CHANGE: Clicked '" + search + "' " + MAX_RETRIES
                    + " times at different locations but none caused a screen change.";
        } finally {
            if (!skipHideWindow) showMinsBotWindow();
        }
    }

    @Tool(description = "Fill a web form by tabbing through fields and typing values. MUCH faster and more reliable "
            + "than clicking each field individually. Pass a pipe-separated list of values in TAB ORDER "
            + "(the order the fields appear top-to-bottom in the form). "
            + "Clicks the FIRST field to focus it, then types value → Tab → types next value → Tab → ... "
            + "Detects focus changes via pixel comparison after each Tab to confirm the cursor moved. "
            + "Use this for ANY form with multiple input fields (registration, login, search filters, etc.). "
            + "Example: fillFormByTab('Email Address', 'bob@demo.org|555-3456|Martinez|Hannah|28') fills 5 fields. "
            + "IMPORTANT: Read the form carefully and provide values in the EXACT order the fields appear.")
    public String fillFormByTab(
            @ToolParam(description = "Text/label of the FIRST input field to click to start, e.g. 'Email Address', 'First Name'")
            String firstFieldLabel,
            @ToolParam(description = "Pipe-separated values in tab order, e.g. 'bob@demo.org|555-3456|Martinez|Hannah|28'")
            String pipeValues) {

        if (pipeValues == null || pipeValues.isBlank()) return "No values provided.";
        String[] values = pipeValues.split("\\|");
        if (values.length == 0) return "No values to fill.";

        notifier.notify("Filling form: " + values.length + " fields via Tab navigation...");
        log.info("[fillFormByTab] Starting with '{}', {} values", firstFieldLabel, values.length);

        try {
            Robot robot = new Robot();
            java.awt.Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();

            // Step 1: Click the first field to focus it
            notifier.notify("Clicking first field: " + firstFieldLabel);
            String clickResult = screenClick(firstFieldLabel);
            if (clickResult != null && clickResult.startsWith("NOT_FOUND")) {
                // Fallback: try clicking generic "first input" area
                return "Could not find first field '" + firstFieldLabel + "'. " + clickResult;
            }

            // Small delay to let the field focus
            Thread.sleep(300);

            // Step 2: For each value, type it and Tab to the next field
            StringBuilder result = new StringBuilder();
            int filled = 0;

            for (int i = 0; i < values.length; i++) {
                String value = values[i].trim();
                if (value.isEmpty()) {
                    // Empty value — just Tab to skip this field
                    robot.keyPress(java.awt.event.KeyEvent.VK_TAB);
                    robot.keyRelease(java.awt.event.KeyEvent.VK_TAB);
                    Thread.sleep(150);
                    result.append("Field ").append(i + 1).append(": (skipped)\n");
                    continue;
                }

                // Triple-click to select field content (safer than Ctrl+A which selects the whole page)
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(20);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(20);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(50);

                // Paste via clipboard (much faster and supports special chars)
                java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(value);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                Thread.sleep(30);
                robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
                robot.keyPress(java.awt.event.KeyEvent.VK_V);
                robot.keyRelease(java.awt.event.KeyEvent.VK_V);
                robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
                Thread.sleep(100);

                filled++;
                notifier.notify("Field " + (i + 1) + "/" + values.length + ": typed '" + value + "'");
                result.append("Field ").append(i + 1).append(": '").append(value).append("'\n");

                // Tab to next field (unless this is the last value)
                if (i < values.length - 1) {
                    // Capture region BEFORE Tab
                    BufferedImage beforeTab = captureSmallRegion(robot, screen);

                    robot.keyPress(java.awt.event.KeyEvent.VK_TAB);
                    robot.keyRelease(java.awt.event.KeyEvent.VK_TAB);
                    Thread.sleep(200);

                    // Capture region AFTER Tab — verify focus moved
                    BufferedImage afterTab = captureSmallRegion(robot, screen);
                    double tabChange = compareSmallRegion(beforeTab, afterTab);

                    if (tabChange < 0.1) {
                        // Tab didn't seem to change anything — try Tab again
                        log.info("[fillFormByTab] Tab had no visible effect ({}%), pressing Tab again",
                                String.format("%.1f", tabChange));
                        robot.keyPress(java.awt.event.KeyEvent.VK_TAB);
                        robot.keyRelease(java.awt.event.KeyEvent.VK_TAB);
                        Thread.sleep(200);
                    } else {
                        log.debug("[fillFormByTab] Tab moved focus ({}% change)", String.format("%.1f", tabChange));
                    }
                }
            }

            notifier.notify("Form filled: " + filled + "/" + values.length + " fields");
            log.info("[fillFormByTab] Done: {}/{} fields filled", filled, values.length);

            return "OK: Filled " + filled + " of " + values.length + " fields via Tab navigation.\n" + result;

        } catch (Exception e) {
            log.warn("[fillFormByTab] Error: {}", e.getMessage(), e);
            return "Form fill failed: " + e.getMessage();
        }
    }

    /** Capture a 200x200 region around the current mouse position. */
    private BufferedImage captureSmallRegion(Robot robot, java.awt.Dimension screen) {
        try {
            java.awt.Point mouse = java.awt.MouseInfo.getPointerInfo().getLocation();
            int size = 200;
            int rx = Math.max(0, Math.min(mouse.x - size / 2, screen.width - size));
            int ry = Math.max(0, Math.min(mouse.y - size / 2, screen.height - size));
            return robot.createScreenCapture(new Rectangle(rx, ry, size, size));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Capture a calibration screenshot and return its path as a string.
     * Called from the frontend per-button so the button is still visible on screen.
     */
    public String takeCalibrationScreenshot() {
        Path screenshot = captureFullScreen();
        return screenshot != null ? screenshot.toString() : null;
    }

    /**
     * Calibration: query ALL engines for coords of each target using per-item screenshots.
     * Each item must have "label" and "screenshotPath" keys.
     * Returns each engine's predicted coordinates — does NOT move the mouse.
     */
    public Map<String, Object> runCalibration(List<Map<String, Object>> items) {
        return runCalibration(items, null);
    }

    public Map<String, Object> runCalibration(List<Map<String, Object>> items, List<String> selectedEngines) {
        // If no engines specified, run all available
        java.util.Set<String> engineSet = (selectedEngines != null && !selectedEngines.isEmpty())
                ? new java.util.HashSet<>(selectedEngines) : null;

        log.info("[calibration] Starting for {} targets, engines: {}", items.size(),
                engineSet != null ? engineSet : "ALL");
        java.awt.Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int sw = screenSize.width, sh = screenSize.height;

        java.awt.geom.AffineTransform tx = java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getDefaultTransform();
        double dpiScaleX = tx.getScaleX();
        double dpiScaleY = tx.getScaleY();
        log.info("[calibration] DPI scale: {}x{} (logical screen: {}x{})", dpiScaleX, dpiScaleY, sw, sh);

        // Log engine availability
        List<String> availableEngines = new ArrayList<>();
        if (engineSet == null || engineSet.contains("gemini")) availableEngines.add("Gemini");
        if (engineSet == null || engineSet.contains("docai")) {
            if (documentAiService.isAvailable()) availableEngines.add("DocumentAI");
            else log.info("[calibration] Document AI not available (missing config)");
        }
        if (engineSet == null || engineSet.contains("textract")) {
            if (textractService.isAvailable()) availableEngines.add("Textract");
            else log.info("[calibration] Textract not available (missing config)");
        }
        if (engineSet == null || engineSet.contains("rek")) {
            if (rekognitionService.isAvailable()) availableEngines.add("Rekognition");
            else log.info("[calibration] Rekognition not available (missing config)");
        }
        if (engineSet == null || engineSet.contains("ocr")) availableEngines.add("WindowsOCR");
        if (engineSet == null || engineSet.contains("gpt5.4") || engineSet.contains("gpt")) {
            if (visionService.isAvailable()) availableEngines.add("GPT-5.4");
            else log.info("[calibration] GPT-5.4 not available (missing OpenAI key)");
        }
        if (engineSet == null || engineSet.contains("gpt4o-mini")) {
            if (visionService.isAvailable()) availableEngines.add("GPT-4o Mini");
            else log.info("[calibration] GPT-4o Mini not available (missing OpenAI key)");
        }
        if (engineSet == null || engineSet.contains("gemini3")) availableEngines.add("Gemini3.1Pro");
        if (engineSet == null || engineSet.contains("claude")) {
            if (claudeVisionService.isAvailable()) availableEngines.add("Claude");
            else log.info("[calibration] Claude not available (missing Anthropic key)");
        }
        log.info("[calibration] Available engines: {}", availableEngines);

        List<Map<String, Object>> results = new ArrayList<>();
        String lastScreenshotPath = null;

        for (Map<String, Object> item : items) {
            String label = (String) item.get("label");
            String searchLabel = item.containsKey("searchLabel") ? (String) item.get("searchLabel") : label;
            String ssPath = (String) item.get("screenshotPath");
            log.info("[calibration] Locating '{}' (search='{}') with all engines (screenshot: {})", label, searchLabel, ssPath);

            Path screenshot = Paths.get(ssPath);
            lastScreenshotPath = ssPath;

            Map<String, Object> entry = new HashMap<>();
            entry.put("label", label);

            // Read actual image dimensions (physical pixels) for engines
            int imgW = sw, imgH = sh;
            try {
                java.awt.image.BufferedImage ssImg = javax.imageio.ImageIO.read(screenshot.toFile());
                if (ssImg != null) { imgW = ssImg.getWidth(); imgH = ssImg.getHeight(); }
            } catch (Exception ignored) {}
            log.info("[calibration] '{}' screenshot {}x{} (logical {}x{}, scale {}x{})",
                    label, imgW, imgH, sw, sh, dpiScaleX, dpiScaleY);

            // Gemini 2.5 — pass actual image dimensions, then scale result to logical
            if (engineSet == null || engineSet.contains("gemini")) {
                try {
                    long t0 = System.currentTimeMillis();
                    int[] gc = askVisionForCoords(screenshot, searchLabel, imgW, imgH, 1);
                    long geminiMs = System.currentTimeMillis() - t0;
                    entry.put("geminiMs", geminiMs);
                    if (gc != null) {
                        int gx = (int) (gc[0] / dpiScaleX);
                        int gy = (int) (gc[1] / dpiScaleY);
                        entry.put("geminiX", gx);
                        entry.put("geminiY", gy);
                        log.info("[calibration] '{}' Gemini: ({},{}) raw→({},{}) scaled in {}ms", label, gc[0], gc[1], gx, gy, geminiMs);
                    }
                } catch (Exception e) {
                    log.warn("[calibration] '{}' Gemini failed: {}", label, e.getMessage());
                }
            }

            // Document AI
            if (engineSet == null || engineSet.contains("docai")) {
                try {
                    if (documentAiService.isAvailable()) {
                        long t0 = System.currentTimeMillis();
                        double[] dc = documentAiService.findTextOnScreen(screenshot, searchLabel);
                        long docaiMs = System.currentTimeMillis() - t0;
                        entry.put("docaiMs", docaiMs);
                        if (dc != null) {
                            int dx = (int) (dc[0] / dpiScaleX);
                            int dy = (int) (dc[1] / dpiScaleY);
                            entry.put("docaiX", dx);
                            entry.put("docaiY", dy);
                            log.info("[calibration] '{}' DocAI: ({},{}) raw→({},{}) scaled in {}ms", label, (int) dc[0], (int) dc[1], dx, dy, docaiMs);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[calibration] '{}' DocAI failed: {}", label, e.getMessage());
                }
            }

            // Textract
            if (engineSet == null || engineSet.contains("textract")) {
                try {
                    if (textractService.isAvailable()) {
                        long t0 = System.currentTimeMillis();
                        double[] tc = textractService.findTextOnScreen(screenshot, searchLabel);
                        long textractMs = System.currentTimeMillis() - t0;
                        entry.put("textractMs", textractMs);
                        if (tc != null) {
                            int ttx = (int) (tc[0] / dpiScaleX);
                            int tty = (int) (tc[1] / dpiScaleY);
                            entry.put("textractX", ttx);
                            entry.put("textractY", tty);
                            log.info("[calibration] '{}' Textract: ({},{}) raw→({},{}) scaled in {}ms", label, (int) tc[0], (int) tc[1], ttx, tty, textractMs);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[calibration] '{}' Textract failed: {}", label, e.getMessage());
                }
            }

            // Rekognition
            if (engineSet == null || engineSet.contains("rek")) {
                try {
                    if (rekognitionService.isAvailable()) {
                        long t0 = System.currentTimeMillis();
                        double[] rc = rekognitionService.findTextOnScreen(screenshot, searchLabel);
                        long rekMs = System.currentTimeMillis() - t0;
                        entry.put("rekMs", rekMs);
                        if (rc != null) {
                            int rx = (int) (rc[0] / dpiScaleX);
                            int ry = (int) (rc[1] / dpiScaleY);
                            entry.put("rekX", rx);
                            entry.put("rekY", ry);
                            log.info("[calibration] '{}' Rekognition: ({},{}) raw→({},{}) scaled in {}ms", label, (int) rc[0], (int) rc[1], rx, ry, rekMs);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[calibration] '{}' Rekognition failed: {}", label, e.getMessage());
                }
            }

            // Windows OCR
            if (engineSet == null || engineSet.contains("ocr")) {
                try {
                    long t0 = System.currentTimeMillis();
                    double[] oc = screenMemoryService.findTextOnScreen(screenshot, searchLabel);
                    long ocrMs = System.currentTimeMillis() - t0;
                    entry.put("ocrMs", ocrMs);
                    if (oc != null) {
                        int ox = (int) (oc[0] / dpiScaleX);
                        int oy = (int) (oc[1] / dpiScaleY);
                        entry.put("ocrX", ox);
                        entry.put("ocrY", oy);
                        log.info("[calibration] '{}' WinOCR: ({},{}) raw→({},{}) scaled in {}ms", label, (int) oc[0], (int) oc[1], ox, oy, ocrMs);
                    }
                } catch (Exception e) {
                    log.warn("[calibration] '{}' WinOCR failed: {}", label, e.getMessage());
                }
            }

            // GPT-5.4 — pass actual image dimensions, then scale result to logical
            if (engineSet == null || engineSet.contains("gpt5.4") || engineSet.contains("gpt")) {
                try {
                    if (visionService.isAvailable()) {
                        long t0 = System.currentTimeMillis();
                        int[] gptCoords = visionService.findElementCoordinates(screenshot, searchLabel, imgW, imgH, visionModelConfig.getPrimaryModel());
                        long gptMs = System.currentTimeMillis() - t0;
                        entry.put("gpt5Ms", gptMs);
                        if (gptCoords != null) {
                            int gx = (int) (gptCoords[0] / dpiScaleX);
                            int gy = (int) (gptCoords[1] / dpiScaleY);
                            entry.put("gpt5X", gx);
                            entry.put("gpt5Y", gy);
                            log.info("[calibration] '{}' GPT-5.4: ({},{}) raw→({},{}) scaled in {}ms", label, gptCoords[0], gptCoords[1], gx, gy, gptMs);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[calibration] '{}' GPT-5.4 failed: {}", label, e.getMessage());
                }
            }

            // GPT-4o Mini — lighter/cheaper vision model
            if (engineSet != null && engineSet.contains("gpt4o-mini")) {
                try {
                    if (visionService.isAvailable()) {
                        long t0 = System.currentTimeMillis();
                        int[] gpt4oCoords = visionService.findElementCoordinates(screenshot, searchLabel, imgW, imgH, visionModelConfig.getFastModel());
                        long gpt4oMs = System.currentTimeMillis() - t0;
                        entry.put("gpt4oMs", gpt4oMs);
                        if (gpt4oCoords != null) {
                            int gx = (int) (gpt4oCoords[0] / dpiScaleX);
                            int gy = (int) (gpt4oCoords[1] / dpiScaleY);
                            entry.put("gpt4oX", gx);
                            entry.put("gpt4oY", gy);
                            log.info("[calibration] '{}' GPT-4o Mini: ({},{}) raw→({},{}) scaled in {}ms", label, gpt4oCoords[0], gpt4oCoords[1], gx, gy, gpt4oMs);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[calibration] '{}' GPT-4o Mini failed: {}", label, e.getMessage());
                }
            }

            // Gemini 3.1 Pro — pass actual image dimensions, then scale result to logical
            if (engineSet == null || engineSet.contains("gemini3")) {
                try {
                    long t0 = System.currentTimeMillis();
                    int[] g3Coords = askVisionForCoords(screenshot, searchLabel, imgW, imgH, 1, "gemini-3.1-pro-preview");
                    long g3Ms = System.currentTimeMillis() - t0;
                    entry.put("gemini3Ms", g3Ms);
                    if (g3Coords != null) {
                        int gx = (int) (g3Coords[0] / dpiScaleX);
                        int gy = (int) (g3Coords[1] / dpiScaleY);
                        entry.put("gemini3X", gx);
                        entry.put("gemini3Y", gy);
                        log.info("[calibration] '{}' Gemini3.1: ({},{}) raw→({},{}) scaled in {}ms", label, g3Coords[0], g3Coords[1], gx, gy, g3Ms);
                    }
                } catch (Exception e) {
                    log.warn("[calibration] '{}' Gemini 3.1 failed: {}", label, e.getMessage());
                }
            }

            // Claude Sonnet 4.6 — pass actual image dimensions, then scale result to logical
            if (engineSet == null || engineSet.contains("claude")) {
                try {
                    if (claudeVisionService.isAvailable()) {
                        long t0 = System.currentTimeMillis();
                        int[] clCoords = claudeVisionService.findElementCoordinates(screenshot, searchLabel, imgW, imgH);
                        long clMs = System.currentTimeMillis() - t0;
                        entry.put("claudeMs", clMs);
                        if (clCoords != null) {
                            int cx = (int) (clCoords[0] / dpiScaleX);
                            int cy = (int) (clCoords[1] / dpiScaleY);
                            entry.put("claudeX", cx);
                            entry.put("claudeY", cy);
                            log.info("[calibration] '{}' Claude: ({},{}) raw→({},{}) scaled in {}ms", label, clCoords[0], clCoords[1], cx, cy, clMs);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[calibration] '{}' Claude failed: {}", label, e.getMessage());
                }
            }

            entry.put("found", entry.containsKey("geminiX") || entry.containsKey("docaiX")
                    || entry.containsKey("textractX") || entry.containsKey("rekX")
                    || entry.containsKey("ocrX") || entry.containsKey("gptX")
                    || entry.containsKey("gemini3X") || entry.containsKey("claudeX"));
            results.add(entry);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("total", items.size());
        response.put("results", results);
        if (lastScreenshotPath != null) response.put("screenshotPath", lastScreenshotPath);
        response.put("engines", availableEngines);

        log.info("[calibration] Done for {} targets", items.size());
        return response;
    }

    /**
     * Calibration step 2: compare all engine coords vs user clicks.
     * Generates comparison image + Excel spreadsheet.
     * comparisons[].keys: label, userX, userY, geminiX, geminiY, docaiX, docaiY, textractX, textractY, ocrX, ocrY
     */
    public Map<String, Object> generateCalibrationComparison(
            String screenshotPath, List<Map<String, Object>> comparisons, int threshold) {
        try {
            Path ssPath = Paths.get(screenshotPath);
            BufferedImage img = javax.imageio.ImageIO.read(ssPath.toFile());
            java.awt.Graphics2D g = img.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new java.awt.BasicStroke(3f));

            List<Map<String, Object>> details = new ArrayList<>();
            String[] engines = {"docai", "rek", "ocr", "gpt5.4", "gpt4o-mini", "claude"};
            String[] engineLabels = {"Document AI", "Rekognition", "Windows OCR", "GPT-5.4", "GPT-4o Mini", "Claude Opus 4.6"};
            java.awt.Color[] engineColors = {
                java.awt.Color.RED,
                new java.awt.Color(255, 165, 0),   // orange
                new java.awt.Color(138, 43, 226),   // purple
                new java.awt.Color(255, 215, 0),    // gold
                new java.awt.Color(0, 191, 255),     // cyan
                new java.awt.Color(0, 255, 127),     // spring green (GPT-5.4)
                new java.awt.Color(144, 238, 144),   // light green (GPT-4o Mini)
                new java.awt.Color(255, 105, 180),   // hot pink
                new java.awt.Color(217, 119, 6)      // amber
            };

            for (Map<String, Object> comp : comparisons) {
                String label = (String) comp.get("label");
                int ux = ((Number) comp.get("userX")).intValue();
                int uy = ((Number) comp.get("userY")).intValue();

                // Green + for user click
                g.setColor(new java.awt.Color(34, 197, 94));
                g.drawLine(ux - 12, uy, ux + 12, uy);
                g.drawLine(ux, uy - 12, ux, uy + 12);

                Map<String, Object> detail = new HashMap<>();
                detail.put("label", label);
                detail.put("userX", ux);
                detail.put("userY", uy);

                for (int e = 0; e < engines.length; e++) {
                    String xKey = engines[e] + "X", yKey = engines[e] + "Y";
                    if (comp.containsKey(xKey) && comp.get(xKey) != null) {
                        int ex = ((Number) comp.get(xKey)).intValue();
                        int ey = ((Number) comp.get(yKey)).intValue();
                        double dist = Math.sqrt(Math.pow(ex - ux, 2) + Math.pow(ey - uy, 2));

                        // Draw engine crosshair
                        g.setColor(engineColors[e]);
                        g.drawLine(ex - 10, ey, ex + 10, ey);
                        g.drawLine(ex, ey - 10, ex, ey + 10);

                        detail.put(engines[e] + "X", ex);
                        detail.put(engines[e] + "Y", ey);
                        detail.put(engines[e] + "Dist", (int) dist);

                        log.info("[calibration] '{}' {} ({},{}) dist={}px", label, engineLabels[e], ex, ey, (int) dist);
                    }
                    // Carry over timing data
                    String msKey = engines[e] + "Ms";
                    if (comp.containsKey(msKey) && comp.get(msKey) != null) {
                        detail.put(msKey, ((Number) comp.get(msKey)).longValue());
                    }
                }

                // Label near user click
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 10));
                g.setColor(new java.awt.Color(34, 197, 94));
                g.drawString(label, ux + 15, uy - 4);

                details.add(detail);
            }

            g.dispose();

            // Save comparison image
            Files.createDirectories(NAV_DIR);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
            String imgFilename = "calibration_comparison_" + ts + ".png";
            Path imgPath = NAV_DIR.resolve(imgFilename);
            javax.imageio.ImageIO.write(img, "png", imgPath.toFile());

            // Encode as base64
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", baos);
            String base64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());

            // Generate Excel
            String excelPath = generateCalibrationExcel(details, engines, engineLabels, threshold, ts);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("details", details);
            response.put("image", "data:image/png;base64," + base64);
            response.put("imagePath", imgPath.toString());
            response.put("excelPath", excelPath);

            log.info("[calibration] Comparison done, Excel: {}", excelPath);
            return response;

        } catch (Exception e) {
            log.warn("[calibration] Comparison failed: {}", e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * Generate Excel spreadsheet comparing all engines.
     */
    private String generateCalibrationExcel(List<Map<String, Object>> details,
            String[] engines, String[] engineLabels, int threshold, String ts) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Calibration");

            // Header styles
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle goodStyle = wb.createCellStyle();
            Font goodFont = wb.createFont();
            goodFont.setColor(IndexedColors.GREEN.getIndex());
            goodFont.setBold(true);
            goodStyle.setFont(goodFont);

            CellStyle badStyle = wb.createCellStyle();
            Font badFont = wb.createFont();
            badFont.setColor(IndexedColors.RED.getIndex());
            badFont.setBold(true);
            badStyle.setFont(badFont);

            // Headers: Label | User (x,y) | Gemini (x,y) | Gemini Dist | DocAI (x,y) | DocAI Dist | ...
            Row header = sheet.createRow(0);
            int col = 0;
            String[] colHeaders = {"Label", "User (x,y)"};
            for (String h : colHeaders) {
                Cell c = header.createCell(col++);
                c.setCellValue(h);
                c.setCellStyle(headerStyle);
            }
            for (String el : engineLabels) {
                Cell c1 = header.createCell(col++);
                c1.setCellValue(el + " (x,y)");
                c1.setCellStyle(headerStyle);
                Cell c2 = header.createCell(col++);
                c2.setCellValue(el + " Dist");
                c2.setCellStyle(headerStyle);
                Cell c3 = header.createCell(col++);
                c3.setCellValue(el + " Time");
                c3.setCellStyle(headerStyle);
            }

            // Data rows
            int[] engineHits = new int[engines.length];
            int[] engineTotalDist = new int[engines.length];
            int[] engineCount = new int[engines.length];
            long[] engineTotalMs = new long[engines.length];
            int[] engineMsCount = new int[engines.length];

            for (int r = 0; r < details.size(); r++) {
                Map<String, Object> d = details.get(r);
                Row row = sheet.createRow(r + 1);
                col = 0;
                row.createCell(col++).setCellValue((String) d.get("label"));
                row.createCell(col++).setCellValue(d.get("userX") + ", " + d.get("userY"));

                for (int e = 0; e < engines.length; e++) {
                    String xk = engines[e] + "X", dk = engines[e] + "Dist", mk = engines[e] + "Ms";
                    if (d.containsKey(xk)) {
                        row.createCell(col++).setCellValue(d.get(xk) + ", " + d.get(engines[e] + "Y"));
                        int dist = ((Number) d.get(dk)).intValue();
                        Cell distCell = row.createCell(col++);
                        distCell.setCellValue(dist + "px");
                        distCell.setCellStyle(dist <= threshold ? goodStyle : badStyle);
                        engineTotalDist[e] += dist;
                        engineCount[e]++;
                        if (dist <= threshold) engineHits[e]++;
                    } else {
                        row.createCell(col++).setCellValue("—");
                        row.createCell(col++).setCellValue("—");
                    }
                    if (d.containsKey(mk)) {
                        long ms = ((Number) d.get(mk)).longValue();
                        row.createCell(col++).setCellValue(String.format("%.1fs", ms / 1000.0));
                        engineTotalMs[e] += ms;
                        engineMsCount[e]++;
                    } else {
                        row.createCell(col++).setCellValue("—");
                    }
                }
            }

            // Summary row
            Row summary = sheet.createRow(details.size() + 2);
            summary.createCell(0).setCellValue("AVERAGE");
            Cell emptyCell = summary.createCell(1);
            emptyCell.setCellValue("");
            col = 2;
            for (int e = 0; e < engines.length; e++) {
                Cell hitsCell = summary.createCell(col++);
                hitsCell.setCellValue(engineHits[e] + "/" + engineCount[e] + " hits");
                hitsCell.setCellStyle(headerStyle);
                Cell avgCell = summary.createCell(col++);
                if (engineCount[e] > 0) {
                    avgCell.setCellValue(Math.round(engineTotalDist[e] / (double) engineCount[e]) + "px avg");
                } else {
                    avgCell.setCellValue("N/A");
                }
                avgCell.setCellStyle(headerStyle);
                Cell timeCell = summary.createCell(col++);
                if (engineMsCount[e] > 0) {
                    timeCell.setCellValue(String.format("%.1fs avg", engineTotalMs[e] / (double) engineMsCount[e] / 1000.0));
                } else {
                    timeCell.setCellValue("—");
                }
                timeCell.setCellStyle(headerStyle);
            }

            // Auto-size columns
            for (int i = 0; i < col; i++) sheet.autoSizeColumn(i);

            // Write file
            String filename = "calibration_" + ts + ".xlsx";
            Path excelPath = NAV_DIR.resolve(filename);
            try (FileOutputStream fos = new FileOutputStream(excelPath.toFile())) {
                wb.write(fos);
            }
            log.info("[calibration] Excel saved to {}", excelPath);
            return excelPath.toString();

        } catch (Exception e) {
            log.warn("[calibration] Excel generation failed: {}", e.getMessage());
            return null;
        }
    }

    @Tool(description = "Navigate a multi-step click path with tree-based backtracking. "
            + "Give a comma-separated sequence of UI elements to click in order, e.g. 'History, Shorts, Music'. "
            + "For each step, finds ALL instances of the target text using OCR + vision engines. "
            + "If the next target is not visible after clicking one instance, it backtracks and tries the next. "
            + "Uses a tree structure: multiple candidates at each step form branches, tried depth-first. "
            + "Example: screenNavigate('History, Shorts, Music')")
    public String screenNavigate(
            @ToolParam(description = "Comma-separated click targets in order, e.g. 'History, Shorts, Music'")
            String path) {

        String[] steps = path.split(",");
        for (int i = 0; i < steps.length; i++) steps[i] = steps[i].trim();

        notifier.notify("Navigating path: " + String.join(" → ", steps));
        log.info("[screenNavigate] Path: {}", String.join(" → ", steps));

        if (!skipHideWindow) hideMinsBotWindow();
        try {
            return navigateStep(steps, 0);
        } finally {
            if (!skipHideWindow) showMinsBotWindow();
        }
    }

    // ═══ Navigate with tree-based backtracking ═══

    private static final int MAX_CANDIDATES = 5;

    /**
     * Tree-based navigation: for each step, discover ALL candidate click locations
     * (via OCR + vision), try each one depth-first. If a deeper step fails, backtrack
     * to this step and try the next candidate.
     */
    private String navigateStep(String[] steps, int stepIdx) {
        if (stepIdx >= steps.length) {
            return "OK: Full path completed: " + String.join(" → ", steps);
        }

        String target = steps[stepIdx];
        boolean isLastStep = (stepIdx == steps.length - 1);
        notifier.notify("Step " + (stepIdx + 1) + "/" + steps.length + ": looking for '" + target + "'...");

        java.awt.Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int sw = screenSize.width, sh = screenSize.height;

        // Take screenshot
        Path screenshot = captureFullScreen();
        if (screenshot == null) return "ERROR: Failed to capture screenshot at step " + (stepIdx + 1);

        // Discover ALL candidate locations using OCR
        List<int[]> candidates = new ArrayList<>();
        List<double[]> ocrMatches = screenMemoryService.findAllTextOnScreen(screenshot, target);
        for (double[] m : ocrMatches) {
            candidates.add(new int[]{(int) m[0], (int) m[1]});
        }

        // Also try vision engines — may find candidates OCR missed
        for (String eng : getActiveEnginePriority()) {
            int[] vc = findWithEngine(eng, screenshot, target, sw, sh);
            if (vc != null) {
                // Only add if not near an existing candidate
                boolean dupe = false;
                for (int[] c : candidates) {
                    if (Math.abs(c[0] - vc[0]) < 40 && Math.abs(c[1] - vc[1]) < 40) {
                        dupe = true;
                        break;
                    }
                }
                if (!dupe) candidates.add(vc);
                break; // One vision engine result is enough
            }
        }

        if (candidates.isEmpty()) {
            notifier.notify("'" + target + "' not found at step " + (stepIdx + 1));
            return "NOT_FOUND: '" + target + "' not visible at step " + (stepIdx + 1)
                    + " of [" + String.join(" → ", steps) + "]. Switch to the correct app and try again.";
        }

        // Limit candidates to avoid combinatorial explosion
        if (candidates.size() > MAX_CANDIDATES) {
            candidates = candidates.subList(0, MAX_CANDIDATES);
        }

        log.info("[screenNavigate] Step {}: '{}' has {} candidate(s)", stepIdx + 1, target, candidates.size());

        // Try each candidate — depth-first tree traversal
        for (int ci = 0; ci < candidates.size(); ci++) {
            int[] coords = candidates.get(ci);
            int[] refined = refineWithOcr(target, coords[0], coords[1]);
            int clickX = refined[0], clickY = refined[1];

            notifier.notify("Step " + (stepIdx + 1) + ": trying '" + target + "' candidate "
                    + (ci + 1) + "/" + candidates.size() + " at (" + clickX + ", " + clickY + ")");
            log.info("[screenNavigate] Step {}: clicking '{}' candidate {}/{} at ({},{})",
                    stepIdx + 1, target, ci + 1, candidates.size(), clickX, clickY);

            annotateScreenshot(screenshot, clickX, clickY, target + " [" + (ci + 1) + "/" + candidates.size() + "]");

            // Click the candidate
            BufferedImage before = captureRegion(clickX, clickY);
            systemControl.mouseClick(clickX, clickY, "left");
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            BufferedImage after = captureRegion(clickX, clickY);
            double changePercent = compareImages(before, after);

            if (isLastStep) {
                if (changePercent > 5.0) {
                    notifier.notify("Path complete: " + String.join(" → ", steps));
                    return "OK: Full path completed: " + String.join(" → ", steps)
                            + ". Last click at (" + clickX + ", " + clickY + ").";
                }
                // No screen change — this candidate didn't work, try next
                log.info("[screenNavigate] Last step candidate {}/{} no screen change", ci + 1, candidates.size());
                continue;
            }

            // Not last step — check if next target is now visible
            String nextTarget = steps[stepIdx + 1];
            notifier.notify("Checking if '" + nextTarget + "' is visible...");

            Path afterScreenshot = captureFullScreen();
            if (afterScreenshot != null) {
                // Quick check: can we find the next target?
                boolean nextFound = false;
                for (String eng : getActiveEnginePriority()) {
                    if (findWithEngine(eng, afterScreenshot, nextTarget, sw, sh) != null) {
                        nextFound = true;
                        break;
                    }
                }
                if (!nextFound) {
                    // Also check via OCR
                    List<double[]> nextOcr = screenMemoryService.findAllTextOnScreen(afterScreenshot, nextTarget);
                    nextFound = !nextOcr.isEmpty();
                }

                if (nextFound) {
                    // Next target visible — recurse deeper
                    notifier.notify("'" + nextTarget + "' found after candidate " + (ci + 1) + " — continuing...");
                    String result = navigateStep(steps, stepIdx + 1);
                    if (result.startsWith("OK:")) return result;
                    // Deeper step failed — need to backtrack
                    log.info("[screenNavigate] Deeper step failed after candidate {}/{} of '{}', backtracking",
                            ci + 1, candidates.size(), target);
                } else {
                    log.info("[screenNavigate] '{}' not found after clicking '{}' candidate {}/{}",
                            nextTarget, target, ci + 1, candidates.size());
                }
            }

            // Backtrack: try to return to the state before we clicked this candidate
            if (ci < candidates.size() - 1) {
                boolean restored = backtrackToStep(steps, stepIdx, sw, sh);
                if (!restored) {
                    log.warn("[screenNavigate] Could not backtrack to step {} — giving up", stepIdx + 1);
                    break;
                }
                // Re-capture screenshot for next candidate (screen state may have changed)
                screenshot = captureFullScreen();
                if (screenshot == null) break;

                // Re-discover candidates from new screenshot (positions may shift)
                candidates = new ArrayList<>();
                ocrMatches = screenMemoryService.findAllTextOnScreen(screenshot, target);
                for (double[] m : ocrMatches) {
                    candidates.add(new int[]{(int) m[0], (int) m[1]});
                }
                if (candidates.size() > MAX_CANDIDATES) {
                    candidates = candidates.subList(0, MAX_CANDIDATES);
                }
                // Remove already-tried candidates (within 40px of what we already clicked)
                int finalClickX = clickX, finalClickY = clickY;
                candidates.removeIf(c -> Math.abs(c[0] - finalClickX) < 40 && Math.abs(c[1] - finalClickY) < 40);
            }
        }

        notifier.notify("All candidates for '" + target + "' exhausted at step " + (stepIdx + 1));
        return "BACKTRACK_FAILED: Tried " + candidates.size() + " locations for '" + target
                + "' at step " + (stepIdx + 1) + " but none led to '" + steps[Math.min(stepIdx + 1, steps.length - 1)] + "'.";
    }

    /**
     * Backtrack to a given step by re-navigating from the beginning up to (but not including) stepIdx.
     * Strategy: try Alt+Left first (browser back), then if that doesn't work,
     * re-click the previous steps from step 0.
     */
    private boolean backtrackToStep(String[] steps, int stepIdx, int sw, int sh) {
        // Strategy 1: Alt+Left (browser back) — fast and usually works
        notifier.notify("Backtracking (Alt+Left)...");
        systemControl.sendKeys("%{LEFT}");
        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        // Verify: is the current step's target still visible?
        Path screenshot = captureFullScreen();
        if (screenshot != null) {
            List<double[]> matches = screenMemoryService.findAllTextOnScreen(screenshot, steps[stepIdx]);
            if (!matches.isEmpty()) {
                log.info("[screenNavigate] Backtrack via Alt+Left succeeded — '{}' visible with {} candidates",
                        steps[stepIdx], matches.size());
                return true;
            }
        }

        // Strategy 2: re-click previous steps from the beginning
        if (stepIdx > 0) {
            notifier.notify("Alt+Left didn't restore — re-clicking from step 1...");
            log.info("[screenNavigate] Alt+Left failed, re-navigating steps 0..{}", stepIdx - 1);

            for (int i = 0; i < stepIdx; i++) {
                screenshot = captureFullScreen();
                if (screenshot == null) return false;

                int[] coords = null;
                for (String eng : getActiveEnginePriority()) {
                    coords = findWithEngine(eng, screenshot, steps[i], sw, sh);
                    if (coords != null) break;
                }
                if (coords == null) {
                    // Also try OCR
                    double[] ocrResult = screenMemoryService.findTextOnScreen(screenshot, steps[i]);
                    if (ocrResult != null) coords = new int[]{(int) ocrResult[0], (int) ocrResult[1]};
                }
                if (coords == null) {
                    log.warn("[screenNavigate] Cannot re-find '{}' during backtrack re-navigation", steps[i]);
                    return false;
                }

                systemControl.mouseClick(coords[0], coords[1], "left");
                try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }

            // Verify target is visible
            screenshot = captureFullScreen();
            if (screenshot != null) {
                List<double[]> matches = screenMemoryService.findAllTextOnScreen(screenshot, steps[stepIdx]);
                if (!matches.isEmpty()) {
                    log.info("[screenNavigate] Backtrack via re-navigation succeeded");
                    return true;
                }
            }
        }

        log.warn("[screenNavigate] All backtrack strategies failed for step {}", stepIdx + 1);
        return false;
    }

    // ═══ Screenshot helpers ═══

    /** Capture full screen and save to navigation_screenshots. */
    private Path captureFullScreen() {
        try {
            java.awt.Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            Robot robot = new Robot();
            BufferedImage img = robot.createScreenCapture(new Rectangle(0, 0, screen.width, screen.height));
            SystemControlService.drawCursorOnImage(img, 0, 0);

            Files.createDirectories(NAV_DIR);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
            Path file = NAV_DIR.resolve("screen_" + ts + ".png");
            javax.imageio.ImageIO.write(img, "png", file.toFile());

            log.info("[screenClick] Captured full screen {}x{} → {}", screen.width, screen.height, file.getFileName());
            return file;
        } catch (Exception e) {
            log.warn("[screenClick] captureFullScreen failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Smart hover probe: moves the mouse to the target, checks for hover effects
     * (color changes, cursor changes). If nothing happens at the exact coords,
     * spirals outward in a 100px radius checking nearby positions for interactive elements.
     * Returns the confirmed clickable position, or null to use the original coords.
     */
    private int[] hoverProbe(int targetX, int targetY, String search) {
        try {
            Robot robot = new Robot();
            java.awt.Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int probeRadius = 100;

            // 1. Move to exact target and check for hover effect
            int[] result = probeAt(robot, targetX, targetY, screen);
            if (result != null) {
                log.info("[hoverProbe] Hover effect confirmed at original ({},{})", targetX, targetY);
                return null; // original coords are good
            }

            // 2. No hover effect at exact location — spiral outward checking nearby
            log.info("[hoverProbe] No hover effect at ({},{}), probing nearby...", targetX, targetY);
            notifier.notify("__vision__Probing nearby for '" + search + "'...");

            // Spiral pattern: check in expanding rings at 15px steps
            int[][] offsets = {
                // Ring 1: 15px
                {0, -15}, {15, 0}, {0, 15}, {-15, 0},
                {10, -10}, {10, 10}, {-10, 10}, {-10, -10},
                // Ring 2: 30px
                {0, -30}, {30, 0}, {0, 30}, {-30, 0},
                {21, -21}, {21, 21}, {-21, 21}, {-21, -21},
                // Ring 3: 50px
                {0, -50}, {50, 0}, {0, 50}, {-50, 0},
                {35, -35}, {35, 35}, {-35, 35}, {-35, -35},
                // Ring 4: 75px
                {0, -75}, {75, 0}, {0, 75}, {-75, 0},
                {53, -53}, {53, 53}, {-53, 53}, {-53, -53},
                // Ring 5: 100px
                {0, -100}, {100, 0}, {0, 100}, {-100, 0},
            };

            for (int[] off : offsets) {
                int px = targetX + off[0];
                int py = targetY + off[1];
                // Bounds check
                if (px < 5 || py < 5 || px >= screen.width - 5 || py >= screen.height - 5) continue;
                // Skip if inside bot window
                if (com.minsbot.FloatingAppLauncher.isInsideWindow(px, py)) continue;

                result = probeAt(robot, px, py, screen);
                if (result != null) {
                    log.info("[hoverProbe] Hover effect found at ({},{}) — offset ({},{}) from target",
                            px, py, off[0], off[1]);
                    notifier.notify("__vision__Button found at (" + px + "," + py + ") via hover probe");
                    return new int[]{px, py};
                }
            }

            log.info("[hoverProbe] No hover effect detected in {}px radius, using original coords", probeRadius);
            return null; // no hover found, use original

        } catch (Exception e) {
            log.warn("[hoverProbe] Failed: {}, using original coords", e.getMessage());
            return null;
        }
    }

    /**
     * Move mouse to (x,y), wait for hover effect, check cursor type AND pixel changes.
     * Returns {x,y} if interactive cursor detected (hand/ibeam) or pixel change >0.5%.
     */
    private int[] probeAt(Robot robot, int x, int y, java.awt.Dimension screen) {
        try {
            int regionSize = 100;
            int rx = Math.max(0, Math.min(x - regionSize / 2, screen.width - regionSize));
            int ry = Math.max(0, Math.min(y - regionSize / 2, screen.height - regionSize));

            // Capture BEFORE moving mouse there
            BufferedImage before = robot.createScreenCapture(
                    new Rectangle(rx, ry, regionSize, regionSize));

            // Move mouse to the probe point
            robot.mouseMove(x, y);
            robot.delay(150); // wait for hover effect + cursor change

            // Check cursor type via native Win32 API (most reliable signal)
            if (cursorDetector.isAvailable()) {
                var cursorType = cursorDetector.getCurrentCursor();
                if (cursorType == com.minsbot.agent.CursorDetector.CursorType.HAND) {
                    log.info("[hoverProbe] HAND cursor at ({},{}) — clickable button/link", x, y);
                    return new int[]{x, y};
                }
                if (cursorType == com.minsbot.agent.CursorDetector.CursorType.IBEAM) {
                    log.info("[hoverProbe] IBEAM cursor at ({},{}) — text input field", x, y);
                    return new int[]{x, y};
                }
            }

            // Fallback: pixel comparison for hover effects (color/highlight changes)
            BufferedImage after = robot.createScreenCapture(
                    new Rectangle(rx, ry, regionSize, regionSize));
            double change = compareSmallRegion(before, after);

            if (change > 0.5) {
                log.debug("[hoverProbe] Hover pixel change at ({},{}): {}%", x, y, String.format("%.1f", change));
                return new int[]{x, y};
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Fast pixel comparison for small regions. Returns % of pixels changed. */
    private double compareSmallRegion(BufferedImage a, BufferedImage b) {
        if (a == null || b == null) return 0;
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        int changed = 0, total = w * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c1 = a.getRGB(x, y), c2 = b.getRGB(x, y);
                if (c1 != c2) {
                    // Only count as "changed" if the color difference is significant
                    int dr = Math.abs(((c1 >> 16) & 0xFF) - ((c2 >> 16) & 0xFF));
                    int dg = Math.abs(((c1 >> 8) & 0xFF) - ((c2 >> 8) & 0xFF));
                    int db = Math.abs((c1 & 0xFF) - (c2 & 0xFF));
                    if (dr + dg + db > 30) changed++; // threshold to ignore anti-aliasing noise
                }
            }
        }
        return total == 0 ? 0.0 : (changed * 100.0 / total);
    }

    /** Capture a 500x400 region around a screen point. */
    private BufferedImage captureRegion(int centerX, int centerY) {
        try {
            java.awt.Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int regionW = 500, regionH = 400;
            int rx = Math.max(0, Math.min(centerX - regionW / 2, screen.width - regionW));
            int ry = Math.max(0, Math.min(centerY - regionH / 2, screen.height - regionH));
            return new Robot().createScreenCapture(new Rectangle(rx, ry, regionW, regionH));
        } catch (Exception e) {
            log.warn("[screenClick] captureRegion failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Refine Gemini's approximate coordinates using local OCR.
     * Captures a 300x200 region around the Gemini coords, runs OCR,
     * finds the closest matching word, and returns its exact center.
     * Falls back to original coords if OCR can't improve.
     */
    private int[] refineWithOcr(String searchText, int geminiX, int geminiY) {
        try {
            java.awt.Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int regionW = 300, regionH = 200;
            int rx = Math.max(0, Math.min(geminiX - regionW / 2, screen.width - regionW));
            int ry = Math.max(0, Math.min(geminiY - regionH / 2, screen.height - regionH));

            Robot robot = new Robot();
            BufferedImage regionImg = robot.createScreenCapture(new Rectangle(rx, ry, regionW, regionH));

            Files.createDirectories(NAV_DIR);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
            Path imgFile = NAV_DIR.resolve("refine_" + ts + ".png");
            javax.imageio.ImageIO.write(regionImg, "png", imgFile.toFile());

            List<OcrWord> words = screenMemoryService.runOcrWithBounds(imgFile);
            if (words.isEmpty()) return new int[]{geminiX, geminiY};

            String searchLower = searchText.toLowerCase();

            // Find the closest matching word to the Gemini point
            OcrWord bestMatch = null;
            double bestDist = Double.MAX_VALUE;

            for (OcrWord word : words) {
                boolean textMatch = word.text().equalsIgnoreCase(searchText)
                        || word.text().toLowerCase().contains(searchLower)
                        || searchLower.contains(word.text().toLowerCase());
                if (!textMatch) continue;

                double wordCenterX = word.x() + word.width() / 2.0;
                double wordCenterY = word.y() + word.height() / 2.0;
                // Distance from Gemini's point (relative to region)
                double relGeminiX = geminiX - rx;
                double relGeminiY = geminiY - ry;
                double dist = Math.sqrt(Math.pow(wordCenterX - relGeminiX, 2) + Math.pow(wordCenterY - relGeminiY, 2));

                if (dist < bestDist) {
                    bestDist = dist;
                    bestMatch = word;
                }
            }

            if (bestMatch != null) {
                int refinedX = rx + (int) Math.round(bestMatch.x() + bestMatch.width() / 2.0);
                int refinedY = ry + (int) Math.round(bestMatch.y() + bestMatch.height() * 0.65);
                log.info("[screenClick] OCR refined ({},{}) → ({},{}) text='{}' dist={}px",
                        geminiX, geminiY, refinedX, refinedY, bestMatch.text(), String.format("%.0f", bestDist));
                return new int[]{refinedX, refinedY};
            }

            // Also try multi-word: join consecutive words and check
            for (int j = 0; j < words.size(); j++) {
                StringBuilder joined = new StringBuilder(words.get(j).text());
                for (int k = j + 1; k < Math.min(j + 5, words.size()); k++) {
                    joined.append(" ").append(words.get(k).text());
                    if (joined.toString().toLowerCase().contains(searchLower)) {
                        // Compute bounding box of the range j..k
                        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = 0, maxY = 0;
                        for (int m = j; m <= k; m++) {
                            OcrWord mw = words.get(m);
                            minX = Math.min(minX, mw.x());
                            minY = Math.min(minY, mw.y());
                            maxX = Math.max(maxX, mw.x() + mw.width());
                            maxY = Math.max(maxY, mw.y() + mw.height());
                        }
                        int refinedX = rx + (int) Math.round(minX + (maxX - minX) / 2.0);
                        int refinedY = ry + (int) Math.round(minY + (maxY - minY) * 0.65);
                        log.info("[screenClick] OCR refined (multi-word) ({},{}) → ({},{}) text='{}'",
                                geminiX, geminiY, refinedX, refinedY, joined);
                        return new int[]{refinedX, refinedY};
                    }
                }
            }

            log.info("[screenClick] OCR refinement found no match near ({},{}), using Gemini coords", geminiX, geminiY);
            return new int[]{geminiX, geminiY};
        } catch (Exception e) {
            log.warn("[screenClick] refineWithOcr failed: {}, using Gemini coords", e.getMessage());
            return new int[]{geminiX, geminiY};
        }
    }

    /** Compare two images pixel-by-pixel. Returns percentage of pixels that changed (0-100). */
    private double compareImages(BufferedImage before, BufferedImage after) {
        if (before == null || after == null) return 100.0;
        int w = Math.min(before.getWidth(), after.getWidth());
        int h = Math.min(before.getHeight(), after.getHeight());
        int changed = 0, total = w * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) changed++;
            }
        }
        return total == 0 ? 0.0 : (changed * 100.0 / total);
    }

    // ═══ Progressive zoom verification ═══

    /**
     * Capture a region of given size centered on (targetX, targetY),
     * draw a bright green crosshair marker on the image at the target point,
     * send to Gemini asking "is this marker on '{search}'? if not, give corrected coords".
     * Returns corrected screen coords, or null if Gemini confirms / can't improve.
     */
    private int[] zoomVerify(String search, int targetX, int targetY, int regionW, int regionH, String label) {
        try {
            java.awt.Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            // Clamp region to screen bounds
            int rx = Math.max(0, Math.min(targetX - regionW / 2, screen.width - regionW));
            int ry = Math.max(0, Math.min(targetY - regionH / 2, screen.height - regionH));

            Robot robot = new Robot();
            BufferedImage regionImg = robot.createScreenCapture(new Rectangle(rx, ry, regionW, regionH));

            // Draw bright green crosshair marker at the target point (relative to region)
            int markerX = targetX - rx;
            int markerY = targetY - ry;
            java.awt.Graphics2D g = regionImg.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(java.awt.Color.GREEN);
            g.setStroke(new java.awt.BasicStroke(3f));
            // Circle
            g.drawOval(markerX - 15, markerY - 15, 30, 30);
            // Crosshair lines
            g.drawLine(markerX - 20, markerY, markerX + 20, markerY);
            g.drawLine(markerX, markerY - 20, markerX, markerY + 20);
            // Label
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 12));
            g.drawString("TARGET", markerX + 18, markerY - 18);
            g.dispose();

            // Save annotated zoom image
            Files.createDirectories(NAV_DIR);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
            Path imgFile = NAV_DIR.resolve(label + "_" + ts + ".png");
            javax.imageio.ImageIO.write(regionImg, "png", imgFile.toFile());

            log.info("[screenClick] {} zoom: {}x{} at ({},{}) marker at ({},{})",
                    label, regionW, regionH, rx, ry, markerX, markerY);

            // Ask Gemini to verify
            String prompt = "This is a zoomed screenshot (" + regionW + "x" + regionH + " pixels). "
                    + "There is a GREEN CIRCLE with crosshair marked 'TARGET' on this image. "
                    + "I want to click on the text '" + search + "'.\n\n"
                    + "Is the green marker centered ON the text '" + search + "'?\n"
                    + "- If YES (marker is on the correct text), return: CONFIRMED\n"
                    + "- If NO (marker is off), return the CORRECTED coordinates where the CENTER of the text '"
                    + search + "' actually is in this image, in format: CORRECTED:x,y\n\n"
                    + "The coordinates must be within this image (0-" + regionW + " for x, 0-" + regionH + " for y). "
                    + "Return ONLY one line: either CONFIRMED or CORRECTED:x,y";

            String response = visionService.analyzeWithPrompt(imgFile, prompt, visionModelConfig.getVerifyModel());
            if (response == null || response.isBlank()) return null;

            for (String line : response.trim().split("\\r?\\n")) {
                line = line.trim();
                if (line.equalsIgnoreCase("CONFIRMED")) {
                    log.info("[screenClick] {} — GPT-5.4 confirmed target at ({},{})", label, targetX, targetY);
                    return null; // coords are good
                }
                if (line.startsWith("CORRECTED:")) {
                    String[] parts = line.substring(10).split(",");
                    if (parts.length == 2) {
                        int corrX = Integer.parseInt(parts[0].trim());
                        int corrY = Integer.parseInt(parts[1].trim());
                        if (corrX >= 0 && corrX <= regionW && corrY >= 0 && corrY <= regionH) {
                            int newScreenX = rx + corrX;
                            int newScreenY = ry + corrY;
                            log.info("[screenClick] {} — GPT-5.4 corrected ({},{}) → image({},{}) → screen({},{})",
                                    label, targetX, targetY, corrX, corrY, newScreenX, newScreenY);
                            return new int[]{newScreenX, newScreenY};
                        }
                    }
                }
            }

            log.info("[screenClick] {} — GPT-5.4 response unclear: {}", label, response.trim());
            return null;
        } catch (Exception e) {
            log.warn("[screenClick] {} failed: {}", label, e.getMessage());
            return null;
        }
    }

    // ═══ Annotation ═══

    /** Draw a red circle and label on the screenshot at the target location. */
    private void annotateScreenshot(Path screenshotFile, int x, int y, String label) {
        try {
            BufferedImage img = javax.imageio.ImageIO.read(screenshotFile.toFile());
            java.awt.Graphics2D g = img.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

            // Red circle (radius 20)
            g.setColor(java.awt.Color.RED);
            g.setStroke(new java.awt.BasicStroke(3f));
            g.drawOval(x - 20, y - 20, 40, 40);

            // Crosshair
            g.drawLine(x - 10, y, x + 10, y);
            g.drawLine(x, y - 10, x, y + 10);

            // Label
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14));
            g.drawString(label, x + 25, y + 5);

            g.dispose();
            javax.imageio.ImageIO.write(img, "png", screenshotFile.toFile());
            log.info("[screenClick] Annotated {} with target '{}' at ({},{})", screenshotFile.getFileName(), label, x, y);
        } catch (Exception e) {
            log.warn("[screenClick] annotateScreenshot failed: {}", e.getMessage());
        }
    }

    // ═══ Gemini coordinate finder ═══

    /**
     * Ask Gemini to find an element and return its coordinates.
     * Uses analyze() directly with a precise prompt (avoids double-prompt issue in findElementCoordinates).
     */
    private int[] askVisionForCoords(Path screenshot, String target, int width, int height, int attempt) {
        return askVisionForCoords(screenshot, target, width, height, attempt, null);
    }

    private int[] askVisionForCoords(Path screenshot, String target, int width, int height, int attempt, String modelOverride) {
        String retryHint = attempt > 1
                ? " The previous click didn't work. Find a DIFFERENT '" + target
                + "' — a button, link, or clickable menu item, NOT a label or heading."
                : "";

        String prompt = "You are a pixel-precise UI element locator. "
                + "Find the element with the text '" + target + "' in this screenshot and return "
                + "the CENTER coordinates of that text.\n\n"
                + "CRITICAL RULES:\n"
                + "- Return the CENTER of the TEXT itself, not the icon next to it, not nearby elements.\n"
                + "- The coordinates must land ON the text '" + target + "'.\n"
                + "- If there are multiple '" + target + "' elements, pick the one that looks like a clickable "
                + "button, tab, or link (not a static label or heading).\n"
                + "- Image dimensions: " + width + "x" + height + " pixels.\n"
                + "- Return ONLY one line: COORDS:x,y (integers, center of the text)\n"
                + "- If not found, return: NOT_FOUND\n"
                + retryHint;

        String modelName = modelOverride != null ? modelOverride : visionModelConfig.getPrimaryModel();

        log.info("\n══════════════════════════════════════\n"
                + "  LOCATING ELEMENT\n"
                + "  * Using: {}\n"
                + "  * Target: {}\n"
                + "  * Attempt: {}\n"
                + "  * Image: {}x{}\n"
                + "  * Prompt: {}\n"
                + "══════════════════════════════════════",
                modelName, target, attempt, width, height,
                prompt.replace("\n", " ").trim());

        // Use GPT Vision only
        String response = null;
        if (visionService.isAvailable()) {
            response = modelOverride != null
                    ? visionService.analyzeWithPrompt(screenshot, prompt, modelOverride)
                    : visionService.analyzeWithPrompt(screenshot, prompt);
        }

        if (response == null || response.isBlank() || response.contains("NOT_FOUND")) {
            log.info("\n  LOCATING ELEMENT — RESULT\n"
                    + "  * Target: {}\n"
                    + "  * Coordinates: NOT FOUND\n"
                    + "  * Raw response: {}\n"
                    + "══════════════════════════════════════",
                    target, response == null ? "(null)" : response.trim());
            return null;
        }

        for (String line : response.trim().split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("COORDS:")) {
                String[] parts = line.substring(7).split(",");
                if (parts.length == 2) {
                    try {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        // Some models return coordinates in 0-1000 normalized scale.
                        if (x <= 1000 && y <= 1000 && (width > 1000 || height > 1000)) {
                            int scaledX = (int) Math.round(x * width / 1000.0);
                            int scaledY = (int) Math.round(y * height / 1000.0);
                            log.info("\n  LOCATING ELEMENT — RESULT\n"
                                    + "  * Target: {}\n"
                                    + "  * Coordinates: x: {}, y: {} (rescaled from {}, {})\n"
                                    + "══════════════════════════════════════",
                                    target, scaledX, scaledY, x, y);
                            return new int[]{scaledX, scaledY};
                        }
                        if (x >= 0 && x <= width && y >= 0 && y <= height) {
                            log.info("\n  LOCATING ELEMENT — RESULT\n"
                                    + "  * Target: {}\n"
                                    + "  * Coordinates: x: {}, y: {}\n"
                                    + "══════════════════════════════════════",
                                    target, x, y);
                            return new int[]{x, y};
                        }
                        log.warn("\n  LOCATING ELEMENT — RESULT\n"
                                + "  * Target: {}\n"
                                + "  * Coordinates: x: {}, y: {} — OUT OF BOUNDS ({}x{})\n"
                                + "══════════════════════════════════════",
                                target, x, y, width, height);
                    } catch (NumberFormatException e) {
                        log.warn("\n  LOCATING ELEMENT — RESULT\n"
                                + "  * Target: {}\n"
                                + "  * Error: Failed to parse '{}'\n"
                                + "══════════════════════════════════════",
                                target, line);
                    }
                }
            }
        }
        log.warn("\n  LOCATING ELEMENT — RESULT\n"
                + "  * Target: {}\n"
                + "  * Coordinates: PARSE FAILED\n"
                + "  * Raw response: {}\n"
                + "══════════════════════════════════════",
                target, response.trim());
        return null;
    }

    // ═══ Engine priority helpers ═══

    /**
     * Try to find element coordinates using the specified engine key.
     * Returns int[] {x, y} or null if not found / engine unavailable.
     */
    private int[] findWithEngine(String engineKey, Path screenshot, String search, int screenW, int screenH) {
        try {
            switch (engineKey) {
                case "gpt5.4":
                case "gpt": // legacy
                    if (!visionService.isAvailable()) return null;
                    return visionService.findElementCoordinates(screenshot, search, screenW, screenH, visionModelConfig.getPrimaryModel());
                case "gpt4o-mini":
                    if (!visionService.isAvailable()) return null;
                    return visionService.findElementCoordinates(screenshot, search, screenW, screenH, visionModelConfig.getFastModel());
                case "claude":
                    if (!claudeVisionService.isAvailable()) return null;
                    return claudeVisionService.findElementCoordinates(screenshot, search, screenW, screenH);
                case "ocr": {
                    double[] oc = screenMemoryService.findTextOnScreen(screenshot, search);
                    return oc != null ? new int[]{(int) oc[0], (int) oc[1]} : null;
                }
                case "rek": {
                    if (!rekognitionService.isAvailable()) return null;
                    double[] rc = rekognitionService.findTextOnScreen(screenshot, search);
                    return rc != null ? new int[]{(int) rc[0], (int) rc[1]} : null;
                }
                case "docai": {
                    if (!documentAiService.isAvailable()) return null;
                    double[] dc = documentAiService.findTextOnScreen(screenshot, search);
                    return dc != null ? new int[]{(int) dc[0], (int) dc[1]} : null;
                }
                default:
                    log.warn("[screenClick] Unknown engine key: {}", engineKey);
                    return null;
            }
        } catch (Exception e) {
            log.warn("[screenClick] Engine '{}' failed: {}", engineKey, e.getMessage());
            return null;
        }
    }

    private static String engineDisplayName(String key) {
        switch (key) {
            case "gpt5.4": return "GPT-5.4";
            case "gpt4o-mini": return "GPT-4o Mini";
            case "gpt": return "GPT-5.4"; // legacy
            case "claude": return "Claude Opus 4.6";
            case "ocr": return "Windows OCR";
            case "rek": return "Rekognition";
            case "docai": return "Document AI";
            default: return key;
        }
    }

    // ═══ Window helpers ═══

    private void hideMinsBotWindow() {
        // No-op: keep Mins Bot visible during screenshots
    }

    private void showMinsBotWindow() {
        // No-op: window is never hidden
    }
}
