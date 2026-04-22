package com.minsbot.firstrun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One-click ComfyUI installer. Handles prereq checks, clone, venv, pip install,
 * process start, and resumable model file downloads. Progress is reported via a
 * String consumer — the SSE controller serializes those strings to clients.
 *
 * <p>Install root: {@code %USERPROFILE%/mins_bot_data/ComfyUI/}. The folder
 * doubles as both the install location and the ComfyUI working directory.</p>
 */
@Service
public class ComfyUiInstallerService {

    private static final Logger log = LoggerFactory.getLogger(ComfyUiInstallerService.class);
    private static final Path ROOT =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "ComfyUI").toAbsolutePath();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Tracked child process from {@link #startComfyUi}; lets us stop it before pip reinstalls. */
    private volatile Process comfyProcess;

    public Path root() { return ROOT; }

    public boolean isInstalled() {
        return Files.exists(ROOT.resolve("main.py"))
            && Files.exists(ROOT.resolve(isWindows() ? "venv/Scripts/python.exe" : "venv/bin/python"));
    }

    /** @return null when both git and a compatible python are available; otherwise a human-readable reason. */
    public String prereqError() {
        String gitVer = firstLineOf("git", "--version");
        if (gitVer == null) return "git is not installed or not on PATH. Install: https://git-scm.com/download/win";
        String[] pyBins = isWindows() ? new String[]{"python", "py"} : new String[]{"python3", "python"};
        for (String bin : pyBins) {
            String ver = firstLineOf(bin, "--version");
            if (ver == null) continue;
            int[] mm = parsePythonVersion(ver);
            if (mm != null && (mm[0] > 3 || (mm[0] == 3 && mm[1] >= 10))) return null;
        }
        return "Python 3.10+ is required. Install from https://www.python.org/downloads/ (check 'Add to PATH').";
    }

    public String pythonBinary() {
        String[] candidates = isWindows() ? new String[]{"python", "py"} : new String[]{"python3", "python"};
        for (String c : candidates) {
            if (firstLineOf(c, "--version") != null) return c;
        }
        return "python";
    }

    /** Clone + venv + pip install. Streams human-readable log lines to {@code emit}. */
    public void installComfyUi(Consumer<String> emit) throws Exception {
        Files.createDirectories(ROOT.getParent());

        if (!Files.exists(ROOT.resolve(".git"))) {
            emit.accept("log|Cloning ComfyUI…");
            run(ROOT.getParent(), emit, "git", "clone", "--depth", "1",
                    "https://github.com/comfyanonymous/ComfyUI.git", ROOT.getFileName().toString());
        } else {
            emit.accept("log|Existing ComfyUI clone found; skipping clone.");
        }

        Path venvPython = ROOT.resolve(isWindows() ? "venv/Scripts/python.exe" : "venv/bin/python");
        if (!Files.exists(venvPython)) {
            emit.accept("log|Creating Python virtual environment…");
            run(ROOT, emit, pythonBinary(), "-m", "venv", "venv");
        } else {
            emit.accept("log|venv exists; skipping.");
        }

        emit.accept("log|Installing Python dependencies (this is the slow step — downloads PyTorch, ~2 GB)…");
        run(ROOT, emit, venvPython.toString(), "-m", "pip", "install", "--upgrade", "pip");
        run(ROOT, emit, venvPython.toString(), "-m", "pip", "install", "-r",
                ROOT.resolve("requirements.txt").toString());
        ensureCudaTorch(emit);
        emit.accept("log|ComfyUI install finished.");
    }

    /**
     * Detects the CPU-only torch wheel (common failure: {@code torch.cuda.is_available() == False})
     * and force-reinstalls torch/torchvision from the CUDA 12.1 wheel index. No-op when a working
     * CUDA build is already present.
     */
    public void ensureCudaTorch(Consumer<String> emit) throws Exception {
        if (!detectGpuForInstaller()) {
            emit.accept("log|No NVIDIA GPU detected — skipping CUDA torch install (CPU inference will be very slow).");
            return;
        }
        Path venvPython = ROOT.resolve(isWindows() ? "venv/Scripts/python.exe" : "venv/bin/python");
        boolean cudaReady = false;
        try {
            Process p = new ProcessBuilder(venvPython.toString(), "-c",
                    "import torch, sys; sys.exit(0 if torch.cuda.is_available() else 1)")
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(15, TimeUnit.SECONDS);
            cudaReady = done && p.exitValue() == 0;
        } catch (Exception ignored) {}

        if (cudaReady) {
            emit.accept("log|PyTorch CUDA build is already active — no torch reinstall needed.");
            return;
        }

        // Critical: ComfyUI's python holds torch's .pyd files open. Stop it before pip overwrites
        // them, otherwise pip fails with WinError 32 (file in use).
        if (isRunning() || (comfyProcess != null && comfyProcess.isAlive())) {
            emit.accept("log|Stopping ComfyUI so pip can replace the torch files…");
            stopComfyUi(emit);
        }

        emit.accept("log|CPU-only PyTorch detected. Reinstalling CUDA wheels (~2 GB, one time)…");
        // --no-cache-dir sidesteps half-extracted wheels from a previously-interrupted install.
        run(ROOT, emit, venvPython.toString(), "-m", "pip", "install",
                "--upgrade", "--force-reinstall", "--no-cache-dir",
                "torch", "torchvision",
                "--index-url", "https://download.pytorch.org/whl/cu121");
        emit.accept("log|CUDA torch installed.");
    }

    /** Terminate the tracked ComfyUI server and wait up to 10 s for it to exit. */
    public void stopComfyUi(Consumer<String> emit) {
        Process p = comfyProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        comfyProcess = null;

        // Also sweep any orphan ComfyUI python on Windows that isn't tracked (e.g. from an
        // earlier app run). Filters by the venv path so we don't touch unrelated python.exe.
        if (isWindows()) {
            try {
                String venvSubpath = ROOT.resolve("venv\\Scripts\\python.exe").toString().replace("\\", "\\\\");
                new ProcessBuilder("wmic", "process", "where",
                        "ExecutablePath='" + venvSubpath + "'", "delete")
                        .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }

        // Give the OS a beat to release file handles so pip can overwrite.
        try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        emit.accept("log|ComfyUI stopped.");
    }

    /** Lightweight nvidia-smi probe — same idea as {@code FirstRunService.detectGpu} but local. */
    private boolean detectGpuForInstaller() {
        try {
            Process p = new ProcessBuilder("nvidia-smi", "-L").redirectErrorStream(true).start();
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    /** Starts ComfyUI as a detached child process. Returns true if it appears to start within 30 s. */
    public boolean startComfyUi(Consumer<String> emit) throws Exception {
        Path venvPython = ROOT.resolve(isWindows() ? "venv/Scripts/python.exe" : "venv/bin/python");
        if (!Files.exists(venvPython)) throw new IllegalStateException("ComfyUI is not installed");

        emit.accept("log|Starting ComfyUI server (first launch loads PyTorch — can take 30–90 s)…");
        ProcessBuilder pb = new ProcessBuilder(
                venvPython.toString(), "main.py", "--listen", "127.0.0.1", "--port", "8188")
                .directory(ROOT.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ROOT.resolve("comfyui.log").toFile());
        comfyProcess = pb.start();

        // Poll /system_stats for up to 120 s, emitting a heartbeat every 5 s so the UI isn't silent.
        int maxWaitS = 120;
        for (int i = 0; i < maxWaitS; i++) {
            Thread.sleep(1000);
            if (isRunning()) { emit.accept("log|ComfyUI is responding on http://localhost:8188"); return true; }
            if (i > 0 && i % 5 == 0) {
                emit.accept("log|…still waiting for ComfyUI (" + i + "s / " + maxWaitS + "s)");
            }
        }
        emit.accept("log|ComfyUI did not respond in " + maxWaitS + "s — check "
                + ROOT.resolve("comfyui.log"));
        return false;
    }

    public boolean isRunning() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:8188/system_stats"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) { return false; }
    }

    /**
     * Resumable HTTP download into {@code ComfyUI/models/<folder>/<filename>}.
     * Emits {@code progress|<done>|<total>} events.
     */
    public void downloadModel(String folder, String filename, String url,
                              Consumer<String> emit) throws Exception {
        Path modelsDir = ROOT.resolve("models").resolve(folder);
        Files.createDirectories(modelsDir);
        Path target = modelsDir.resolve(filename);
        long existing = Files.exists(target) ? Files.size(target) : 0L;

        // HEAD first to learn the real total. Avoids HTTP 416 when the file is
        // already complete (Range: bytes=N- where N == total).
        long remoteTotal = -1L;
        try {
            HttpRequest headReq = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> headResp = http.send(headReq, HttpResponse.BodyHandlers.discarding());
            if (headResp.statusCode() / 100 == 2) {
                remoteTotal = headResp.headers().firstValueAsLong("Content-Length").orElse(-1L);
            }
        } catch (Exception ignored) { /* HEAD may not be supported on all CDNs; fall through */ }

        if (remoteTotal > 0 && existing == remoteTotal) {
            emit.accept("log|" + filename + " already fully downloaded (" + existing + " bytes) — skipping.");
            emit.accept("progress|" + existing + "|" + remoteTotal);
            return;
        }
        if (remoteTotal > 0 && existing > remoteTotal) {
            emit.accept("log|Local file is larger than remote (" + existing + " > " + remoteTotal
                    + "). Truncating and starting fresh.");
            Files.deleteIfExists(target);
            existing = 0L;
        }

        // Retry loop: on any IOException mid-stream (HuggingFace CDN likes to
        // drop long-lived connections) we pick up where we left off via a fresh
        // Range request. Up to 10 attempts.
        int maxAttempts = 10;
        long done = existing;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).GET();
            // No per-request timeout — large models on slow links can take hours.
            // The server's SSE emitter keeps the client informed via progress events.
            if (done > 0) rb.header("Range", "bytes=" + done + "-");
            HttpRequest req = rb.build();

            emit.accept("log|" + (done > 0 ? "Resuming at " + fmtBytes(done) : "Starting")
                    + " download (attempt " + attempt + "/" + maxAttempts + "): " + filename);
            try {
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int status = resp.statusCode();
                if (status == 416) {
                    // Server says the range is past EOF — we're probably done, verify via HEAD.
                    if (remoteTotal > 0 && done >= remoteTotal) {
                        emit.accept("log|Already at end of file. Done.");
                        emit.accept("progress|" + done + "|" + remoteTotal);
                        return;
                    }
                    emit.accept("log|HTTP 416: truncating and starting from 0.");
                    Files.deleteIfExists(target);
                    done = 0L;
                    continue;
                }
                if (status != 200 && status != 206) {
                    throw new IOException("HTTP " + status + " fetching " + url);
                }
                long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
                long total = (status == 206 && contentLength > 0) ? done + contentLength
                        : (contentLength > 0 ? contentLength : remoteTotal);

                try (InputStream in = resp.body();
                     OutputStream out = Files.newOutputStream(target,
                             done > 0 ? StandardOpenOption.APPEND : StandardOpenOption.CREATE)) {
                    byte[] buf = new byte[65536];
                    long lastEmit = 0L;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (done - lastEmit > 2L * 1024 * 1024 || (total > 0 && done == total)) {
                            emit.accept("progress|" + done + "|" + total);
                            lastEmit = done;
                        }
                    }
                }
                // Stream closed cleanly — verify we actually reached the end.
                if (remoteTotal > 0 && done < remoteTotal) {
                    emit.accept("log|Stream ended at " + fmtBytes(done) + " of " + fmtBytes(remoteTotal)
                            + " — reconnecting for remainder.");
                    continue;
                }
                emit.accept("progress|" + done + "|" + (remoteTotal > 0 ? remoteTotal : done));
                emit.accept("log|Download complete: " + target);
                return;
            } catch (IOException e) {
                emit.accept("log|Stream error at " + fmtBytes(done) + ": " + e.getMessage()
                        + " — retrying in 3 s");
                if (attempt >= maxAttempts) throw e;
                try { Thread.sleep(3000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new IOException("Download failed after " + maxAttempts + " attempts at " + fmtBytes(done));
    }

    private static String fmtBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024L * 1024) return String.format("%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format("%.1f MB", n / (1024.0 * 1024));
        return String.format("%.2f GB", n / (1024.0 * 1024 * 1024));
    }

    // ═══ helpers ═════════════════════════════════════════════════════

    private void run(Path cwd, Consumer<String> emit, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                emit.accept("log|" + line);
            }
        }
        if (!p.waitFor(60, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("Timed out running: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            throw new IOException("Command failed (exit " + p.exitValue() + "): " + String.join(" ", cmd));
        }
    }

    private String firstLineOf(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                out = r.readLine();
            }
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;
            return out == null ? "" : out.trim();
        } catch (Exception e) { return null; }
    }

    private int[] parsePythonVersion(String verLine) {
        // "Python 3.11.7" / "Python 3.10.0a"
        if (verLine == null) return null;
        String s = verLine.toLowerCase(Locale.ROOT).replace("python", "").trim();
        String[] parts = s.split("\\.");
        if (parts.length < 2) return null;
        try {
            int maj = Integer.parseInt(parts[0].replaceAll("\\D", ""));
            int min = Integer.parseInt(parts[1].replaceAll("\\D", ""));
            return new int[]{maj, min};
        } catch (NumberFormatException e) { return null; }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
