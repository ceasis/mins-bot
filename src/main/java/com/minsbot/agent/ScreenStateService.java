package com.minsbot.agent;

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
 * Takes a fresh screenshot on every user message, analyzes it with Gemini reasoning,
 * and returns the analysis for injection into the AI system message.
 * Falls back to OCR/Textract word listing if Gemini is unavailable.
 */
@Component
public class ScreenStateService {

    private static final Logger log = LoggerFactory.getLogger(ScreenStateService.class);

    private final SystemControlService systemControl;
    private final ScreenMemoryService screenMemoryService;
    private final TextractService textractService;
    private final GeminiVisionService geminiVisionService;

    public ScreenStateService(SystemControlService systemControl,
                              ScreenMemoryService screenMemoryService,
                              TextractService textractService,
                              GeminiVisionService geminiVisionService) {
        this.systemControl = systemControl;
        this.screenMemoryService = screenMemoryService;
        this.textractService = textractService;
        this.geminiVisionService = geminiVisionService;
    }

    private static final String GEMINI_SCREEN_PROMPT = """
            You are analyzing a Windows desktop screenshot. List EVERY visible item precisely:

            1. FILES: List every file name you can see (e.g. "ANIMALS.txt", "report.docx")
            2. FOLDERS: List every folder name you can see (e.g. "LIVING", "Documents")
            3. WINDOWS: List any open application windows and their titles
            4. OTHER: Any other notable UI elements (taskbar items, icons, etc.)

            Be EXACT with names — spell them exactly as they appear on screen.
            Format as a concise list, one item per line. Do NOT add commentary.""";

    private static final String GEMINI_TASK_PROMPT = """
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

            // Hide Mins Bot so it doesn't appear in screenshot
            try { com.minsbot.FloatingAppLauncher.hideWindow(); } catch (Exception e) {
                log.info("[ScreenState] hideWindow failed: {}", e.getMessage());
            }
            Thread.sleep(150);

            // Take a fresh screenshot NOW
            String result = systemControl.takeScreenshot();
            log.info("[ScreenState] takeScreenshot: '{}'",
                    result != null && result.length() > 150 ? result.substring(0, 150) + "..." : result);

            // Restore window immediately
            try { com.minsbot.FloatingAppLauncher.showWindow(); } catch (Exception ignored) {}

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

            // Try Gemini reasoning first (best quality)
            if (geminiVisionService.isAvailable()) {
                log.info("[ScreenState] Gemini available — analyzing...");
                String geminiResult = analyzeWithGemini(screenshotPath, userMessage);
                if (geminiResult != null && !geminiResult.isBlank()) {
                    log.info("[ScreenState] Gemini SUCCESS: {} chars", geminiResult.length());
                    return geminiResult;
                }
                log.warn("[ScreenState] Gemini returned null/empty — falling back to OCR");
            } else {
                log.warn("[ScreenState] Gemini NOT available — using OCR fallback");
            }

            // Fallback: OCR + Textract word listing
            String ocrResult = collectOcrText(screenshotPath);
            if (ocrResult != null) {
                log.info("[ScreenState] OCR fallback SUCCESS: {} chars", ocrResult.length());
            } else {
                log.warn("[ScreenState] OCR fallback also returned null — no screen data");
            }
            return ocrResult;

        } catch (Exception e) {
            log.warn("[ScreenState] EXCEPTION: {}", e.getMessage(), e);
            try { com.minsbot.FloatingAppLauncher.showWindow(); } catch (Exception ignored) {}
            return null;
        }
    }

    /** Use Gemini to provide intelligent, context-aware screen analysis. */
    private String analyzeWithGemini(Path screenshotPath, String userMessage) {
        try {
            String prompt;
            if (userMessage != null && !userMessage.isBlank()
                    && looksLikeScreenTask(userMessage)) {
                prompt = GEMINI_TASK_PROMPT.formatted(userMessage);
                log.info("[ScreenState] Using TASK prompt");
            } else {
                prompt = GEMINI_SCREEN_PROMPT;
                log.info("[ScreenState] Using SCREEN prompt");
            }

            String result = geminiVisionService.analyze(screenshotPath, prompt);
            if (result == null || result.isBlank()) {
                log.warn("[ScreenState] Gemini analyze() returned null/empty");
            }
            return result;
        } catch (Exception e) {
            log.warn("[ScreenState] Gemini EXCEPTION: {}", e.getMessage(), e);
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

        // Skip very short messages (1-3 words): greetings, confirmations, simple questions
        if (wordCount <= 3) {
            // But allow short action commands like "take screenshot", "open chrome"
            if (lower.contains("open") || lower.contains("click") || lower.contains("drag")
                    || lower.contains("search") || lower.contains("screenshot") || lower.contains("browse")
                    || lower.contains("navigate") || lower.contains("type") || lower.contains("close")
                    || lower.contains("move") || lower.contains("find")) {
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
