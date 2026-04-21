package com.minsbot.firstrun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks first-launch setup state. When true, the UI shows a "getting started"
 * overlay that offers to install Ollama + pull a default model so the bot
 * works offline-first with no cloud API key.
 */
@Service
public class FirstRunService {

    private static final Logger log = LoggerFactory.getLogger(FirstRunService.class);
    private static final Path FLAG = Paths.get("memory", "first-run-complete.flag").toAbsolutePath();
    private static final Path ACTIVE_MODEL_FLAG = Paths.get("memory", "active-model.txt").toAbsolutePath();

    /** Default local model — small enough to pull in a few minutes on a typical connection. */
    public static final String DEFAULT_MODEL = "llama3.2:3b";
    private static final String OLLAMA_API = "http://localhost:11434";
    private static final String COMFYUI_API = "http://localhost:8188";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** Cached GPU probe — nvidia-smi takes ~300ms, re-probe at most every 30 s. */
    private volatile Map<String, Object> cachedGpu;
    private volatile long cachedGpuAt;

    public boolean isFirstRun() {
        return !Files.exists(FLAG);
    }

    public void markComplete() {
        try {
            Files.createDirectories(FLAG.getParent());
            Files.writeString(FLAG, "1");
            log.info("[FirstRun] marked complete");
        } catch (IOException e) {
            log.warn("[FirstRun] persist failed: {}", e.getMessage());
        }
    }

    public boolean isOllamaInstalled() {
        // Check PATH + common install locations
        try {
            Process p = new ProcessBuilder("ollama", "--version").redirectErrorStream(true).start();
            boolean done = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) return true;
        } catch (Exception ignored) {}

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path p = Paths.get(localAppData, "Programs", "Ollama", "ollama.exe");
            if (Files.exists(p)) return true;
        }
        return false;
    }

    public boolean isOllamaRunning() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(OLLAMA_API + "/api/version"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasModel(String modelName) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(OLLAMA_API + "/api/tags"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return false;
            return resp.body().contains("\"name\":\"" + modelName + "\"")
                    || resp.body().contains("\"name\": \"" + modelName + "\"");
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean installed = isOllamaInstalled();
        boolean running = installed && isOllamaRunning();
        String active = getActiveModel();
        boolean hasActive = running && hasModel(active);
        out.put("firstRun", isFirstRun());
        out.put("ollamaInstalled", installed);
        out.put("ollamaRunning", running);
        out.put("defaultModel", active);
        out.put("defaultModelReady", hasActive);
        out.put("isCustomDefault", !DEFAULT_MODEL.equals(active));
        out.put("setupComplete", installed && running && hasActive);
        out.put("comfyUiRunning", isComfyUiRunning());
        out.put("gpu", detectGpu());
        out.put("freeDiskGb", freeDiskGb());
        return out;
    }

    /** User-selected default, or {@link #DEFAULT_MODEL} when unset. */
    public String getActiveModel() {
        try {
            if (Files.exists(ACTIVE_MODEL_FLAG)) {
                String v = Files.readString(ACTIVE_MODEL_FLAG).trim();
                if (!v.isEmpty()) return v;
            }
        } catch (IOException ignored) {}
        return DEFAULT_MODEL;
    }

    /** Persist the user's preferred default model. Pass null/blank to reset. */
    public void setActiveModel(String model) {
        try {
            Files.createDirectories(ACTIVE_MODEL_FLAG.getParent());
            if (model == null || model.isBlank()) {
                Files.deleteIfExists(ACTIVE_MODEL_FLAG);
            } else {
                Files.writeString(ACTIVE_MODEL_FLAG, model.trim());
            }
            log.info("[FirstRun] active model set to: {}", model);
        } catch (IOException e) {
            log.warn("[FirstRun] setActiveModel failed: {}", e.getMessage());
        }
    }

    /** Free disk space (GB) on the partition holding Ollama's model cache. */
    public int freeDiskGb() {
        Path probe = Paths.get(System.getProperty("user.home"), ".ollama");
        Path root = Files.exists(probe) ? probe : Paths.get(System.getProperty("user.home"));
        try {
            return (int) (Files.getFileStore(root).getUsableSpace() / (1024L * 1024L * 1024L));
        } catch (IOException e) {
            return -1;
        }
    }

    /** True if a ComfyUI server is responding on {@code localhost:8188}. */
    public boolean isComfyUiRunning() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(COMFYUI_API + "/system_stats"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Probes {@code nvidia-smi} for the primary GPU. Returns a map with
     * {@code available} / {@code name} / {@code vramGb}, or {@code available=false}
     * when no NVIDIA GPU is detected (AMD / Intel / no GPU).
     *
     * <p>Cached for 30 s so the models page can poll without paying the
     * subprocess cost on every render.</p>
     */
    public Map<String, Object> detectGpu() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = cachedGpu;
        if (cached != null && (now - cachedGpuAt) < 30_000) {
            return cached;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Process p = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-gpu=name,memory.total",
                    "--format=csv,noheader,nounits")
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                String out = new String(p.getInputStream().readAllBytes()).trim();
                String firstLine = out.split("\\R", 2)[0].trim();
                if (!firstLine.isEmpty()) {
                    String[] parts = firstLine.split(",", 2);
                    String name = parts[0].trim();
                    int vramGb = 0;
                    if (parts.length > 1) {
                        try {
                            long mb = Long.parseLong(parts[1].trim());
                            vramGb = (int) Math.round(mb / 1024.0);
                        } catch (NumberFormatException ignored) {}
                    }
                    result.put("available", true);
                    result.put("vendor", "nvidia");
                    result.put("name", name);
                    result.put("vramGb", vramGb);
                }
            }
        } catch (Exception e) {
            log.debug("[FirstRun] nvidia-smi probe failed: {}", e.getMessage());
        }
        if (!result.containsKey("available")) {
            result.put("available", false);
            result.put("vendor", "none");
            result.put("vramGb", 0);
        }
        cachedGpu = result;
        cachedGpuAt = now;
        return result;
    }
}
