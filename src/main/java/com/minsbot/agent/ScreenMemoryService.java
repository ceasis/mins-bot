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
import java.util.Comparator;
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

    private volatile boolean enabled = true;
    private volatile int intervalSeconds = 60;

    /** Last OCR text — used for deduplication. */
    private volatile String lastText = "";

    /** Reusable OCR PowerShell script file. */
    private Path ocrScript;

    public ScreenMemoryService(SystemControlService systemControl, VisionService visionService) {
        this.systemControl = systemControl;
        this.visionService = visionService;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(SCREEN_MEMORY_DIR);
        loadConfigFromFile();
        createOcrScript();
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (ocrScript != null) Files.deleteIfExists(ocrScript);
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

        // Append to daily file
        String today = LocalDate.now().format(DATE_FMT);
        String time = LocalTime.now().format(TIME_FMT);
        String entry = "[" + time + "] " + collapsed + "\n";

        Path dayFile = SCREEN_MEMORY_DIR.resolve(today + ".txt");
        try {
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
        String today = LocalDate.now().format(DATE_FMT);
        String time = LocalTime.now().format(TIME_FMT);
        String entry = "[" + time + "] " + collapsed + "\n";

        Path dayFile = SCREEN_MEMORY_DIR.resolve(today + ".txt");
        try {
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
        Path dayFile = SCREEN_MEMORY_DIR.resolve(dateStr + ".txt");
        if (!Files.exists(dayFile)) return null;
        try {
            return Files.readString(dayFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[ScreenMemory] Failed to read {}: {}", dateStr, e.getMessage());
            return null;
        }
    }

    /**
     * List available screen memory dates (file names without extension).
     */
    public String listDates() {
        try (Stream<Path> files = Files.list(SCREEN_MEMORY_DIR)) {
            StringBuilder sb = new StringBuilder();
            files.filter(p -> p.toString().endsWith(".txt"))
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        String name = p.getFileName().toString().replace(".txt", "");
                        try {
                            long size = Files.size(p);
                            long lines = Files.lines(p).count();
                            sb.append(name).append(" (").append(lines)
                                    .append(" entries, ").append(size / 1024).append("KB)\n");
                        } catch (IOException e) {
                            sb.append(name).append("\n");
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

    // ═══ Helpers ═══

    private Path findLatestScreenshot() {
        if (!Files.exists(SCREENSHOTS_DIR)) return null;
        try (Stream<Path> files = Files.list(SCREENSHOTS_DIR)) {
            return files.filter(p -> p.toString().endsWith(".png"))
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
