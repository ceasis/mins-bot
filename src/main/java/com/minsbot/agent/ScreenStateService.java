package com.minsbot.agent;

import com.minsbot.agent.tools.ToolExecutionNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Takes a fresh screenshot on every user message, analyzes it with GPT Vision,
 * and returns the analysis for injection into the AI system message.
 * Falls back to OCR word listing if vision is unavailable.
 */
@Component
public class ScreenStateService {

    private static final Logger log = LoggerFactory.getLogger(ScreenStateService.class);

    private final SystemControlService systemControl;
    private final ScreenMemoryService screenMemoryService;
    private final TextractService textractService;
    private final VisionService visionService;
    private final VisionModelConfig visionModelConfig;
    private final ToolExecutionNotifier toolNotifier;

    public ScreenStateService(SystemControlService systemControl,
                              ScreenMemoryService screenMemoryService,
                              TextractService textractService,
                              VisionService visionService,
                              VisionModelConfig visionModelConfig,
                              ToolExecutionNotifier toolNotifier) {
        this.systemControl = systemControl;
        this.screenMemoryService = screenMemoryService;
        this.textractService = textractService;
        this.visionService = visionService;
        this.visionModelConfig = visionModelConfig;
        this.toolNotifier = toolNotifier;
    }

    private static final String VISION_SCREEN_PROMPT = """
            You are analyzing a Windows desktop screenshot. List EVERY visible item precisely:

            1. FILES: List every file name you can see (e.g. "ANIMALS.txt", "report.docx")
            2. FOLDERS: List every folder name you can see (e.g. "LIVING", "Documents")
            3. WINDOWS: List any open application windows and their titles
            4. OTHER: Any other notable UI elements (taskbar items, icons, etc.)

            Be EXACT with names — spell them exactly as they appear on screen.
            Format as a concise list, one item per line. Do NOT add commentary.""";

    private static final String VISION_TASK_PROMPT = """
            You are analyzing a Windows desktop screenshot for a user who wants to: %s

            1. List EVERY file and folder visible on the desktop (exact names as shown on screen).
            2. Based on the user's request, provide a STEP-BY-STEP PLAN of which files should be \
            dragged to which folders, using findAndDragElement("source", "target") format.
            3. REASON about the MEANING of each file/folder name to determine the correct mapping.
            4. ONLY include items that match the user's request. If they say "text files", only include \
            .txt files. NEVER include system icons (Recycle Bin, This PC, Network), shortcuts, \
            folders, or any item that does not match what the user asked to move.

            Example: If files are ANIMALS.txt, METALS.txt and folders are LIVING, NON-LIVING:
            - ANIMALS.txt → LIVING (animals are living things)
            - METALS.txt → NON-LIVING (metals are non-living things)
            Plan: findAndDragElement("ANIMALS.txt", "LIVING"), findAndDragElement("METALS.txt", "NON-LIVING")
            (Recycle Bin is NOT a text file — do NOT include it in the plan.)

            Be precise. List ALL matching items and ALL actions needed. Do not skip any matching files.""";

    /**
     * Take a fresh screenshot right now and analyze it with Gemini reasoning.
     * Falls back to OCR text if Gemini is unavailable.
     *
     * @param userMessage the user's request (used for context-aware analysis)
     * @return analysis text to inject into the AI system message, or null
     */
    public String captureAndAnalyze(String userMessage) {
        // Skip screen capture for simple greetings and non-action messages
        if (userMessage != null && !needsScreenCapture(userMessage)) {
            log.info("[ScreenState] Skipping capture for non-action message: '{}'", userMessage);
            return null;
        }

        try {
            log.info("[ScreenState] captureAndAnalyze — message: '{}'",
                    userMessage != null && userMessage.length() > 80
                            ? userMessage.substring(0, 80) + "..." : userMessage);

            // Get bot window bounds so we can tell vision AI to ignore that area
            // (no more hiding/showing — avoids flicker and focus-stealing)
            int[] botBounds = com.minsbot.FloatingAppLauncher.getWindowBounds();
            if (botBounds != null) {
                log.info("[ScreenState] Bot window at ({},{}) {}x{} — AI will ignore this region",
                        botBounds[0], botBounds[1], botBounds[2], botBounds[3]);
            }

            // Take a fresh screenshot NOW (bot window is visible but will be ignored)
            String result = systemControl.takeScreenshot();
            log.info("[ScreenState] takeScreenshot: '{}'",
                    result != null && result.length() > 150 ? result.substring(0, 150) + "..." : result);

            if (result == null || !result.startsWith("Screenshot saved:")) {
                log.warn("[ScreenState] Screenshot FAILED — result: {}", result);
                return null;
            }

            // Parse the screenshot path directly from the result (no directory walking)
            String pathStr = result.replace("Screenshot saved: ", "").trim();
            Path screenshotPath = Paths.get(pathStr);

            if (!Files.exists(screenshotPath)) {
                log.warn("[ScreenState] Screenshot file does not exist at: {}", screenshotPath);
                return null;
            }
            log.info("[ScreenState] Screenshot ready: {} ({} bytes)",
                    screenshotPath.getFileName(), Files.size(screenshotPath));

            // Build an ignore-region hint for the vision AI
            String ignoreHint;
            if (botBounds != null) {
                ignoreHint = "\n\nCRITICAL: The Mins Bot chat overlay (YOUR OWN UI) is visible at pixel region ("
                        + botBounds[0] + "," + botBounds[1] + ") to ("
                        + (botBounds[0] + botBounds[2]) + "," + (botBounds[1] + botBounds[3])
                        + "). The chat bubbles inside it are your own previous replies. "
                        + "COMPLETELY IGNORE that region — never describe it, never react to its messages, "
                        + "never comment on what it says. Only observe what is OUTSIDE or BEHIND it.";
            } else {
                ignoreHint = "\n\nCRITICAL: There is a chat overlay called 'Mins Bot' visible on screen "
                        + "(small blue swirling ball icon, title 'Mins Bot', chat message bubbles). "
                        + "This is YOUR OWN UI showing your previous replies. "
                        + "COMPLETELY IGNORE this overlay — never describe it, never react to its messages, "
                        + "never comment on what it says. Only observe what is BEHIND or AROUND it.";
            }

            // GPT Vision only — no Gemini for screen analysis
            if (visionService.isAvailable()) {
                log.info("[ScreenState] GPT Vision available — analyzing...");
                toolNotifier.notify("__vision__Checking screen with GPT Vision...");
                String prompt = (userMessage != null && !userMessage.isBlank() && looksLikeScreenTask(userMessage))
                        ? VISION_TASK_PROMPT.formatted(userMessage) : VISION_SCREEN_PROMPT;
                prompt += ignoreHint;
                String gptResult = visionService.analyzeWithPrompt(screenshotPath, prompt, visionModelConfig.getPrimaryModel());
                if (gptResult != null && !gptResult.isBlank()) {
                    log.info("[ScreenState] GPT Vision SUCCESS: {} chars", gptResult.length());
                    return gptResult;
                }
                log.warn("[ScreenState] GPT Vision returned null/empty — falling back to OCR");
            }

            // Fallback: OCR + Textract word listing
            toolNotifier.notify("__vision__Checking screen with OCR fallback...");
            String ocrResult = collectOcrText(screenshotPath);
            if (ocrResult != null) {
                log.info("[ScreenState] OCR fallback SUCCESS: {} chars", ocrResult.length());
            } else {
                log.warn("[ScreenState] OCR fallback also returned null — no screen data");
            }
            return ocrResult;

        } catch (Exception e) {
            log.warn("[ScreenState] EXCEPTION: {}", e.getMessage(), e);
            return null;
        }
    }


