package com.minsbot.diagnostics;

import com.minsbot.firstrun.ComfyUiInstallerService;
import com.minsbot.firstrun.FirstRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.awt.GraphicsEnvironment;
import java.awt.DisplayMode;
import java.awt.GraphicsDevice;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Runs a battery of health checks so the user can see — at a glance — whether
 * Mins Bot is ready to run, and which subsystems are broken. Each check returns
 * a consistent shape so the Diagnostics page can render them uniformly with
 * "status dot + message + optional fix action" rows.
 *
 * <p>Status tiers:
 * <ul>
 *   <li>{@link #STATUS_OK} — healthy</li>
 *   <li>{@link #STATUS_INFO} — optional feature not configured (no attention needed)</li>
 *   <li>{@link #STATUS_WARN} — suboptimal but usable</li>
 *   <li>{@link #STATUS_ERROR} — broken, required subsystem</li>
 * </ul>
 *
 * <p>Each check also carries a {@code category} so the UI can group + filter
 * ({@code runtime} / {@code local-ai} / {@code hardware} / {@code network} / {@code api-keys}).
 */
@Service
public class DiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsService.class);

    public static final String STATUS_OK = "ok";
    public static final String STATUS_INFO = "info";
    public static final String STATUS_WARN = "warn";
    public static final String STATUS_ERROR = "error";

    public static final String CAT_RUNTIME = "runtime";
    public static final String CAT_LOCAL_AI = "local-ai";
    public static final String CAT_HARDWARE = "hardware";
    public static final String CAT_NETWORK = "network";
    public static final String CAT_API_KEYS = "api-keys";

    private final FirstRunService firstRun;
    private final ComfyUiInstallerService comfyInstaller;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiKey;
    @Value("${app.claude.api-key:}")
    private String anthropicKey;
    @Value("${app.gemini.api-key:}")
    private String geminiKey;
    @Value("${app.elevenlabs.api-key:}")
    private String elevenLabsKey;
    @Value("${app.heygen.api-key:}")
    private String heyGenKey;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public DiagnosticsService(FirstRunService firstRun, ComfyUiInstallerService comfyInstaller) {
        this.firstRun = firstRun;
        this.comfyInstaller = comfyInstaller;
    }

    /** Returns all checks plus a summary. */
    public Map<String, Object> runAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", System.currentTimeMillis());
        List<Map<String, Object>> checks = new ArrayList<>();

        // Runtime
        checks.add(checkJava());
        checks.add(checkOs());
        checks.add(checkCpu());
        checks.add(checkRam());
        checks.add(checkDisk());
        checks.add(checkTimezone());

        // Hardware
        checks.add(checkGpu());
        checks.add(checkScreen());
        checks.add(checkAudioOutput());
        checks.add(checkMicrophone());

        // Network
        checks.add(checkNetwork());

        // Local AI
        checks.add(checkOllamaInstalled());
        checks.add(checkOllamaRunning());
        checks.add(checkOllamaModels());
        checks.add(checkGit());
        checks.add(checkPython());
        checks.add(checkComfyUiInstalled());
        checks.add(checkComfyUiRunning());
        checks.add(checkComfyUiCudaTorch());

        // API keys (optional — use INFO when unset)
        checks.add(checkApiKey("openai", "OpenAI API key", openAiKey,
                "Enables GPT-4/GPT-5 chat. Local Ollama works without this."));
        checks.add(checkApiKey("anthropic", "Anthropic API key", anthropicKey,
                "Enables Claude as a chat backend. Optional."));
        checks.add(checkApiKey("gemini", "Google Gemini API key", geminiKey,
                "Enables Gemini + Veo video. Optional."));
        checks.add(checkApiKey("elevenlabs", "ElevenLabs API key", elevenLabsKey,
                "Premium TTS voice. Falls back to Windows SAPI without it."));
        checks.add(checkApiKey("heygen", "HeyGen API key", heyGenKey,
                "AI avatar video generation. Optional."));

        out.put("checks", checks);
        out.put("summary", summarize(checks));
        out.put("categories", categorySummary(checks));
        return out;
    }

    // ═══ Runtime ═══════════════════════════════════════════════════

    private Map<String, Object> checkJava() {
        String ver = System.getProperty("java.version", "?");
        long maxMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        long totalMb = Runtime.getRuntime().totalMemory() / (1024L * 1024L);
        long freeMb = Runtime.getRuntime().freeMemory() / (1024L * 1024L);
        long usedMb = totalMb - freeMb;
        return check("java", CAT_RUNTIME, "Java runtime", STATUS_OK,
                "Java " + ver + " · " + usedMb + " / " + maxMb + " MB heap", null, null);
    }

    private Map<String, Object> checkOs() {
        String name = System.getProperty("os.name", "?");
        String version = System.getProperty("os.version", "?");
        String arch = System.getProperty("os.arch", "?");
        return check("os", CAT_RUNTIME, "Operating system", STATUS_OK,
                name + " " + version + " (" + arch + ")", null, null);
    }

    private Map<String, Object> checkCpu() {
        int cores = Runtime.getRuntime().availableProcessors();
        String name = cpuName();
        String msg = (name == null) ? cores + " logical cores" : name + " · " + cores + " logical cores";
        return check("cpu", CAT_RUNTIME, "CPU", STATUS_OK, msg, null, null);
    }

    private Map<String, Object> checkRam() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long totalMb = os.getTotalMemorySize() / (1024L * 1024L);
            long freeMb = os.getFreeMemorySize() / (1024L * 1024L);
            long totalGb = totalMb / 1024L;
            long freeGb = freeMb / 1024L;
            String status = totalGb >= 16 ? STATUS_OK : (totalGb >= 8 ? STATUS_WARN : STATUS_ERROR);
            String msg = totalGb + " GB total · " + freeGb + " GB free";
            if (totalGb < 8) msg += " — 8 GB minimum recommended";
            return check("ram", CAT_RUNTIME, "System RAM", status, msg, null, null);
        } catch (Throwable e) {
            long approxMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
            return check("ram", CAT_RUNTIME, "System RAM", STATUS_INFO,
                    "Heap max " + approxMb + " MB (couldn't probe system total)", null, null);
        }
    }

    private Map<String, Object> checkDisk() {
        int freeGb = firstRun.freeDiskGb();
        if (freeGb < 0) {
            return check("disk", CAT_RUNTIME, "Disk space", STATUS_WARN,
                    "Couldn't determine free space", null, null);
        }
        String status = freeGb < 10 ? STATUS_ERROR : (freeGb < 30 ? STATUS_WARN : STATUS_OK);
        String msg = freeGb + " GB free";
        if (freeGb < 10) msg += " — model installs will fail. Free at least 10 GB.";
        else if (freeGb < 30) msg += " — tight for multiple models. Consider freeing space.";
        return check("disk", CAT_RUNTIME, "Disk space", status, msg, null, null);
    }

    private Map<String, Object> checkTimezone() {
        String tz = ZoneId.systemDefault().getId();
        String locale = Locale.getDefault().toString();
        String offset = TimeZone.getDefault().getDisplayName(TimeZone.getDefault().inDaylightTime(new java.util.Date()), TimeZone.SHORT);
        return check("timezone", CAT_RUNTIME, "Timezone & locale", STATUS_INFO,
                tz + " (" + offset + ") · " + locale, null, null);
    }

    // ═══ Hardware ══════════════════════════════════════════════════

    private Map<String, Object> checkGpu() {
        Map<String, Object> gpu = firstRun.detectGpu();
        boolean available = Boolean.TRUE.equals(gpu.get("available"));
        if (!available) {
            return check("gpu", CAT_HARDWARE, "NVIDIA GPU", STATUS_WARN,
                    "No NVIDIA GPU detected — CPU inference will be very slow. Image gen unavailable.",
                    null, null);
        }
        int vram = (gpu.get("vramGb") instanceof Number) ? ((Number) gpu.get("vramGb")).intValue() : 0;
        String name = String.valueOf(gpu.getOrDefault("name", "unknown"));
        String status = vram >= 8 ? STATUS_OK : (vram >= 4 ? STATUS_WARN : STATUS_ERROR);
        return check("gpu", CAT_HARDWARE, "NVIDIA GPU", status, name + " · " + vram + " GB VRAM", null, null);
    }

    private Map<String, Object> checkScreen() {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return check("screen", CAT_HARDWARE, "Display", STATUS_INFO, "Headless — no display", null, null);
            }
            GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            if (devices.length == 0) {
                return check("screen", CAT_HARDWARE, "Display", STATUS_WARN, "No displays found", null, null);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < devices.length; i++) {
                DisplayMode dm = devices[i].getDisplayMode();
                if (sb.length() > 0) sb.append(" · ");
                sb.append(dm.getWidth()).append("×").append(dm.getHeight());
                if (dm.getRefreshRate() > 0) sb.append(" @ ").append(dm.getRefreshRate()).append(" Hz");
            }
            String label = devices.length == 1 ? "Display" : devices.length + " displays";
            return check("screen", CAT_HARDWARE, label, STATUS_OK, sb.toString(), null, null);
        } catch (Throwable e) {
            return check("screen", CAT_HARDWARE, "Display", STATUS_INFO,
                    "Probe failed: " + e.getClass().getSimpleName(), null, null);
        }
    }

    private Map<String, Object> checkAudioOutput() {
        try {
            int outputs = 0;
            String firstName = null;
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(info);
                if (mixer.getSourceLineInfo().length > 0) {
                    outputs++;
                    if (firstName == null) firstName = info.getName();
                }
            }
            if (outputs == 0) {
                return check("audio-out", CAT_HARDWARE, "Audio output", STATUS_WARN,
                        "No output devices — TTS will be silent", null, null);
            }
            String msg = outputs == 1 ? firstName : outputs + " devices · default: " + firstName;
            return check("audio-out", CAT_HARDWARE, "Audio output", STATUS_OK, msg, null, null);
        } catch (Throwable e) {
            return check("audio-out", CAT_HARDWARE, "Audio output", STATUS_INFO,
                    "Probe failed: " + e.getClass().getSimpleName(), null, null);
        }
    }

    private Map<String, Object> checkMicrophone() {
        try {
            int inputs = 0;
            String firstName = null;
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(info);
                if (mixer.getTargetLineInfo().length > 0) {
                    inputs++;
                    if (firstName == null) firstName = info.getName();
                }
            }
            if (inputs == 0) {
                return check("microphone", CAT_HARDWARE, "Microphone", STATUS_WARN,
                        "No microphones — voice input disabled", null, null);
            }
            String msg = inputs == 1 ? firstName : inputs + " devices · default: " + firstName;
            return check("microphone", CAT_HARDWARE, "Microphone", STATUS_OK, msg, null, null);
        } catch (Throwable e) {
            return check("microphone", CAT_HARDWARE, "Microphone", STATUS_INFO,
                    "Probe failed: " + e.getClass().getSimpleName(), null, null);
        }
    }

    // ═══ Network ═══════════════════════════════════════════════════

    private Map<String, Object> checkNetwork() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://github.com"))
                    .timeout(Duration.ofSeconds(3))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 < 4) {
                return check("network", CAT_NETWORK, "Internet connectivity", STATUS_OK,
                        "Reachable (HTTP " + resp.statusCode() + ")", null, null);
            }
            return check("network", CAT_NETWORK, "Internet connectivity", STATUS_WARN,
                    "github.com returned HTTP " + resp.statusCode(), null, null);
        } catch (Exception e) {
            return check("network", CAT_NETWORK, "Internet connectivity", STATUS_ERROR,
                    "Cannot reach github.com: " + e.getClass().getSimpleName(), null, null);
        }
    }

    // ═══ Local AI ══════════════════════════════════════════════════

    private Map<String, Object> checkOllamaInstalled() {
        boolean ok = firstRun.isOllamaInstalled();
        return check("ollama-installed", CAT_LOCAL_AI, "Ollama installed",
                ok ? STATUS_OK : STATUS_ERROR,
                ok ? "Binary on PATH" : "Not installed — required for local LLMs",
                ok ? null : "install-ollama",
                ok ? null : "/api/setup/install-ollama");
    }

    private Map<String, Object> checkOllamaRunning() {
        if (!firstRun.isOllamaInstalled()) {
            return check("ollama-running", CAT_LOCAL_AI, "Ollama running", STATUS_INFO,
                    "N/A — install first", null, null);
        }
        boolean running = firstRun.isOllamaRunning();
        return check("ollama-running", CAT_LOCAL_AI, "Ollama running",
                running ? STATUS_OK : STATUS_ERROR,
                running ? "Responding on localhost:11434" : "Installed but not running",
                running ? null : "start-ollama",
                running ? null : "/api/setup/start-ollama-service");
    }

    private Map<String, Object> checkOllamaModels() {
        if (!firstRun.isOllamaRunning()) {
            return check("ollama-models", CAT_LOCAL_AI, "Local LLM models", STATUS_INFO,
                    "Ollama not running", null, null);
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:11434/api/tags"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int count = countOccurrences(resp.body(), "\"name\":\"");
            if (count == 0) {
                return check("ollama-models", CAT_LOCAL_AI, "Local LLM models", STATUS_WARN,
                        "None installed — use the Models tab to download one", null, null);
            }
            return check("ollama-models", CAT_LOCAL_AI, "Local LLM models", STATUS_OK,
                    count + " installed", null, null);
        } catch (Exception e) {
            return check("ollama-models", CAT_LOCAL_AI, "Local LLM models", STATUS_WARN,
                    "Couldn't list: " + e.getMessage(), null, null);
        }
    }

    private Map<String, Object> checkGit() {
        String v = firstLine("git", "--version");
        if (v == null) {
            return check("git", CAT_LOCAL_AI, "Git on PATH", STATUS_WARN,
                    "Not found — needed to clone ComfyUI. Install from https://git-scm.com/download/win",
                    null, null);
        }
        return check("git", CAT_LOCAL_AI, "Git on PATH", STATUS_OK, v, null, null);
    }

    private Map<String, Object> checkPython() {
        String[] candidates = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? new String[]{"python", "py"} : new String[]{"python3", "python"};
        for (String c : candidates) {
            String v = firstLine(c, "--version");
            if (v == null) continue;
            int[] mm = parsePy(v);
            if (mm != null && (mm[0] > 3 || (mm[0] == 3 && mm[1] >= 10))) {
                return check("python", CAT_LOCAL_AI, "Python 3.10+", STATUS_OK, v + " (" + c + ")", null, null);
            }
            if (mm != null) {
                return check("python", CAT_LOCAL_AI, "Python 3.10+", STATUS_WARN,
                        v + " found — ComfyUI needs 3.10 or newer", null, null);
            }
        }
        return check("python", CAT_LOCAL_AI, "Python 3.10+", STATUS_WARN,
                "Not found — needed for ComfyUI install", null, null);
    }

    private Map<String, Object> checkComfyUiInstalled() {
        boolean ok = comfyInstaller.isInstalled();
        return check("comfy-installed", CAT_LOCAL_AI, "ComfyUI installed",
                ok ? STATUS_OK : STATUS_INFO,
                ok ? comfyInstaller.root().toString() : "Not installed — required for local image generation",
                null, null);
    }

    private Map<String, Object> checkComfyUiRunning() {
        if (!comfyInstaller.isInstalled()) {
            return check("comfy-running", CAT_LOCAL_AI, "ComfyUI running", STATUS_INFO,
                    "N/A — install first", null, null);
        }
        boolean running = comfyInstaller.isRunning();
        return check("comfy-running", CAT_LOCAL_AI, "ComfyUI running",
                running ? STATUS_OK : STATUS_WARN,
                running ? "Responding on localhost:8188" : "Installed but not running",
                null, null);
    }

    private Map<String, Object> checkComfyUiCudaTorch() {
        if (!comfyInstaller.isInstalled()) {
            return check("comfy-cuda", CAT_LOCAL_AI, "PyTorch CUDA build", STATUS_INFO,
                    "N/A — ComfyUI not installed", null, null);
        }
        Path venvPython = comfyInstaller.root().resolve(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? "venv/Scripts/python.exe" : "venv/bin/python");
        if (!Files.exists(venvPython)) {
            return check("comfy-cuda", CAT_LOCAL_AI, "PyTorch CUDA build", STATUS_WARN,
                    "venv not ready", null, null);
        }
        try {
            Process p = new ProcessBuilder(venvPython.toString(), "-c",
                    "import torch, sys; sys.exit(0 if torch.cuda.is_available() else 1)")
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                return check("comfy-cuda", CAT_LOCAL_AI, "PyTorch CUDA build", STATUS_OK, "CUDA active", null, null);
            }
            return check("comfy-cuda", CAT_LOCAL_AI, "PyTorch CUDA build", STATUS_ERROR,
                    "CPU-only wheel installed — image gen will fail. Re-run the ComfyUI installer to fix.",
                    null, null);
        } catch (Exception e) {
            return check("comfy-cuda", CAT_LOCAL_AI, "PyTorch CUDA build", STATUS_WARN,
                    "Probe failed: " + e.getMessage(), null, null);
        }
    }

    // ═══ API keys (optional — use INFO when unset) ═════════════════

    private Map<String, Object> checkApiKey(String id, String label, String value, String whenMissing) {
        boolean set = value != null && !value.isBlank() && !value.startsWith("sk-xxx") && value.length() > 10;
        return check("key-" + id, CAT_API_KEYS, label,
                set ? STATUS_OK : STATUS_INFO,
                set ? "Set (hidden)" : "Not configured — " + whenMissing,
                null, null);
    }

    // ═══ helpers ═══════════════════════════════════════════════════

    /** Cache CPU model name for the JVM lifetime — wmic spawn is ~200ms. */
    private static volatile String CACHED_CPU_NAME;

    private static String cpuName() {
        if (CACHED_CPU_NAME != null) return CACHED_CPU_NAME.isEmpty() ? null : CACHED_CPU_NAME;
        String resolved = "";
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (win) {
            String out = firstLine("wmic", "cpu", "get", "name", "/value");
            // Try again without /value if the first attempt returned the header
            if (out == null || out.isBlank() || out.equalsIgnoreCase("Name")) {
                try {
                    Process p = new ProcessBuilder("wmic", "cpu", "get", "name").redirectErrorStream(true).start();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            String t = line.trim();
                            if (!t.isEmpty() && !"Name".equalsIgnoreCase(t)) { resolved = t; break; }
                        }
                    }
                    p.waitFor(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            } else {
                int eq = out.indexOf('=');
                if (eq >= 0) resolved = out.substring(eq + 1).trim();
            }
        } else {
            // macOS: sysctl; Linux: /proc/cpuinfo
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
                String s = firstLine("sysctl", "-n", "machdep.cpu.brand_string");
                if (s != null) resolved = s;
            } else {
                try {
                    for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                        if (line.startsWith("model name")) {
                            int colon = line.indexOf(':');
                            if (colon > 0) { resolved = line.substring(colon + 1).trim(); break; }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        CACHED_CPU_NAME = resolved == null ? "" : resolved;
        return CACHED_CPU_NAME.isEmpty() ? null : CACHED_CPU_NAME;
    }

    private static Map<String, Object> check(String id, String category, String label, String status,
                                             String message, String actionId, String actionEndpoint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("category", category);
        m.put("label", label);
        m.put("status", status);
        m.put("message", message);
        if (actionId != null) m.put("actionId", actionId);
        if (actionEndpoint != null) m.put("actionEndpoint", actionEndpoint);
        return m;
    }

    private static Map<String, Object> summarize(List<Map<String, Object>> checks) {
        int ok = 0, info = 0, warn = 0, err = 0;
        for (Map<String, Object> c : checks) {
            switch (String.valueOf(c.get("status"))) {
                case STATUS_OK: ok++; break;
                case STATUS_INFO: info++; break;
                case STATUS_WARN: warn++; break;
                case STATUS_ERROR: err++; break;
            }
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("ok", ok);
        s.put("info", info);
        s.put("warn", warn);
        s.put("error", err);
        s.put("total", checks.size());
        s.put("overall", err > 0 ? STATUS_ERROR : (warn > 0 ? STATUS_WARN : STATUS_OK));
        return s;
    }

    private static List<Map<String, Object>> categorySummary(List<Map<String, Object>> checks) {
        // Preserve declaration order.
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
        for (Map<String, Object> c : checks) {
            String cat = String.valueOf(c.getOrDefault("category", "other"));
            int[] arr = counts.computeIfAbsent(cat, k -> new int[4]); // ok, info, warn, err
            switch (String.valueOf(c.get("status"))) {
                case STATUS_OK: arr[0]++; break;
                case STATUS_INFO: arr[1]++; break;
                case STATUS_WARN: arr[2]++; break;
                case STATUS_ERROR: arr[3]++; break;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int[] a = e.getValue();
            int total = a[0] + a[1] + a[2] + a[3];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("label", categoryLabel(e.getKey()));
            m.put("ok", a[0]);
            m.put("info", a[1]);
            m.put("warn", a[2]);
            m.put("error", a[3]);
            m.put("total", total);
            out.add(m);
        }
        return out;
    }

    private static String categoryLabel(String id) {
        switch (id) {
            case CAT_RUNTIME:  return "Runtime";
            case CAT_LOCAL_AI: return "Local AI";
            case CAT_HARDWARE: return "Hardware";
            case CAT_NETWORK:  return "Network";
            case CAT_API_KEYS: return "API keys";
            default:           return id;
        }
    }

    private static String firstLine(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                out = r.readLine();
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;
            return out == null ? "" : out.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static int[] parsePy(String verLine) {
        if (verLine == null) return null;
        String s = verLine.toLowerCase(Locale.ROOT).replace("python", "").trim();
        String[] parts = s.split("\\.");
        if (parts.length < 2) return null;
        try {
            return new int[]{
                    Integer.parseInt(parts[0].replaceAll("\\D", "")),
                    Integer.parseInt(parts[1].replaceAll("\\D", ""))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) { count++; idx += needle.length(); }
        return count;
    }
}
