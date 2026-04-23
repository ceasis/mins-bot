package com.minsbot.firstrun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One-click Piper TTS installer. Downloads the prebuilt Piper binary and any number
 * of voice packs ({@code .onnx} + {@code .onnx.json} pairs from the {@code rhasspy/piper-voices}
 * HuggingFace repo). All local, no Python, no pip — piper.exe is self-contained.
 *
 * <p>Install root: {@code %USERPROFILE%/mins_bot_data/piper/}. Voices live under
 * {@code piper/voices/}.</p>
 */
@Service
public class PiperInstallerService {

    private static final Logger log = LoggerFactory.getLogger(PiperInstallerService.class);

    private static final Path ROOT =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "piper").toAbsolutePath();
    private static final Path VOICES = ROOT.resolve("voices");

    /** Stable latest-release URLs for the prebuilt Piper binary. */
    private static final String WIN_ZIP   = "https://github.com/rhasspy/piper/releases/latest/download/piper_windows_amd64.zip";
    private static final String LINUX_GZ  = "https://github.com/rhasspy/piper/releases/latest/download/piper_linux_x86_64.tar.gz";
    private static final String MAC_GZ    = "https://github.com/rhasspy/piper/releases/latest/download/piper_macos_aarch64.tar.gz";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Path root()    { return ROOT; }
    public Path voices()  { return VOICES; }
    public Path binary()  { return ROOT.resolve(isWindows() ? "piper/piper.exe" : "piper/piper"); }

    public boolean isInstalled() {
        return Files.isRegularFile(binary());
    }

    /** {@code voices/<filename>.onnx} exists AND its sidecar {@code .onnx.json} exists. */
    public boolean hasVoice(String filename) {
        if (filename == null) return false;
        Path onnx = VOICES.resolve(filename);
        Path json = VOICES.resolve(filename + ".json");
        return Files.isRegularFile(onnx) && Files.isRegularFile(json);
    }

    /** Piper binary + any one installed voice. */
    public boolean isReady() {
        if (!isInstalled() || !Files.isDirectory(VOICES)) return false;
        try (var s = Files.list(VOICES)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".onnx"));
        } catch (IOException e) {
            return false;
        }
    }

    // ─── Binary install ──────────────────────────────────────────────

    /** Download + extract piper binary. Streams log/progress lines to {@code emit}. */
    public void installBinary(Consumer<String> emit) throws Exception {
        Files.createDirectories(ROOT);
        if (isInstalled()) {
            emit.accept("log|Piper binary already installed at " + binary());
            return;
        }
        String url = isWindows() ? WIN_ZIP : (isMac() ? MAC_GZ : LINUX_GZ);
        emit.accept("log|Downloading Piper binary (~25 MB)…");
        Path archive = ROOT.resolve("piper-archive" + (isWindows() ? ".zip" : ".tar.gz"));
        downloadTo(url, archive, emit);

        emit.accept("log|Extracting Piper…");
        if (isWindows()) {
            unzip(archive, ROOT);
        } else {
            // tar.gz: shell out to tar (both macOS and Linux ship it)
            Process p = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", ROOT.toString())
                    .redirectErrorStream(true).start();
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("tar extraction timed out");
            }
            if (p.exitValue() != 0) throw new IOException("tar exited " + p.exitValue());
        }
        try { Files.deleteIfExists(archive); } catch (IOException ignored) {}
        if (!isInstalled()) {
            throw new IOException("Piper binary not found after extraction at " + binary());
        }
        emit.accept("log|Piper installed at " + binary());
    }

    // ─── Voice install ──────────────────────────────────────────────

    /**
     * Downloads one voice pair into {@code voices/}. Stable HuggingFace URL:
     * {@code https://huggingface.co/rhasspy/piper-voices/resolve/main/<hfPath>/<filename>}.
     */
    public void installVoice(String hfPath, String filename, Consumer<String> emit) throws Exception {
        Files.createDirectories(VOICES);
        String baseUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/" + hfPath + "/";
        Path onnx = VOICES.resolve(filename);
        Path json = VOICES.resolve(filename + ".json");

        if (!Files.exists(onnx) || Files.size(onnx) < 1024L * 1024L) {
            emit.accept("log|Downloading voice model " + filename + "…");
            downloadTo(baseUrl + filename, onnx, emit);
        } else {
            emit.accept("log|Voice model already present: " + filename);
        }
        if (!Files.exists(json)) {
            emit.accept("log|Downloading voice config " + filename + ".json…");
            downloadTo(baseUrl + filename + ".json", json, emit);
        }
        emit.accept("log|Voice ready: " + filename);
    }

    // ─── helpers ═══════════════════════════════════════════════════

    private void downloadTo(String url, Path dest, Consumer<String> emit) throws Exception {
        Files.createDirectories(dest.getParent());
        long existing = Files.exists(dest) ? Files.size(dest) : 0L;
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(15))
                .header("User-Agent", "mins-bot-piper/1.0")
                .GET();
        if (existing > 0) rb.header("Range", "bytes=" + existing + "-");

        HttpResponse<InputStream> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = resp.statusCode();
        if (status != 200 && status != 206) {
            throw new IOException("HTTP " + status + " fetching " + url);
        }
        long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
        long total = (status == 206 && contentLength > 0) ? existing + contentLength : contentLength;

        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(dest,
                     existing > 0 ? StandardOpenOption.APPEND : StandardOpenOption.CREATE)) {
            byte[] buf = new byte[64 * 1024];
            long done = existing, lastEmit = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (done - lastEmit > 512 * 1024 || (total > 0 && done == total)) {
                    emit.accept("progress|" + done + "|" + (total > 0 ? total : -1));
                    lastEmit = done;
                }
            }
            emit.accept("progress|" + done + "|" + (total > 0 ? total : done));
        }
    }

    private static void unzip(Path zip, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir)) continue; // zip-slip
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
