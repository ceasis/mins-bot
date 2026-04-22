package com.minsbot.agent.tools;

import com.minsbot.TranscriptService;
import com.minsbot.firstrun.ComfyUiInstallerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Local text-to-image via ComfyUI. Expects ComfyUI running on {@code localhost:8188}
 * with at least one SDXL / SDXL-Lightning checkpoint in {@code models/checkpoints}.
 *
 * <p>Submits a minimal SDXL Lightning workflow (4 steps, CFG 1.5, euler/sgm_uniform),
 * polls {@code /history/<id>} until the PNG appears, pulls the bytes via
 * {@code /view?filename=...}, and writes them to
 * {@code ~/mins_bot_data/generated/<timestamp>.png}.</p>
 */
@Component
public class LocalImageTools {

    private static final Logger log = LoggerFactory.getLogger(LocalImageTools.class);
    private static final String COMFY_API = "http://localhost:8188";
    private static final Path OUTPUT_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "generated");

    private final ToolExecutionNotifier notifier;
    private final ComfyUiInstallerService comfyInstaller;
    private final TranscriptService transcriptService;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Random rng = new Random();

    public LocalImageTools(ToolExecutionNotifier notifier,
                           ComfyUiInstallerService comfyInstaller,
                           TranscriptService transcriptService) {
        this.notifier = notifier;
        this.comfyInstaller = comfyInstaller;
        this.transcriptService = transcriptService;
    }

    @Tool(description =
            "Generate one or more images on this machine via local ComfyUI. Call this when the user asks to create, " +
            "draw, generate, make, render, or paint an image/picture/illustration/portrait/scene/artwork. " +
            "Examples: 'create me a boy playing with a red ball', 'draw a sunset over Manila', 'make a logo of a fox'. " +
            "CRITICAL — FOR MULTIPLE IMAGES:\n" +
            "  • If the user asks for N images (\"2 images\", \"three pictures\", \"a few versions\"), " +
            "pass count=N in a SINGLE call. Do NOT call this tool multiple times for N images.\n" +
            "IMPORTANT BEHAVIOR AFTER CALLING:\n" +
            "  • Do NOT invent or guess a file path in your reply.\n" +
            "  • Do NOT describe what the image looks like (you haven't seen it).\n" +
            "  • Reply with a single brief sentence like 'Starting image generation — I'll post each one here as it finishes.'\n" +
            "  • Each finished image and its real path arrive as separate follow-up messages automatically.\n" +
            "Requires ComfyUI running at localhost:8188 with an SDXL checkpoint installed.")
    public String generateLocalImage(
            @ToolParam(description = "Plain-English description of the image to create") String prompt,
            @ToolParam(description = "Optional: things to avoid in the image (e.g. 'blurry, deformed, text')", required = false)
            String negativePrompt,
            @ToolParam(description =
                    "Optional: which installed checkpoint to use (substring match on filename). " +
                    "Use 'juggernaut' for photorealistic people, portraits, photos, real-world scenes (faces/skin). " +
                    "Use 'sdxl_lightning' (or 'lightning') for illustrations, logos, cartoons, concept art, " +
                    "abstract visuals. Omit to auto-pick.",
                    required = false)
            String checkpoint,
            @ToolParam(description =
                    "How many images to generate. Default 1. Max 4. Use this when the user asks for multiple " +
                    "images in one request (\"2 images\", \"three variations\"). Each runs sequentially — " +
                    "the model stays loaded in VRAM between them so it's much faster than separate calls.",
                    required = false)
            Integer count
    ) {
        notifier.notify("Submitting image job to ComfyUI: " + shortPrompt(prompt));
        if (!comfyInstaller.isRunning()) {
            log.warn("[LocalImage] ComfyUI not running");
            return "Local image generator (ComfyUI) is not running. Open the Models tab → SDXL Lightning → Install everything to start it.";
        }
        final String chosen = resolveCheckpoint(checkpoint);
        if (chosen == null) {
            return "No SDXL checkpoint found in ComfyUI/models/checkpoints. Install an image model from the Models tab first.";
        }
        if (chosen.toLowerCase().contains("flux")) {
            return "FLUX checkpoints need a different workflow than SDXL — not wired yet. Install an SDXL Lightning model for now.";
        }

        // Clamp count to a sane range. LLMs occasionally overshoot ("make me a bunch") —
        // cap at 4 so a bad arg can't tie up the GPU for 20 minutes.
        final int n = (count == null || count < 1) ? 1 : Math.min(count, 4);

        final String neg = (negativePrompt == null || negativePrompt.isBlank())
                ? "blurry, deformed, low quality, watermark, text"
                : negativePrompt;
        final String finalPrompt = prompt;

        Thread t = new Thread(() -> {
            for (int i = 1; i <= n; i++) {
                final int index = i;
                long seed = rng.nextInt(Integer.MAX_VALUE);
                String clientId = "minsbot-" + System.currentTimeMillis() + "-" + index;
                String workflow = buildSdxlLightningWorkflow(finalPrompt, neg, chosen, seed);
                log.info("[LocalImage] Submitting job {}/{} — checkpoint={}, seed={}, prompt={}",
                        index, n, chosen, seed, shortPrompt(finalPrompt));

                final String promptId;
                try {
                    String body = "{\"prompt\":" + workflow + ",\"client_id\":\"" + clientId + "\"}";
                    HttpRequest submit = HttpRequest.newBuilder(URI.create(COMFY_API + "/prompt"))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<String> submitResp = http.send(submit, HttpResponse.BodyHandlers.ofString());
                    log.info("[LocalImage] /prompt HTTP {} (job {}/{}) — body: {}",
                            submitResp.statusCode(), index, n,
                            submitResp.body().length() > 300 ? submitResp.body().substring(0, 300) + "…" : submitResp.body());
                    if (submitResp.statusCode() != 200) {
                        transcriptService.save("BOT", "Image " + index + "/" + n
                                + " — ComfyUI rejected the job (HTTP " + submitResp.statusCode() + "): " + submitResp.body());
                        continue;
                    }
                    String id = extractPromptId(submitResp.body());
                    if (id == null) {
                        transcriptService.save("BOT", "Image " + index + "/" + n
                                + " — couldn't parse prompt id from ComfyUI response.");
                        continue;
                    }
                    promptId = id;
                } catch (Exception e) {
                    log.warn("[LocalImage] submit {}/{} failed: {}", index, n, e.getMessage(), e);
                    transcriptService.save("BOT", "Image " + index + "/" + n + " — submit failed: " + e.getMessage());
                    continue;
                }

                try {
                    String resultMsg = awaitImage(promptId, finalPrompt, chosen);
                    String prefix = (n > 1) ? "Image " + index + "/" + n + ":\n" : "";
                    log.info("[LocalImage] Job {}/{} ({}) finished", index, n, promptId);
                    transcriptService.save("BOT", prefix + resultMsg);
                } catch (Exception e) {
                    log.warn("[LocalImage] poll {}/{} crashed: {}", index, n, e.getMessage(), e);
                    transcriptService.save("BOT", "Image " + index + "/" + n + " — generation crashed: " + e.getMessage());
                }
            }
        }, "local-image-batch-" + System.currentTimeMillis());
        t.setDaemon(true);
        t.start();

        // Sync return keeps the LLM from fabricating paths. Real images post as each finishes.
        return (n == 1)
                ? "Image job submitted to local ComfyUI. Reply briefly — do NOT invent a path or describe the image. "
                        + "The real path will auto-post shortly."
                : n + " image jobs queued to local ComfyUI. Reply briefly — do NOT invent paths or describe the images. "
                        + "Each image will auto-post as it finishes (~10 s per image on SDXL Lightning).";
    }

    /** Blocking poll — runs on a background thread. Returns a human-readable result. */
    private String awaitImage(String promptId, String prompt, String checkpoint) throws Exception {
        notifier.notify("Waiting on ComfyUI…");
        String[] filenamePath = null;
        String lastHistoryBody = "";
        String lastQueueState = "unknown";
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(4).toMillis();
        int ticks = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1500);
            ticks++;

            HttpRequest hReq = HttpRequest.newBuilder(URI.create(COMFY_API + "/history/" + promptId))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> hResp = http.send(hReq, HttpResponse.BodyHandlers.ofString());
            if (hResp.statusCode() == 200 && hResp.body().contains(promptId)) {
                lastHistoryBody = hResp.body();
                String err = extractErrorMessage(lastHistoryBody);
                if (err != null) {
                    return "ComfyUI rejected the workflow: " + err + "\n(Checkpoint tried: " + checkpoint + ")";
                }
                filenamePath = extractFirstImage(lastHistoryBody);
                if (filenamePath != null) break;
            }

            if (ticks % 4 == 0) {
                try {
                    HttpRequest qReq = HttpRequest.newBuilder(URI.create(COMFY_API + "/queue"))
                            .timeout(Duration.ofSeconds(5)).GET().build();
                    HttpResponse<String> qResp = http.send(qReq, HttpResponse.BodyHandlers.ofString());
                    if (qResp.statusCode() == 200) {
                        if (qResp.body().contains(promptId)) {
                            lastQueueState = qResp.body().contains("queue_running") &&
                                    qResp.body().indexOf(promptId) > qResp.body().indexOf("queue_running")
                                    ? "running" : "queued";
                        } else {
                            lastQueueState = "not in queue";
                        }
                        log.info("[LocalImage] Job {} state: {} (t≈{}s)", promptId, lastQueueState, ticks * 1.5);
                    }
                } catch (Exception ignored) {}
            }
        }
        if (filenamePath == null) {
            String logPath = comfyInstaller.root().resolve("comfyui.log").toString().replace('\\', '/');
            return "Image generation timed out after 4 minutes. Last state: " + lastQueueState + ". "
                    + "Usually means (a) checkpoint still loading, (b) CUDA OOM, or (c) bad workflow node. "
                    + "Log: " + logPath
                    + (lastHistoryBody.isEmpty() ? "" : "\nLast /history: " +
                        (lastHistoryBody.length() > 400 ? lastHistoryBody.substring(0, 400) + "…" : lastHistoryBody));
        }

        String viewUrl = COMFY_API + "/view"
                + "?filename=" + URLEncoder.encode(filenamePath[0], StandardCharsets.UTF_8)
                + "&subfolder=" + URLEncoder.encode(filenamePath[1], StandardCharsets.UTF_8)
                + "&type=" + URLEncoder.encode(filenamePath[2], StandardCharsets.UTF_8);
        HttpRequest dl = HttpRequest.newBuilder(URI.create(viewUrl))
                .timeout(Duration.ofMinutes(1)).GET().build();
        HttpResponse<InputStream> dlResp = http.send(dl, HttpResponse.BodyHandlers.ofInputStream());
        if (dlResp.statusCode() != 200) {
            return "Fetched image URL returned " + dlResp.statusCode() + " — " + viewUrl;
        }
        Files.createDirectories(OUTPUT_DIR);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path outPath = OUTPUT_DIR.resolve(stamp + "_" + safeFilename(prompt) + ".png");
        try (InputStream in = dlResp.body()) {
            Files.copy(in, outPath, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("[LocalImage] Saved to {}", outPath);
        // The [image:/api/generated/X.png] marker lets the chat UI render an inline <img>
        // with download + show-in-explorer icons baked onto the image itself (top-right,
        // reveal on hover) — so no redundant "Saved to: <path>" text line needed here.
        String url = "/api/generated/" + outPath.getFileName().toString();
        return "Image ready.\n[image:" + url + "]";
    }

    // ═══ helpers ═════════════════════════════════════════════════════

    /**
     * Resolve the checkpoint filename to pass to ComfyUI.
     * <ol>
     *   <li>If the LLM supplied a hint, match it case-insensitively against any installed .safetensors filename.</li>
     *   <li>Otherwise, prefer an SDXL Lightning build (the documented default), then any "lightning" variant, then the first file.</li>
     * </ol>
     * Returns {@code null} when no checkpoints are installed.
     */
    private String resolveCheckpoint(String hint) {
        Path dir = comfyInstaller.root().resolve("models").resolve("checkpoints");
        if (!Files.exists(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> safetensors = s.filter(p -> p.getFileName().toString().endsWith(".safetensors")).toList();
            if (safetensors.isEmpty()) return null;

            // 1. Honor explicit LLM hint (substring, case-insensitive).
            if (hint != null && !hint.isBlank()) {
                String needle = hint.toLowerCase();
                for (Path p : safetensors) {
                    if (p.getFileName().toString().toLowerCase().contains(needle)) {
                        return p.getFileName().toString();
                    }
                }
                log.info("[LocalImage] Checkpoint hint '{}' didn't match any installed file — auto-picking.", hint);
            }

            // 2. Prefer sdxl_lightning (the documented default).
            for (Path p : safetensors) {
                if (p.getFileName().toString().toLowerCase().contains("sdxl_lightning")) {
                    return p.getFileName().toString();
                }
            }
            // 3. Any Lightning variant.
            for (Path p : safetensors) {
                if (p.getFileName().toString().toLowerCase().contains("lightning")) {
                    return p.getFileName().toString();
                }
            }
            // 4. Whatever's there.
            return safetensors.get(0).getFileName().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildSdxlLightningWorkflow(String positive, String negative, String ckpt, long seed) {
        String p = jsonEscape(positive);
        String n = jsonEscape(negative);
        String c = jsonEscape(ckpt);
        return "{"
            + "\"3\":{\"class_type\":\"KSampler\",\"inputs\":{"
            +   "\"seed\":" + seed + ",\"steps\":4,\"cfg\":1.5,"
            +   "\"sampler_name\":\"euler\",\"scheduler\":\"sgm_uniform\",\"denoise\":1.0,"
            +   "\"model\":[\"4\",0],\"positive\":[\"6\",0],\"negative\":[\"7\",0],\"latent_image\":[\"5\",0]}},"
            + "\"4\":{\"class_type\":\"CheckpointLoaderSimple\",\"inputs\":{\"ckpt_name\":\"" + c + "\"}},"
            + "\"5\":{\"class_type\":\"EmptyLatentImage\",\"inputs\":{\"width\":1024,\"height\":1024,\"batch_size\":1}},"
            + "\"6\":{\"class_type\":\"CLIPTextEncode\",\"inputs\":{\"text\":\"" + p + "\",\"clip\":[\"4\",1]}},"
            + "\"7\":{\"class_type\":\"CLIPTextEncode\",\"inputs\":{\"text\":\"" + n + "\",\"clip\":[\"4\",1]}},"
            + "\"8\":{\"class_type\":\"VAEDecode\",\"inputs\":{\"samples\":[\"3\",0],\"vae\":[\"4\",2]}},"
            + "\"9\":{\"class_type\":\"SaveImage\",\"inputs\":{\"filename_prefix\":\"mins_bot\",\"images\":[\"8\",0]}}"
            + "}";
    }

    private static String extractPromptId(String json) {
        // Parse the VALUE of the first {"prompt_id": "<uuid>"} pair.
        // Earlier version was off-by-two quotes and returned the name of the
        // next field ("number") instead of the uuid — which broke all polling.
        int i = json.indexOf("\"prompt_id\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 <= q1) return null;
        return json.substring(q1 + 1, q2);
    }

    /**
     * Very small extractor over the /history/<id> JSON — looks for the first
     * {"filename":"...","subfolder":"...","type":"..."} under outputs.images.
     */
    /**
     * Extract a human-readable error from ComfyUI's /history response. Matches both
     * the top-level {@code status.messages[["execution_error", {...}]]} and per-node
     * {@code <node>.errors} shapes. Returns null when the job has no error recorded yet.
     */
    private static String extractErrorMessage(String historyJson) {
        String[] keys = { "exception_message", "error_details", "\"type\":\"execution_error\"" };
        for (String key : keys) {
            int k = historyJson.indexOf(key);
            if (k < 0) continue;
            // Try to pull the next quoted string as the message
            String msg = grabNextString(historyJson, k, "\"message\"");
            if (msg == null) msg = grabNextString(historyJson, k, "\"exception_message\"");
            if (msg != null && !msg.isBlank()) return msg;
        }
        // Per-node errors look like "node_errors": { "4": { "errors": [...] } }
        int ne = historyJson.indexOf("\"node_errors\"");
        if (ne >= 0) {
            String msg = grabNextString(historyJson, ne, "\"message\"");
            if (msg != null && !msg.isBlank()) return msg;
        }
        return null;
    }

    private static String[] extractFirstImage(String historyJson) {
        int idx = historyJson.indexOf("\"filename\"");
        if (idx < 0) return null;
        String filename = grabJsonString(historyJson, idx);
        String subfolder = grabNextString(historyJson, idx, "\"subfolder\"");
        String type = grabNextString(historyJson, idx, "\"type\"");
        if (filename == null) return null;
        return new String[]{ filename, subfolder == null ? "" : subfolder, type == null ? "output" : type };
    }

    private static String grabJsonString(String json, int keyIdx) {
        int colon = json.indexOf(':', keyIdx);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon);
        int q2 = json.indexOf('"', q1 + 1);
        return (q1 > 0 && q2 > q1) ? json.substring(q1 + 1, q2) : null;
    }

    private static String grabNextString(String json, int fromIdx, String key) {
        int k = json.indexOf(key, fromIdx);
        if (k < 0) return null;
        return grabJsonString(json, k);
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"':  b.append("\\\""); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    private static String safeFilename(String s) {
        if (s == null) return "image";
        String t = s.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (t.length() > 40) t = t.substring(0, 40);
        return t.replace(' ', '_').toLowerCase();
    }

    private static String shortPrompt(String p) {
        if (p == null) return "";
        return p.length() > 60 ? p.substring(0, 57) + "…" : p;
    }
}
