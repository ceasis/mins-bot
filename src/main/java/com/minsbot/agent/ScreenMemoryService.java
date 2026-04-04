package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Periodically captures screen content and stores extracted text with timestamps.
 * Live captures use GPT-4o Vision API (via {@link VisionService}) for rich understanding.
 * Background periodic captures use Windows OCR (free, fast) for historical logs.
 *
 * Storage: ~/mins_bot_data/screen_memory/YYYY-MM-DD.txt
 * Config:  ~/mins_bot_data/minsbot_config.txt section "## Screen memory"
 */
@Component
public class ScreenMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ScreenMemoryService.class);

    private final SystemControlService systemControl;
    private final VisionService visionService;

    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "minsbot_config.txt");

    /** Where ScreenshotService and takeScreenshot save PNGs. */
    private static final Path SCREENSHOTS_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "screenshots");

    private static final Path SCREEN_MEMORY_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "screen_memory");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter YEAR_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy_MMM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("d");

    private volatile boolean enabled = true;
    private volatile int intervalSeconds = 60;

    /** Last OCR text — used for deduplication. */
    private volatile String lastText = "";

    /** Reusable OCR PowerShell script file (flat text output). */
    private Path ocrScript;

    /** OCR script that outputs word-level bounding boxes. */
    private Path ocrBoundsScript;

    /** A single OCR-detected word with its bounding rectangle in image pixels (sub-pixel precision). */
    public record OcrWord(String text, double x, double y, double width, double height) {
        public double centerX() { return x + width / 2.0; }
        public double centerY() { return y + height / 2.0; }
    }

    public ScreenMemoryService(SystemControlService systemControl, VisionService visionService) {
        this.systemControl = systemControl;
        this.visionService = visionService;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(SCREEN_MEMORY_DIR);
        loadConfigFromFile();
        createOcrScript();
        createOcrBoundsScript();
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (ocrScript != null) Files.deleteIfExists(ocrScript);
        } catch (IOException ignored) {}
        try {
            if (ocrBoundsScript != null) Files.deleteIfExists(ocrBoundsScript);
        } catch (IOException ignored) {}
    }

    /** Re-read config. Called by ConfigScanService when minsbot_config.txt changes. */
    public void reloadConfig() {
        loadConfigFromFile();
        log.info("[ScreenMemory] Config reloaded — enabled={}, interval={}s", enabled, intervalSeconds);
    }

    @Scheduled(fixedDelay = 10000) // polls every 10s, actual interval controlled by lastRunCheck
    public void tick() {
        if (!enabled || !isWindows()) return;
        // The @Scheduled runs at 10s granularity; we track our own interval
        // so it's reconfigurable without restarting.
        processLatestScreenshot();
    }

    // Track last run time for configurable interval
    private volatile long lastRunMs = 0;

    private void processLatestScreenshot() {
        long now = System.currentTimeMillis();
        if (now - lastRunMs < intervalSeconds * 1000L) return;
        lastRunMs = now;

        Path latest = findLatestScreenshot();
        if (latest == null) return;

        String text = runOcr(latest);
        if (text == null || text.isBlank()) return;

        // Collapse to single line, max 500 chars
        String collapsed = text.replaceAll("[\\r\\n]+", " ").trim();
        if (collapsed.length() > 500) collapsed = collapsed.substring(0, 500);

        // Dedup: skip if same as last entry
        if (collapsed.equals(lastText)) return;
        lastText = collapsed;

        // Append to daily file: screen_memory/2026_Feb/23.txt
        LocalDate today = LocalDate.now();
        String time = LocalTime.now().format(TIME_FMT);
        String entry = "[" + time + "] " + collapsed + "\n";

        Path monthDir = SCREEN_MEMORY_DIR.resolve(today.format(YEAR_MONTH_FMT));
        try {
            Files.createDirectories(monthDir);
            Path dayFile = monthDir.resolve(today.format(DAY_FMT) + ".txt");
            Files.writeString(dayFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("[ScreenMemory] Stored entry: [{}] {}...", time,
                    collapsed.substring(0, Math.min(60, collapsed.length())));
        } catch (IOException e) {
            log.warn("[ScreenMemory] Failed to write screen memory: {}", e.getMessage());
        }
    }

    // ═══ Public methods for ScreenMemoryTools ═══

    /**
     * Take a fresh screenshot, analyze it via Vision API, and store the result.
     * Always captures "right now" so the answer matches what the user is looking at.
     * Uses GPT-4o Vision API for rich understanding; falls back to Windows OCR if Vision fails.
     * Returns the analysis text, or null on failure.
     */
    public String captureNow() {
        systemControl.takeScreenshot();
        Path latest = findLatestScreenshot();
        if (latest == null) return null;

        // Try Vision API first (rich understanding: text + context + app identification)
        String fullText = null;
        if (visionService.isAvailable()) {
            fullText = visionService.analyzeScreenshot(latest);
            if (fullText != null) {
                log.debug("[ScreenMemory] Vision API returned {} chars", fullText.length());
            }
        }

        // Fallback: Windows OCR (text-only extraction)
        if (fullText == null) {
            String ocrText = runOcr(latest);
            if (ocrText == null || ocrText.isBlank()) return null;
            fullText = ocrText.trim();
        }

        // Compact version for daily log storage (single line, 500 chars)
        String collapsed = fullText.replaceAll("[\\r\\n]+", " ").trim();
        if (collapsed.length() > 500) collapsed = collapsed.substring(0, 500);

        lastText = collapsed;
        LocalDate today = LocalDate.now();
        String time = LocalTime.now().format(TIME_FMT);
        String entry = "[" + time + "] " + collapsed + "\n";

        Path monthDir = SCREEN_MEMORY_DIR.resolve(today.format(YEAR_MONTH_FMT));
        try {
            Files.createDirectories(monthDir);
            Path dayFile = monthDir.resolve(today.format(DAY_FMT) + ".txt");
            Files.writeString(dayFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[ScreenMemory] Failed to write: {}", e.getMessage());
        }

        // Return full OCR text (up to 4000 chars) with line breaks preserved
        return fullText.length() > 4000 ? fullText.substring(0, 4000) : fullText;
    }

    /**
     * Read screen memory text for a specific date (YYYY-MM-DD).
     */
    public String readMemory(String dateStr) {
        // New path: screen_memory/2026_Feb/23.txt
        try {
            LocalDate date = LocalDate.parse(dateStr, DATE_FMT);
            Path newPath = SCREEN_MEMORY_DIR.resolve(date.format(YEAR_MONTH_FMT)).resolve(date.format(DAY_FMT) + ".txt");
            if (Files.exists(newPath)) return Files.readString(newPath, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        // Backwards compat: screen_memory/2026-02-23.txt
        Path oldPath = SCREEN_MEMORY_DIR.resolve(dateStr + ".txt");
        if (Files.exists(oldPath)) {
            try { return Files.readString(oldPath, StandardCharsets.UTF_8); }
            catch (IOException e) { log.warn("[ScreenMemory] Failed to read {}: {}", dateStr, e.getMessage()); }
        }
        return null;
    }

    /**
     * List available screen memory dates.
     */
    public String listDates() {
        try (Stream<Path> files = Files.walk(SCREEN_MEMORY_DIR)) {
            StringBuilder sb = new StringBuilder();
            files.filter(p -> p.toString().endsWith(".txt"))
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        Path rel = SCREEN_MEMORY_DIR.relativize(p);
                        String label = rel.getNameCount() == 2
                                ? rel.getName(0) + "/" + rel.getName(1).toString().replace(".txt", "")
                                : p.getFileName().toString().replace(".txt", "");
                        try {
                            long size = Files.size(p);
                            long lines = Files.lines(p).count();
                            sb.append(label).append(" (").append(lines)
                                    .append(" entries, ").append(size / 1024).append("KB)\n");
                        } catch (IOException e) {
                            sb.append(label).append("\n");
                        }
                    });
            return sb.length() == 0 ? "No screen memory files yet." : sb.toString().trim();
        } catch (IOException e) {
            return "Failed to list screen memory: " + e.getMessage();
        }
    }

    /**
     * OCR an image file and return extracted text.
     * Available for external callers (e.g., browser tab capture).
     */
    public String ocrImage(Path imagePath) {
        return runOcr(imagePath);
    }

    // ═══ OCR via PowerShell ═══

    private void createOcrScript() {
        if (!isWindows()) return;
        try {
            ocrScript = Files.createTempFile("mins_bot_ocr_", ".ps1");
            String script = """
                    param([string]$ImagePath)
                    Add-Type -AssemblyName System.Runtime.WindowsRuntime
                    $null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
                    $null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime]
                    $null = [Windows.Storage.StorageFile, Windows.Foundation, ContentType = WindowsRuntime]

                    $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() |
                        Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
                        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
                    Function Await($WinRtTask, $ResultType) {
                        $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
                        $netTask = $asTask.Invoke($null, @($WinRtTask))
                        $netTask.Wait(-1) | Out-Null
                        $netTask.Result
                    }

                    $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($ImagePath)) ([Windows.Storage.StorageFile])
                    $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
                    $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
                    $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
                    $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
                    $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
                    Write-Output $result.Text
                    $stream.Dispose()
                    """;
            Files.writeString(ocrScript, script, StandardCharsets.UTF_8);
            log.debug("[ScreenMemory] OCR script created: {}", ocrScript);
        } catch (IOException e) {
            log.warn("[ScreenMemory] Failed to create OCR script: {}", e.getMessage());
        }
    }

    private String runOcr(Path imagePath) {
        if (ocrScript == null || !Files.exists(ocrScript)) return null;
        try {
            Process p = new ProcessBuilder(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", ocrScript.toString(), imagePath.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean done = p.waitFor(30, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return null;
            }
            return output;
        } catch (Exception e) {
            log.debug("[ScreenMemory] OCR failed: {}", e.getMessage());
            return null;
        }
    }

    // ═══ OCR with bounding boxes (for element location) ═══

    private void createOcrBoundsScript() {
        if (!isWindows()) return;
        try {
            ocrBoundsScript = Files.createTempFile("mins_bot_ocr_bounds_", ".ps1");
            String script = """
                    param([string]$ImagePath)
                    Add-Type -AssemblyName System.Runtime.WindowsRuntime
                    $null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
                    $null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime]
                    $null = [Windows.Storage.StorageFile, Windows.Foundation, ContentType = WindowsRuntime]

                    $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() |
                        Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
                        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
                    Function Await($WinRtTask, $ResultType) {
                        $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
                        $netTask = $asTask.Invoke($null, @($WinRtTask))
                        $netTask.Wait(-1) | Out-Null
                        $netTask.Result
                    }

                    $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($ImagePath)) ([Windows.Storage.StorageFile])
                    $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
                    $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
                    $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
                    $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
                    $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])

                    foreach ($line in $result.Lines) {
                        foreach ($word in $line.Words) {
                            $r = $word.BoundingRect
                            Write-Output ("WORD|" + $r.X.ToString("F2") + "|" + $r.Y.ToString("F2") + "|" + $r.Width.ToString("F2") + "|" + $r.Height.ToString("F2") + "|" + $word.Text)
                        }
                    }
                    $stream.Dispose()
                    """;
            Files.writeString(ocrBoundsScript, script, StandardCharsets.UTF_8);
            log.debug("[ScreenMemory] OCR bounds script created: {}", ocrBoundsScript);
        } catch (IOException e) {
            log.warn("[ScreenMemory] Failed to create OCR bounds script: {}", e.getMessage());
        }
    }

    /**
     * Run OCR on an image and return word-level bounding boxes.
     * Each word includes its text and pixel coordinates within the image.
     */
    public List<OcrWord> runOcrWithBounds(Path imagePath) {
        if (ocrBoundsScript == null || !Files.exists(ocrBoundsScript)) return List.of();
        try {
            Process p = new ProcessBuilder(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", ocrBoundsScript.toString(), imagePath.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean done = p.waitFor(30, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                log.info("[ScreenMemory] OCR bounds timed out for: {}", imagePath.getFileName());
                return List.of();
            }

            List<OcrWord> words = new ArrayList<>();
            for (String line : output.split("\\r?\\n")) {
                if (!line.startsWith("WORD|")) continue;
                String[] parts = line.split("\\|", 6);
                if (parts.length < 6) continue;
                try {
                    words.add(new OcrWord(
                            parts[5],
                            Double.parseDouble(parts[1]),
                            Double.parseDouble(parts[2]),
                            Double.parseDouble(parts[3]),
                            Double.parseDouble(parts[4])));
                } catch (NumberFormatException ignored) {}
            }
            log.info("[ScreenMemory] OCR bounds: {} words detected from {}", words.size(), imagePath.getFileName());
            return words;
        } catch (Exception e) {
            log.info("[ScreenMemory] OCR bounds failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Find text on a screenshot using OCR bounding boxes.
     * Tries exact word match, then multi-word consecutive match, then substring match.
     *
     * @param imagePath  path to the screenshot PNG
     * @param searchText the text to find (e.g. "Selection", "Sign In", "Submit")
     * @return double[] {centerX, centerY} in image-pixel coordinates (sub-pixel), or null if not found
     */
    public double[] findTextOnScreen(Path imagePath, String searchText) {
        List<OcrWord> words = runOcrWithBounds(imagePath);
        if (words.isEmpty()) {
            log.info("[findText] No OCR words detected — OCR may have failed");
            return null;
        }

        String search = searchText.trim();
        String searchLower = search.toLowerCase();
        log.info("[findText] Searching for '{}' among {} OCR words", search, words.size());

        // Log all detected words for debugging
        for (int i = 0; i < words.size(); i++) {
            OcrWord w = words.get(i);
            //log.info("[findText]   word[{}]: '{}' at ({},{}) {}x{} → center({},{})",
            //        i, w.text(),
            //        String.format("%.1f", w.x()), String.format("%.1f", w.y()),
            //        String.format("%.1f", w.width()), String.format("%.1f", w.height()),
            //        String.format("%.1f", w.centerX()), String.format("%.1f", w.centerY()));
        }

        // Strategy 1: Exact single-word match (case-insensitive)
        for (OcrWord w : words) {
            if (w.text().equalsIgnoreCase(search)) {
                log.info("[findText] EXACT match: '{}' bbox({},{} {}x{}) → center({}, {})",
                        w.text(), String.format("%.1f", w.x()), String.format("%.1f", w.y()),
                        String.format("%.1f", w.width()), String.format("%.1f", w.height()),
                        String.format("%.2f", w.centerX()), String.format("%.2f", w.centerY()));
                return new double[]{w.centerX(), w.centerY()};
            }
        }

        // Strategy 2: Multi-word consecutive match (e.g. "Sign In" → words "Sign" + "In")
        String[] tokens = search.split("\\s+");
        if (tokens.length > 1) {
            for (int i = 0; i <= words.size() - tokens.length; i++) {
                boolean match = true;
                for (int j = 0; j < tokens.length; j++) {
                    if (!words.get(i + j).text().equalsIgnoreCase(tokens[j])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    // Merge bounding boxes of all matched words
                    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
                    double maxX = 0, maxY = 0;
                    for (int j = 0; j < tokens.length; j++) {
                        OcrWord w = words.get(i + j);
                        minX = Math.min(minX, w.x());
                        minY = Math.min(minY, w.y());
                        maxX = Math.max(maxX, w.x() + w.width());
                        maxY = Math.max(maxY, w.y() + w.height());
                    }
                    double cx = (minX + maxX) / 2.0;
                    double cy = (minY + maxY) / 2.0;
                    log.info("[findText] MULTI-WORD match: '{}' → merged box ({},{})–({},{}) → center({}, {})",
                            search, String.format("%.1f", minX), String.format("%.1f", minY),
                            String.format("%.1f", maxX), String.format("%.1f", maxY),
                            String.format("%.2f", cx), String.format("%.2f", cy));
                    return new double[]{cx, cy};
                }
            }
        }

        // Strategy 3: Substring match (OCR word contains the search text)
        // Only forward direction: "Sending" contains "Send" → match.
        // NEVER reverse: "Send" contains "en" would be a false positive.
        for (OcrWord w : words) {
            if (w.text().toLowerCase().contains(searchLower) && w.text().length() >= searchLower.length()) {
                log.info("[findText] SUBSTRING match: '{}' contains '{}' bbox({},{} {}x{}) → center({}, {})",
                        w.text(), search, String.format("%.1f", w.x()), String.format("%.1f", w.y()),
                        String.format("%.1f", w.width()), String.format("%.1f", w.height()),
                        String.format("%.2f", w.centerX()), String.format("%.2f", w.centerY()));
                return new double[]{w.centerX(), w.centerY()};
            }
        }

        // Strategy 4: Check consecutive words joined with spaces (OCR may split differently)
        for (int i = 0; i < words.size(); i++) {
            StringBuilder joined = new StringBuilder(words.get(i).text());
            double startX = words.get(i).x(), startY = words.get(i).y();
            double endX = words.get(i).x() + words.get(i).width();
            double endY = words.get(i).y() + words.get(i).height();
            for (int j = i + 1; j < Math.min(i + 5, words.size()); j++) {
                joined.append(" ").append(words.get(j).text());
                endX = Math.max(endX, words.get(j).x() + words.get(j).width());
                endY = Math.max(endY, words.get(j).y() + words.get(j).height());
                if (joined.toString().toLowerCase().contains(searchLower)) {
                    double cx = (startX + endX) / 2.0;
                    double cy = (startY + endY) / 2.0;
                    log.info("[findText] JOINED match: '{}' contains '{}' → center({}, {})",
                            joined, search, String.format("%.2f", cx), String.format("%.2f", cy));
                    return new double[]{cx, cy};
                }
            }
        }

        // Strategy 5: Join consecutive words WITHOUT spaces (handles OCR splitting filenames
        // on punctuation, e.g. "file1" + ".txt" → "file1.txt", or "file1." + "txt")
        for (int i = 0; i < words.size(); i++) {
            StringBuilder noSpace = new StringBuilder(words.get(i).text());
            double startX = words.get(i).x(), startY = words.get(i).y();
            double endX = words.get(i).x() + words.get(i).width();
            double endY = words.get(i).y() + words.get(i).height();
            for (int j = i + 1; j < Math.min(i + 4, words.size()); j++) {
                noSpace.append(words.get(j).text());
                endX = Math.max(endX, words.get(j).x() + words.get(j).width());
                endY = Math.max(endY, words.get(j).y() + words.get(j).height());
                if (noSpace.toString().toLowerCase().contains(searchLower)) {
                    double cx = (startX + endX) / 2.0;
                    double cy = (startY + endY) / 2.0;
                    log.info("[findText] NO-SPACE JOIN match: '{}' contains '{}' → center({}, {})",
                            noSpace, search, String.format("%.2f", cx), String.format("%.2f", cy));
                    return new double[]{cx, cy};
                }
            }
        }

        log.info("[findText] NO MATCH for '{}' in {} OCR words", search, words.size());
        return null;
    }

    /**
     * Find ALL instances of a text on screen using OCR.
     * Returns a list of center coordinates for every match found.
     * Used by navigation backtracking to discover multiple candidates.
     */
    public List<double[]> findAllTextOnScreen(Path imagePath, String searchText) {
        List<double[]> results = new ArrayList<>();
        List<OcrWord> words = runOcrWithBounds(imagePath);
        if (words.isEmpty()) return results;

        String search = searchText.trim();
        String searchLower = search.toLowerCase();
        String[] tokens = search.split("\\s+");

        // Strategy 1: Exact single-word match
        for (OcrWord w : words) {
            if (w.text().equalsIgnoreCase(search)) {
                results.add(new double[]{w.centerX(), w.centerY()});
            }
        }

        // Strategy 2: Multi-word consecutive match
        if (tokens.length > 1) {
            for (int i = 0; i <= words.size() - tokens.length; i++) {
                boolean match = true;
                for (int j = 0; j < tokens.length; j++) {
                    if (!words.get(i + j).text().equalsIgnoreCase(tokens[j])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
                    double maxX = 0, maxY = 0;
                    for (int j = 0; j < tokens.length; j++) {
                        OcrWord w = words.get(i + j);
                        minX = Math.min(minX, w.x());
                        minY = Math.min(minY, w.y());
                        maxX = Math.max(maxX, w.x() + w.width());
                        maxY = Math.max(maxY, w.y() + w.height());
                    }
                    results.add(new double[]{(minX + maxX) / 2.0, (minY + maxY) / 2.0});
                }
            }
        }

        // Strategy 3: Substring match (only if no exact matches found)
        if (results.isEmpty()) {
            for (OcrWord w : words) {
                if (w.text().toLowerCase().contains(searchLower) && w.text().length() >= searchLower.length()) {
                    results.add(new double[]{w.centerX(), w.centerY()});
                }
            }
        }

        // Deduplicate near-identical coordinates (within 30px)
        List<double[]> deduped = new ArrayList<>();
        for (double[] r : results) {
            boolean dupe = false;
            for (double[] d : deduped) {
                if (Math.abs(r[0] - d[0]) < 30 && Math.abs(r[1] - d[1]) < 30) {
                    dupe = true;
                    break;
                }
            }
            if (!dupe) deduped.add(r);
        }
        log.info("[findAllText] '{}' → {} matches (deduped from {})", search, deduped.size(), results.size());
        return deduped;
    }

    // ═══ Helpers ═══

    private Path findLatestScreenshot() {
        if (!Files.exists(SCREENSHOTS_DIR)) return null;
        try (Stream<Path> files = Files.walk(SCREENSHOTS_DIR)) {
            return files.filter(p -> p.toString().endsWith(".png") && Files.isRegularFile(p))
                    .max(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
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
                if (!currentSection.equals("## screen memory")) continue;
                if (!trimmed.startsWith("- ")) continue;

                String kv = trimmed.substring(2).trim();
                int colon = kv.indexOf(':');
                if (colon < 0) continue;
                String key = kv.substring(0, colon).trim().toLowerCase();
                String val = kv.substring(colon + 1).trim().toLowerCase();

                switch (key) {
                    case "enabled" -> enabled = val.equals("true");
                    case "interval_seconds" -> {
                        try { intervalSeconds = Math.max(10, Integer.parseInt(val)); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[ScreenMemory] Could not read config: {}", e.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
