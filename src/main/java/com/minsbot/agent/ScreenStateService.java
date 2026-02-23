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
 * Captures a screenshot and uses Gemini reasoning to provide an intelligent
 * analysis of what's on screen. Falls back to OCR/Textract word listing.
 * Used by ChatService to inject live screen state into every AI call.
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

            Example: If files are ANIMALS.txt, METALS.txt and folders are LIVING, NON-LIVING:
            - ANIMALS.txt → LIVING (animals are living things)
            - METALS.txt → NON-LIVING (metals are non-living things)
            Plan: findAndDragElement("ANIMALS.txt", "LIVING"), findAndDragElement("METALS.txt", "NON-LIVING")

            Be precise. List ALL items and ALL actions needed. Do not skip any files.""";

    /**
     * Take a screenshot and analyze it with Gemini reasoning.
     * Falls back to OCR text if Gemini is unavailable.
     *
     * @param userMessage the user's request (used for context-aware analysis)
     * @return analysis text to inject into the AI system message, or null
     */
    public String captureAndAnalyze(String userMessage) {
        try {
            // Hide Mins Bot so it doesn't appear in screenshot
            try { com.minsbot.FloatingAppLauncher.hideWindow(); } catch (Exception ignored) {}
            Thread.sleep(150);

            // Take screenshot
            String result = systemControl.takeScreenshot();
            if (result == null || !result.startsWith("Screenshot saved:")) {
                try { com.minsbot.FloatingAppLauncher.showWindow(); } catch (Exception ignored) {}
                return null;
            }

            // Restore window
            try { com.minsbot.FloatingAppLauncher.showWindow(); } catch (Exception ignored) {}

            Path screenshotPath = findLatestScreenshot();
            if (screenshotPath == null) return null;

            // Try Gemini reasoning first (best quality)
            if (geminiVisionService.isAvailable()) {
                String geminiResult = analyzeWithGemini(screenshotPath, userMessage);
                if (geminiResult != null && !geminiResult.isBlank()) {
                    log.info("[ScreenState] Gemini analysis: {} chars", geminiResult.length());
                    return geminiResult;
                }
            }

            // Fallback: OCR + Textract word listing
            return collectOcrText(screenshotPath);

        } catch (Exception e) {
            log.info("[ScreenState] Capture failed: {}", e.getMessage());
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
            } else {
                prompt = GEMINI_SCREEN_PROMPT;
            }

            return geminiVisionService.analyze(screenshotPath, prompt);
        } catch (Exception e) {
            log.info("[ScreenState] Gemini analysis failed: {}", e.getMessage());
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

    private Path findLatestScreenshot() {
        try {
            Path screenshotsDir = Paths.get(System.getProperty("user.home"),
                    "mins_bot_data", "screenshots");
            if (!Files.exists(screenshotsDir)) return null;

            return Files.walk(screenshotsDir)
                    .filter(p -> p.toString().endsWith(".png"))
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                        } catch (Exception e) { return 0; }
                    })
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