    /** Check if the user message looks like it involves interacting with screen items. */
    private boolean looksLikeScreenTask(String msg) {
        String lower = msg.toLowerCase();
        return lower.contains("move") || lower.contains("drag") || lower.contains("click")
                || lower.contains("open") || lower.contains("close") || lower.contains("file")
                || lower.contains("folder") || lower.contains("desktop") || lower.contains("sort")
                || lower.contains("organize") || lower.contains("put") || lower.contains("arrange");
    }

    /** Fallback: collect all visible text from OCR + Textract. */
    private String collectOcrText(Path screenshotPath) {
        Set<String> textItems = new LinkedHashSet<>();

        List<ScreenMemoryService.OcrWord> ocrWords = screenMemoryService.runOcrWithBounds(screenshotPath);
        for (ScreenMemoryService.OcrWord word : ocrWords) {
            String w = word.text().trim();
            if (!w.isEmpty() && w.length() > 1) {
                textItems.add(w);
            }
        }

        if (textractService.isAvailable()) {
            List<TextractService.TextractWord> txWords = textractService.detectWords(screenshotPath);
            for (TextractService.TextractWord tw : txWords) {
                String w = tw.text().trim();
                if (!w.isEmpty() && w.length() > 1) {
                    textItems.add(w);
                }
            }
        }

        if (textItems.isEmpty()) return null;

        String joined = String.join(", ", textItems);
        log.info("[ScreenState] OCR fallback: {} text items", textItems.size());
        return "Visible text on screen: " + joined;
    }

    /**
     * Returns true if the message likely needs screen context (actions, browsing, clicking, etc.).
     * Returns false for simple greetings, questions, and personal info that don't need a screenshot.
     */
    private boolean needsScreenCapture(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.trim().toLowerCase();
        int wordCount = lower.split("\\s+").length;

        // Skip non-visual programmatic operations (no screen context needed)
        if (lower.matches(".*(open|new|close|switch).*(tab|chrome|browser|edge|firefox).*")
                || lower.matches(".*(open|launch|start|run)\\s+(an?\\s+)?\\w+\\s*(app)?$")
                || lower.matches(".*run\\s+(powershell|cmd|command|terminal|script).*")
                || lower.matches(".*install\\s+.*") || lower.matches(".*uninstall\\s+.*")
                || lower.matches(".*set\\s+(volume|brightness|timer|alarm|reminder).*")) {
            return false;
        }

        // Skip very short messages (1-3 words): greetings, confirmations, simple questions
        if (wordCount <= 3) {
            // But allow short action commands that need visual context
            if (lower.contains("click") || lower.contains("drag")
                    || lower.contains("screenshot") || lower.contains("browse")
                    || lower.contains("type") || lower.contains("move") || lower.contains("find")) {
                return true;
            }
            return false;
        }

        // Skip personal info sharing (no screen needed)
        if (lower.contains("my wife") || lower.contains("my husband") || lower.contains("my kid")
                || lower.contains("my name is") || lower.contains("my email") || lower.contains("my birthday")
                || lower.contains("my phone") || lower.contains("his name") || lower.contains("her name")
                || lower.contains("his email") || lower.contains("her email")) {
            return false;
        }

        // Everything else: capture screen
        return true;
    }
}
