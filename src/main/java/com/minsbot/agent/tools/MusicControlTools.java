package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.minsbot.GoogleIntegrationOAuthService;
import com.minsbot.SpotifyOAuthService;
import com.minsbot.agent.ChromeCdpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Music/Spotify control: play, pause, skip, previous, volume, search songs.
 * Uses Windows media keys (works with Spotify, Windows Media Player, YouTube Music, etc.)
 * and the Spotify Web API when an access token is configured.
 */
@Component
public class MusicControlTools {

    private static final Logger log = LoggerFactory.getLogger(MusicControlTools.class);

    private final ToolExecutionNotifier notifier;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired(required = false)
    private SpotifyOAuthService spotifyOAuth;

    @Autowired(required = false)
    private ChromeCdpService cdpService;

    @Autowired(required = false)
    private GoogleIntegrationOAuthService googleOAuth;

    /** Mode for music playback browser: "main" = user's existing Chrome via CDP, "isolated" = dedicated profile. */
    private volatile String musicBrowserMode = "main";

    /** Last CDP Page opened for music, so we can close it before opening a new one. */
    private final AtomicReference<Page> lastMusicPage = new AtomicReference<>();

    public MusicControlTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Media key controls (works with any music player)
    // ═════════════════════════════════════════════════════════════════════════

    @Tool(description = "Play or pause the current music track. Works with Spotify, YouTube Music, "
            + "Windows Media Player, or any media player that responds to media keys. "
            + "Use when the user says 'play music', 'pause', 'resume', 'play/pause'.")
    public String playPause() {
        notifier.notify("Toggling play/pause...");
        return pressMediaKey(0xB3 /* VK_MEDIA_PLAY_PAUSE */, "Play/Pause toggled.");
    }

    @Tool(description = "Skip to the next track. Works with any media player via media keys. "
            + "Use when the user says 'next song', 'skip', 'next track'.")
    public String nextTrack() {
        notifier.notify("Skipping to next track...");
        return pressMediaKey(0xB0 /* VK_MEDIA_NEXT_TRACK */, "Skipped to next track.");
    }

    @Tool(description = "Go back to the previous track. Works with any media player via media keys. "
            + "Use when the user says 'previous song', 'go back', 'previous track'.")
    public String previousTrack() {
        notifier.notify("Going to previous track...");
        return pressMediaKey(0xB1 /* VK_MEDIA_PREV_TRACK */, "Went to previous track.");
    }

    @Tool(description = "Stop music playback. Works with any media player via media keys. "
            + "Use when the user says 'stop music', 'stop playing'.")
    public String stopMusic() {
        notifier.notify("Stopping music...");
        return pressMediaKey(0xB2 /* VK_MEDIA_STOP */, "Music stopped.");
    }

    @Tool(description = "Increase the system volume. Use when the user says 'volume up', 'louder', 'turn it up'.")
    public String volumeUp(
            @ToolParam(description = "Number of volume steps to increase (1-10, default 3)") double steps) {
        int n = Math.max(1, Math.min(10, (int) steps));
        notifier.notify("Increasing volume (" + n + " steps)...");
        try {
            Robot robot = new Robot();
            for (int i = 0; i < n; i++) {
                robot.keyPress(0xAF /* VK_VOLUME_UP */);
                robot.keyRelease(0xAF /* VK_VOLUME_UP */);
                Thread.sleep(50);
            }
            return "Volume increased by " + n + " steps.";
        } catch (Exception e) {
            return "Failed to change volume: " + e.getMessage();
        }
    }

    @Tool(description = "Decrease the system volume. Use when the user says 'volume down', 'quieter', 'turn it down'.")
    public String volumeDown(
            @ToolParam(description = "Number of volume steps to decrease (1-10, default 3)") double steps) {
        int n = Math.max(1, Math.min(10, (int) steps));
        notifier.notify("Decreasing volume (" + n + " steps)...");
        try {
            Robot robot = new Robot();
            for (int i = 0; i < n; i++) {
                robot.keyPress(0xAE /* VK_VOLUME_DOWN */);
                robot.keyRelease(0xAE /* VK_VOLUME_DOWN */);
                Thread.sleep(50);
            }
            return "Volume decreased by " + n + " steps.";
        } catch (Exception e) {
            return "Failed to change volume: " + e.getMessage();
        }
    }

    @Tool(description = "Set the system master volume to an EXACT percentage (0-100). "
            + "This is the ONLY correct tool for exact-volume requests — do NOT use runPowerShell with Get-AudioDevice "
            + "(that module isn't installed on most PCs). "
            + "Use when the user says 'set volume to 50', 'audio to 42%', 'volume to 42', 'set audio to 80', "
            + "'max volume', 'volume to 100%', 'put volume at 30 percent', 'turn volume up to 100', 'volume to the max', "
            + "'volume 75', 'audio 60'. Accepts a number from 0 to 100. "
            + "Uses Windows Core Audio API via inline C# in PowerShell — no extra modules required; works on every Windows install.")
    public String setSystemVolume(
            @ToolParam(description = "Target volume percentage from 0 to 100 (e.g. 100 for max)") double percent) {
        int pct = Math.max(0, Math.min(100, (int) Math.round(percent)));
        notifier.notify("Setting system volume to " + pct + "%...");

        // Primary path: Windows Core Audio API via inline C# in PowerShell (no installs).
        if (setVolumeViaPowerShell(pct)) {
            return "System volume set to " + pct + "%.";
        }

        // Fallback: drive media keys via PowerShell WScript.Shell SendKeys.
        // Avoids Java Robot's key-code validation (which rejects VK_VOLUME_UP=0xAF on some JDKs).
        // Not as precise as the Core Audio API path — each press = ~2% on Windows.
        if (setVolumeViaSendKeys(pct)) {
            return "System volume set to approximately " + pct + "% (media-key fallback).";
        }
        return "Failed to set volume: both the Core Audio API and media-key fallback paths failed. "
                + "Check that PowerShell is available on PATH and that the app has permission to drive keys.";
    }

    /**
     * Fallback: use Win32 keybd_event P/Invoked via PowerShell Add-Type to drive real
     * VK_VOLUME_DOWN / VK_VOLUME_UP virtual keys (NOT SendKeys, which sends Unicode).
     * Each press ≈ 2%; we drop to 0 then step up to the target.
     */
    private boolean setVolumeViaSendKeys(int pct) {
        int targetSteps = Math.max(0, Math.min(50, pct / 2));
        java.nio.file.Path scriptFile = null;
        try {
            String psSource = String.join("\r\n",
                "$ErrorActionPreference='Stop'",
                "$src = @'",
                "using System;",
                "using System.Runtime.InteropServices;",
                "public class MinsKeybd {",
                "  [DllImport(\"user32.dll\")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);",
                "  public static void Press(byte vk) { keybd_event(vk, 0, 0, UIntPtr.Zero); keybd_event(vk, 0, 2, UIntPtr.Zero); }",
                "}",
                "'@",
                "if (-not ('MinsKeybd' -as [type])) { Add-Type -TypeDefinition $src -Language CSharp }",
                "for ($i = 0; $i -lt 52; $i++) { [MinsKeybd]::Press(0xAE); Start-Sleep -Milliseconds 8 }",
                "for ($i = 0; $i -lt " + targetSteps + "; $i++) { [MinsKeybd]::Press(0xAF); Start-Sleep -Milliseconds 8 }"
            );
            scriptFile = java.nio.file.Files.createTempFile("mins_volkey_", ".ps1");
            java.nio.file.Files.writeString(scriptFile, psSource, java.nio.charset.StandardCharsets.UTF_8);

            for (String exe : new String[]{"powershell", "pwsh"}) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            exe, "-NoProfile", "-ExecutionPolicy", "Bypass",
                            "-File", scriptFile.toAbsolutePath().toString());
                    java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
                    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    Process p = pb.start();
                    Thread drainer = new Thread(() -> {
                        try (var in = p.getErrorStream()) {
                            byte[] buf = new byte[1024];
                            int n;
                            while ((n = in.read(buf)) > 0) errBuf.write(buf, 0, n);
                        } catch (Exception ignored) {}
                    }, "ps-stderr-drain");
                    drainer.setDaemon(true);
                    drainer.start();
                    boolean done = p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS);
                    if (done && p.exitValue() == 0) return true;
                    String err = errBuf.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (done) {
                        log.warn("[Music] keybd_event {} exit={}: {}", exe, p.exitValue(),
                                err.isEmpty() ? "(no stderr)" : err);
                    } else {
                        p.destroyForcibly();
                    }
                } catch (java.io.IOException notInstalled) {
                    // try next
                } catch (Exception e) {
                    log.warn("[Music] keybd_event {} failed: {}", exe, e.getMessage());
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("[Music] setVolumeViaSendKeys outer failure: {}", e.getMessage());
            return false;
        } finally {
            if (scriptFile != null) {
                try { java.nio.file.Files.deleteIfExists(scriptFile); } catch (Exception ignored) {}
            }
        }
    }

    @Tool(description = "Mute or unmute the system audio. Use when the user says 'mute', 'unmute', 'toggle mute'.")
    public String toggleMute() {
        notifier.notify("Toggling mute...");
        return pressMediaKey(0xAD, "Mute toggled."); // VK_VOLUME_MUTE = 0xAD
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Spotify-specific controls (requires Spotify running)
    // ═════════════════════════════════════════════════════════════════════════

    @Tool(description = "Open Spotify and search for a song, artist, album, or playlist. "
            + "Opens the Spotify search URI. Use when the user says 'play [song]', "
            + "'search for [artist] on Spotify', 'find [album]'.")
    public String spotifySearch(
            @ToolParam(description = "Search query: song name, artist, album, or playlist") String query) {
        notifier.notify("Searching Spotify for: " + query);
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            // Try Spotify URI protocol first (opens in desktop app)
            String uri = "spotify:search:" + encoded;
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "start", "", uri);
            pb.start();
            Thread.sleep(500);
            return "Opened Spotify search for: " + query;
        } catch (Exception e) {
            return "Failed to open Spotify: " + e.getMessage();
        }
    }

    @Tool(description = "Play a song, artist, album, or playlist on Spotify. USE THIS whenever the user "
            + "says 'play [anything]' that sounds like music (song title, artist name, mood, genre) — "
            + "e.g. 'play low low low', 'play Drake', 'play jazz', 'play liked songs'. "
            + "If Spotify is connected in Integrations, uses the Spotify Web API to search and play "
            + "the top result directly (requires Premium + an active device). Otherwise falls back "
            + "to launching the desktop app and sending keystrokes. "
            + "Prefer this over opening YouTube or the browser for any music playback request.")
    public String spotifyPlay(
            @ToolParam(description = "What to play — song title, artist, album, genre, mood, or a spotify: URI") String query) {
        notifier.notify("Playing on Spotify: " + query);

        // 1. Preferred path: Spotify Web API (requires OAuth + Premium)
        String apiResult = tryPlayViaApi(query);
        if (apiResult != null) return apiResult;

        // 2. Spotify not connected → YouTube fallback + Connect button in chat
        if (spotifyOAuth == null || spotifyOAuth.getValidAccessToken() == null) {
            String ytResult = playOnYouTube(query);
            StringBuilder sb = new StringBuilder();
            sb.append("[action:connect-spotify]\n");
            sb.append("Spotify isn't connected yet — I played \"").append(query)
              .append("\" on YouTube instead.\n\n").append(ytResult);
            // One-time tip: if they have Premium but see ads, offer the sign-in flow
            if (!isMusicProfileSignedIn()) {
                sb.append("\n\nSeeing ads? Say **\"sign in to YouTube\"** — I'll open the music "
                        + "profile for a one-time Google login so your Premium subscription kicks in.");
            }
            return sb.toString();
        }

        // 3. Connected but API path failed (no device, etc) → desktop URI + keystrokes
        try {
            if (query.startsWith("spotify:")) {
                new ProcessBuilder("cmd", "/c", "start", "", query).start();
                Thread.sleep(1500);
                sendPlay();
                return "Opening Spotify URI: " + query;
            }
            if (!isSpotifyRunning()) {
                new ProcessBuilder("cmd", "/c", "start", "", "spotify:").start();
                Thread.sleep(3500);
            }
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            new ProcessBuilder("cmd", "/c", "start", "", "spotify:search:" + encoded).start();
            Thread.sleep(1800);
            focusSpotifyWindow();
            Thread.sleep(300);

            Robot robot = new Robot();
            robot.setAutoDelay(60);
            for (int i = 0; i < 4; i++) {
                robot.keyPress(KeyEvent.VK_TAB);
                robot.keyRelease(KeyEvent.VK_TAB);
            }
            robot.keyPress(KeyEvent.VK_ENTER);
            robot.keyRelease(KeyEvent.VK_ENTER);
            Thread.sleep(400);
            robot.keyPress(0xB3);
            robot.keyRelease(0xB3);

            return "Opened Spotify and searched for \"" + query + "\". Pick a result if it didn't auto-play.";
        } catch (Exception e) {
            return "Failed to play on Spotify: " + e.getMessage();
        }
    }

    @Tool(description = "Choose which browser the bot uses to play background YouTube music. "
            + "Modes: 'main' (default) = your already-logged-in Chrome via CDP (no ads with Premium); "
            + "'default-profile' = launch Chrome against your default user profile, it delegates to your "
            + "running Chrome and opens an app window there (inherits your Premium login, no CDP needed); "
            + "'isolated' = separate Chrome profile in ~/mins_bot_data/mins_music_profile (ads unless signed into it). "
            + "Use when the user says 'use my main browser', 'use my default chrome', 'play in my regular chrome'.")
    public String setMusicBrowserMode(
            @ToolParam(description = "'main' | 'default-profile' | 'isolated'") String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase();
        if (!m.equals("main") && !m.equals("isolated") && !m.equals("default-profile")) {
            return "Mode must be 'main', 'default-profile', or 'isolated'. Current: " + musicBrowserMode;
        }
        musicBrowserMode = m;
        return "Music browser mode set to '" + m + "'. " + switch (m) {
            case "main" -> "Future 'play X' will open tabs via CDP in your real Chrome.";
            case "default-profile" -> "Future 'play X' will open app windows in your running Chrome (using your default profile — Premium login applies).";
            default -> "Future 'play X' will use the isolated profile (separate window).";
        };
    }

    @Tool(description = "Get the current music browser mode ('main' or 'isolated').")
    public String getMusicBrowserMode() {
        return "Music browser mode: " + musicBrowserMode;
    }

    /**
     * YouTube fallback: search, extract the first video ID, and open the watch
     * page. Prefers the user's real Chrome via CDP (already logged into Premium),
     * falls back to the isolated profile when CDP isn't available.
     */
    private String playOnYouTube(String query) {
        try {
            String targetUrl = null;

            // 1. Preferred: YouTube Data API (uses connected OAuth). More reliable than scraping.
            String apiVideoId = searchYouTubeViaApi(query);
            if (apiVideoId != null) {
                targetUrl = "https://www.youtube.com/watch?v=" + apiVideoId + "&autoplay=1";
                log.info("[Music] Found video via YouTube API: {}", apiVideoId);
            }

            // 2. Fallback: HTML scrape of YouTube search results
            if (targetUrl == null) {
                String searchUrl = "https://www.youtube.com/results?search_query="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(searchUrl))
                        .header("User-Agent", "Mozilla/5.0 (compatible; MinsBot)")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(resp.body());
                    targetUrl = m.find()
                            ? "https://www.youtube.com/watch?v=" + m.group(1) + "&autoplay=1"
                            : searchUrl;
                } else {
                    targetUrl = searchUrl;
                }
            }

            // Prefer CDP → user's real Chrome. Reuse the same tab across songs:
            // navigate the existing music tab to the new URL instead of opening/closing tabs.
            if ("main".equals(musicBrowserMode) && cdpService != null) {
                try {
                    Page existing = lastMusicPage.get();
                    if (existing != null && !existing.isClosed()) {
                        existing.navigate(targetUrl);
                        try { existing.bringToFront(); } catch (Exception ignored) {}
                        MUSIC_ACTIVE.set(true);
                        return "▶ Now playing (same tab): " + targetUrl;
                    }
                    Page page = cdpService.openPage(targetUrl);
                    lastMusicPage.set(page);
                    MUSIC_ACTIVE.set(true);
                    return "▶ Playing on YouTube (new tab in your Chrome — Premium active if signed in): " + targetUrl;
                } catch (Exception e) {
                    log.warn("[Music] CDP play failed, trying default-profile mode: {}", e.getMessage());
                }
            }

            // Alternative: hand the URL to user's running Chrome via delegation (uses default profile).
            // No CDP needed — Chrome's IPC delegates the command to the already-running instance.
            if ("default-profile".equals(musicBrowserMode) || "main".equals(musicBrowserMode)) {
                if (openInUserDefaultChrome(targetUrl)) {
                    MUSIC_ACTIVE.set(true);
                    return "▶ Playing on YouTube (app window in your default Chrome — Premium login applies): " + targetUrl;
                }
            }

            openInDedicatedMusicBrowser(targetUrl);
            MUSIC_ACTIVE.set(true);
            return "▶ Playing on YouTube (background, isolated profile): " + targetUrl;
        } catch (Exception e) {
            log.warn("[Music] YouTube fallback failed: {}", e.getMessage());
            return "Tried YouTube fallback but it failed: " + e.getMessage();
        }
    }

    /**
     * Use the YouTube Data API (via connected OAuth) to search for a video.
     * Returns the top result's video ID, or null if OAuth isn't connected or the call fails.
     */
    private String searchYouTubeViaApi(String query) {
        if (googleOAuth == null) return null;
        String token = googleOAuth.getValidAccessToken("youtube");
        if (token == null) return null;  // Not connected

        try {
            String url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=1&q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[Music] YouTube API search HTTP {}: {}", resp.statusCode(),
                        resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
                return null;
            }
            JsonNode items = mapper.readTree(resp.body()).path("items");
            if (!items.isArray() || items.isEmpty()) return null;
            return items.get(0).path("id").path("videoId").asText(null);
        } catch (Exception e) {
            log.warn("[Music] YouTube API search failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Launch Chrome with `--app=URL` against the user's DEFAULT profile (no --user-data-dir).
     * If Chrome is already running, Windows's process delegation hands the --app command to
     * the existing instance, which opens a new app-mode window inside it — inheriting all
     * cookies, including the Premium login. Returns true on success, false if Chrome not found.
     */
    private boolean openInUserDefaultChrome(String url) {
        String exe = findChromeLikeExecutable();
        if (exe == null) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    exe,
                    "--app=" + url,
                    "--window-size=480,320",
                    "--window-position=0,0",
                    "--autoplay-policy=no-user-gesture-required",
                    "--disable-background-timer-throttling",
                    "--disable-renderer-backgrounding",
                    "--disable-backgrounding-occluded-windows",
                    "--disable-features=CalculateNativeWinOcclusion"
            );
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            // This PID may be short-lived (delegation forks) — try minimizing anyway.
            scheduleMinimize(p.pid());
            log.info("[Music] Delegated music URL to user's running Chrome: {}", url);
            return true;
        } catch (Exception e) {
            log.warn("[Music] openInUserDefaultChrome failed: {}", e.getMessage());
            return false;
        }
    }

    /** Profile directory reserved for the music browser (separate from user's normal Chrome). */
    private static final String MUSIC_PROFILE_MARKER = "mins_music_profile";

    /** Debug port for the dedicated music browser — used to navigate the existing tab on subsequent plays. */
    private static final int MUSIC_DEBUG_PORT = 9223;

    /** The Chrome process we launched last; kept so we can kill its entire tree next time. */
    private static volatile Process musicBrowserProc;
    private static final Object musicBrowserLock = new Object();

    /**
     * True while background music is playing. Other components (notably TtsTools) check
     * this to suppress speech — otherwise Bluetooth headphones switch from A2DP (hi-fi)
     * to HSP/HFP (comms) during TTS and music sounds terrible until the codec flips back.
     */
    public static final java.util.concurrent.atomic.AtomicBoolean MUSIC_ACTIVE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Kill any existing music browser, then launch a new one in a dedicated profile,
     * minimized so it plays in the background.
     */
    private void openInDedicatedMusicBrowser(String url) {
        synchronized (musicBrowserLock) {
            // 1. If the music browser is already alive, navigate the existing tab via CDP.
            //    This keeps the same window — no flicker, no relaunch.
            if (musicBrowserProc != null && musicBrowserProc.isAlive()) {
                if (navigateExistingMusicTab(url)) {
                    log.info("[Music] Reused existing music browser tab → {}", url);
                    MUSIC_ACTIVE.set(true);
                    return;
                }
                log.info("[Music] Existing music browser unreachable on debug port — relaunching");
            }

            closeExistingMusicBrowser();
            try { Thread.sleep(400); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

            String userHome = System.getProperty("user.home");
            String userDataDir = userHome + java.io.File.separator
                    + "mins_bot_data" + java.io.File.separator + MUSIC_PROFILE_MARKER;
            new java.io.File(userDataDir).mkdirs();

            String exe = findChromeLikeExecutable();
            if (exe == null) {
                try { java.awt.Desktop.getDesktop().browse(URI.create(url)); } catch (Exception ignored) {}
                return;
            }

            try {
                // Launch Chrome DIRECTLY (not via cmd /c start) so we get a real Process handle.
                // --remote-debugging-port enables CDP so subsequent plays can navigate this same tab.
                ProcessBuilder pb = new ProcessBuilder(
                        exe,
                        "--app=" + url,
                        "--user-data-dir=" + userDataDir,
                        "--remote-debugging-port=" + MUSIC_DEBUG_PORT,
                        "--remote-allow-origins=*",
                        "--window-size=480,320",
                        "--window-position=0,0",
                        "--no-first-run",
                        "--no-default-browser-check",
                        "--autoplay-policy=no-user-gesture-required",
                        "--disable-background-timer-throttling",
                        "--disable-renderer-backgrounding",
                        "--disable-backgrounding-occluded-windows",
                        "--disable-features=CalculateNativeWinOcclusion"
                );
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                musicBrowserProc = pb.start();
                long pid = musicBrowserProc.pid();
                log.info("[Music] Launched dedicated music browser pid={} (debug port {}) for: {}",
                        pid, MUSIC_DEBUG_PORT, url);
                scheduleMinimize(pid);
            } catch (Exception e) {
                log.warn("[Music] Failed to launch dedicated music browser: {}", e.getMessage());
            }
        }
    }

    /**
     * Navigate the existing music browser's app tab to a new URL via Chrome DevTools Protocol.
     * Returns true if the navigation succeeded; false if the debug port isn't reachable
     * or no suitable tab was found.
     */
    private boolean navigateExistingMusicTab(String url) {
        try {
            // 1. List pages on the music browser's debug port
            HttpRequest listReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + MUSIC_DEBUG_PORT + "/json"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            HttpResponse<String> listResp = http.send(listReq, HttpResponse.BodyHandlers.ofString());
            if (listResp.statusCode() != 200) return false;

            JsonNode pages = mapper.readTree(listResp.body());
            if (!pages.isArray() || pages.isEmpty()) return false;

            // 2. Find the music tab (any "page" type — the --app window is the only one)
            String wsUrl = null;
            for (JsonNode p : pages) {
                if ("page".equals(p.path("type").asText())) {
                    wsUrl = p.path("webSocketDebuggerUrl").asText(null);
                    if (wsUrl != null) break;
                }
            }
            if (wsUrl == null) return false;

            // 3. Open WebSocket → send Page.navigate → close
            return sendCdpNavigate(wsUrl, url);
        } catch (Exception e) {
            log.debug("[Music] navigateExistingMusicTab failed: {}", e.getMessage());
            return false;
        }
    }

    /** Connect to the page's CDP WebSocket and send a single Page.navigate command. */
    private boolean sendCdpNavigate(String wsUrl, String navigateTo) {
        java.util.concurrent.CompletableFuture<Boolean> done = new java.util.concurrent.CompletableFuture<>();
        try {
            String payload = "{\"id\":1,\"method\":\"Page.navigate\",\"params\":{\"url\":\""
                    + navigateTo.replace("\"", "\\\"") + "\"}}";
            java.net.http.WebSocket ws = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .buildAsync(URI.create(wsUrl), new java.net.http.WebSocket.Listener() {
                        @Override
                        public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket webSocket,
                                                                              CharSequence data, boolean last) {
                            // Any response means the navigate command was accepted
                            done.complete(true);
                            webSocket.request(1);
                            return null;
                        }
                        @Override
                        public void onError(java.net.http.WebSocket webSocket, Throwable error) {
                            done.complete(false);
                        }
                    })
                    .get(2, TimeUnit.SECONDS);
            ws.sendText(payload, true);
            boolean ok = done.get(2, TimeUnit.SECONDS);
            try { ws.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "ok"); } catch (Exception ignored) {}
            return ok;
        } catch (Exception e) {
            log.debug("[Music] sendCdpNavigate failed: {}", e.getMessage());
            return false;
        }
    }

    /** Kill the previously tracked music browser AND any leftover with our profile marker. */
    private void closeExistingMusicBrowser() {
        // 1. Kill the specific Process we launched — fastest and most reliable
        Process prev = musicBrowserProc;
        if (prev != null && prev.isAlive()) {
            long pid = prev.pid();
            try {
                // taskkill /T kills the entire process tree (Chrome + all renderer children)
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start().waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            prev.destroyForcibly();
            musicBrowserProc = null;
        }

        // 2. Safety net: nuke anything with our profile-dir in its commandline
        //    (covers processes launched before this JVM started).
        try {
            String ps = "Get-CimInstance Win32_Process "
                    + "| Where-Object { $_.CommandLine -like '*" + MUSIC_PROFILE_MARKER + "*' } "
                    + "| ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }";
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", ps);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            proc.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("[Music] closeExistingMusicBrowser powershell: {}", e.getMessage());
        }
    }

    /**
     * After a short delay (window needs time to appear), minimize the launched
     * Chrome window via PowerShell ShowWindowAsync(SW_MINIMIZE = 6).
     */
    private void scheduleMinimize(long pid) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(700);
                String ps =
                    "$s=@'\n" +
                    "using System;using System.Runtime.InteropServices;\n" +
                    "public class W{[DllImport(\"user32.dll\")]public static extern bool ShowWindowAsync(IntPtr h,int n);}\n" +
                    "'@;" +
                    "Add-Type -TypeDefinition $s -Language CSharp;" +
                    "$p=Get-Process -Id " + pid + " -ErrorAction SilentlyContinue;" +
                    "for($i=0;$i -lt 10;$i++){" +
                    "  if($p -and $p.MainWindowHandle -ne 0){[W]::ShowWindowAsync($p.MainWindowHandle,6)|Out-Null;break}" +
                    "  Start-Sleep -Milliseconds 200;" +
                    "  $p=Get-Process -Id " + pid + " -ErrorAction SilentlyContinue" +
                    "}";
                new ProcessBuilder("powershell", "-NoProfile", "-Command", ps)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (Exception e) {
                log.debug("[Music] minimize failed: {}", e.getMessage());
            }
        }, "mins-music-minimize");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Rough heuristic for "is the music profile signed into Google?" — checks whether
     * a "LOGIN_INFO" cookie exists in the profile's Cookies DB file (created once
     * the user signs into any Google service). Avoids parsing the SQLite file;
     * a simple byte-scan is enough because Chrome stores cookie names as plain
     * UTF-8 inside the DB.
     */
    private boolean isMusicProfileSignedIn() {
        try {
            String userHome = System.getProperty("user.home");
            java.nio.file.Path cookiesDb = java.nio.file.Path.of(userHome,
                    "mins_bot_data", MUSIC_PROFILE_MARKER, "Default", "Network", "Cookies");
            if (!java.nio.file.Files.isRegularFile(cookiesDb)) return false;
            // Any Google-signed-in profile contains this cookie name in plaintext inside the DB
            byte[] bytes = java.nio.file.Files.readAllBytes(cookiesDb);
            String needle = "LOGIN_INFO";
            byte[] n = needle.getBytes(StandardCharsets.UTF_8);
            outer:
            for (int i = 0; i <= bytes.length - n.length; i++) {
                for (int j = 0; j < n.length; j++) {
                    if (bytes[i + j] != n[j]) continue outer;
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Locate a Chromium-based browser on the machine. Returns null if none found. */
    private String findChromeLikeExecutable() {
        String pf  = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        String lad = System.getenv("LOCALAPPDATA");
        String[] candidates = {
                pf  + "\\Google\\Chrome\\Application\\chrome.exe",
                pf86+ "\\Google\\Chrome\\Application\\chrome.exe",
                lad + "\\Google\\Chrome\\Application\\chrome.exe",
                pf  + "\\Microsoft\\Edge\\Application\\msedge.exe",
                pf86+ "\\Microsoft\\Edge\\Application\\msedge.exe",
                pf  + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                pf86+ "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                lad + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
        };
        for (String p : candidates) {
            if (p == null) continue;
            java.io.File f = new java.io.File(p);
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    @Tool(description = "Stop any background YouTube music the bot started earlier. "
            + "Use when the user says 'stop the music', 'close youtube', 'stop the song', 'silence'.")
    public String stopMusicBrowser() {
        notifier.notify("Stopping background music...");
        Page prev = lastMusicPage.getAndSet(null);
        if (prev != null && !prev.isClosed()) {
            try { prev.close(); } catch (Exception ignored) {}
        }
        closeExistingMusicBrowser();
        MUSIC_ACTIVE.set(false);
        return "Closed the background music.";
    }

    @Tool(description = "Open a full browser window against the music profile so the user can sign in "
            + "to YouTube (or Google). After signing in once, future 'play X' commands reuse the same "
            + "profile — YouTube Premium takes effect and ads go away. "
            + "Use when the user says 'sign in to youtube', 'log in to youtube', 'remove ads', "
            + "'I have youtube premium but I see ads', or similar.")
    public String signInToMusicBrowser() {
        notifier.notify("Opening YouTube sign-in window...");
        // Kill any running music browser so the profile isn't locked
        closeExistingMusicBrowser();
        try { Thread.sleep(400); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        String userHome = System.getProperty("user.home");
        String userDataDir = userHome + java.io.File.separator
                + "mins_bot_data" + java.io.File.separator + MUSIC_PROFILE_MARKER;
        new java.io.File(userDataDir).mkdirs();

        String exe = findChromeLikeExecutable();
        if (exe == null) {
            return "Couldn't find Chrome/Edge. Please install one, then try again.";
        }

        try {
            // Normal Chrome window (with URL bar, tabs) so the user can sign in.
            // NOT using --app= here — full UI is needed for the Google sign-in flow.
            ProcessBuilder pb = new ProcessBuilder(
                    exe,
                    "--user-data-dir=" + userDataDir,
                    "--no-first-run",
                    "--no-default-browser-check",
                    "https://www.youtube.com/"
            );
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            return "Opened YouTube in the music browser. Sign in with your Google account "
                    + "(the one with YouTube Premium). Close the window when done — the next time "
                    + "you ask me to play something, Premium will be active and there won't be ads.";
        } catch (Exception e) {
            return "Failed to open sign-in window: " + e.getMessage();
        }
    }

    /**
     * Try to play via the Spotify Web API. Returns a user-facing message on success/known failure,
     * or null if the API path isn't available (not connected) so we fall back to keystrokes.
     */
    private String tryPlayViaApi(String query) {
        if (spotifyOAuth == null) return null;
        String token = spotifyOAuth.getValidAccessToken();
        if (token == null) return null;

        try {
            String contextUri = null;
            String trackUri = null;
            String label = query;

            if (query.startsWith("spotify:")) {
                if (query.startsWith("spotify:track:")) trackUri = query;
                else contextUri = query;
            } else {
                // Search for a track matching the query, take top hit
                String searchUrl = "https://api.spotify.com/v1/search?type=track&limit=1&q="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8);
                HttpRequest searchReq = HttpRequest.newBuilder()
                        .uri(URI.create(searchUrl))
                        .header("Authorization", "Bearer " + token)
                        .GET().build();
                HttpResponse<String> searchResp = http.send(searchReq, HttpResponse.BodyHandlers.ofString());
                if (searchResp.statusCode() != 200) {
                    log.warn("[Spotify] search HTTP {}: {}", searchResp.statusCode(), searchResp.body());
                    return null;
                }
                JsonNode tracks = mapper.readTree(searchResp.body()).path("tracks").path("items");
                if (!tracks.isArray() || tracks.isEmpty()) {
                    return "Couldn't find anything on Spotify for \"" + query + "\".";
                }
                JsonNode top = tracks.get(0);
                trackUri = top.path("uri").asText(null);
                String name = top.path("name").asText("");
                String artist = top.path("artists").isArray() && !top.path("artists").isEmpty()
                        ? top.path("artists").get(0).path("name").asText("") : "";
                label = name + (artist.isBlank() ? "" : " — " + artist);
            }

            // Build the play request body
            String body;
            if (trackUri != null) {
                body = "{\"uris\":[\"" + trackUri + "\"]}";
            } else if (contextUri != null) {
                body = "{\"context_uri\":\"" + contextUri + "\"}";
            } else {
                return null;
            }

            HttpRequest playReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.spotify.com/v1/me/player/play"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> playResp = http.send(playReq, HttpResponse.BodyHandlers.ofString());

            if (playResp.statusCode() == 204 || playResp.statusCode() == 202) {
                return "▶ Now playing on Spotify: " + label;
            }
            if (playResp.statusCode() == 404) {
                // No active device — try to transfer playback to desktop app after starting it
                if (!isSpotifyRunning()) {
                    new ProcessBuilder("cmd", "/c", "start", "", "spotify:").start();
                    Thread.sleep(3500);
                }
                if (activateFirstDevice(token)) {
                    Thread.sleep(500);
                    HttpResponse<String> retry = http.send(playReq, HttpResponse.BodyHandlers.ofString());
                    if (retry.statusCode() == 204 || retry.statusCode() == 202) {
                        return "▶ Now playing on Spotify: " + label;
                    }
                    log.warn("[Spotify] play retry HTTP {}: {}", retry.statusCode(), retry.body());
                }
                return "Spotify says no active device. Open Spotify desktop or phone app and try again.";
            }
            if (playResp.statusCode() == 403) {
                return "Spotify rejected playback (403). This usually means the account isn't Premium — "
                        + "the Web API requires Premium for play/pause control.";
            }
            log.warn("[Spotify] play HTTP {}: {}", playResp.statusCode(), playResp.body());
            return null;  // unknown failure → let fallback try
        } catch (Exception e) {
            log.warn("[Spotify] API play failed: {}", e.getMessage());
            return null;
        }
    }

    /** Find the first available Spotify device and transfer playback to it. */
    private boolean activateFirstDevice(String token) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.spotify.com/v1/me/player/devices"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return false;
            JsonNode devices = mapper.readTree(resp.body()).path("devices");
            if (!devices.isArray() || devices.isEmpty()) return false;
            String deviceId = devices.get(0).path("id").asText(null);
            if (deviceId == null) return false;

            HttpRequest transfer = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.spotify.com/v1/me/player"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"device_ids\":[\"" + deviceId + "\"],\"play\":false}"))
                    .build();
            HttpResponse<String> tResp = http.send(transfer, HttpResponse.BodyHandlers.ofString());
            return tResp.statusCode() == 204 || tResp.statusCode() == 202;
        } catch (Exception e) {
            return false;
        }
    }

    @Tool(description = "Play the user's Liked Songs on Spotify. Use when the user says 'play music', "
            + "'play something', 'play my liked songs', 'play my music' with no specific song.")
    public String spotifyPlayLiked() {
        notifier.notify("Playing your Liked Songs on Spotify...");
        try {
            if (!isSpotifyRunning()) {
                new ProcessBuilder("cmd", "/c", "start", "", "spotify:").start();
                Thread.sleep(3500);
            }
            // spotify:collection:tracks opens Liked Songs in the desktop app
            new ProcessBuilder("cmd", "/c", "start", "", "spotify:collection:tracks").start();
            Thread.sleep(1500);
            focusSpotifyWindow();
            Thread.sleep(300);
            Robot robot = new Robot();
            robot.setAutoDelay(60);
            robot.keyPress(0xB3 /* play */);
            robot.keyRelease(0xB3);
            return "Playing your Liked Songs on Spotify.";
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Get info about what's currently playing on Spotify using its window title. "
            + "Use when the user asks 'what song is playing?', 'what's this song?', 'current track'.")
    public String getCurrentTrack() {
        notifier.notify("Checking current track...");
        try {
            // Read Spotify window title (format: "Artist - Song Title - Spotify")
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Get-Process Spotify -ErrorAction SilentlyContinue | " +
                    "Where-Object { $_.MainWindowTitle -ne '' } | " +
                    "Select-Object -ExpandProperty MainWindowTitle");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor(5, TimeUnit.SECONDS);

            if (output.isBlank() || output.equalsIgnoreCase("Spotify") || output.contains("not found")) {
                // Try other media players
                return checkOtherPlayers();
            }

            // Spotify title format: "Artist - Song Title"
            if (output.contains(" - ") && !output.equals("Spotify Free") && !output.equals("Spotify Premium")) {
                return "Now playing on Spotify: " + output;
            }
            return "Spotify is open but nothing is currently playing (or playback is paused).";
        } catch (Exception e) {
            return "Could not detect current track: " + e.getMessage();
        }
    }

    @Tool(description = "Check if Spotify is running. If not, launch it. "
            + "Use before attempting Spotify-specific actions.")
    public String ensureSpotifyRunning() {
        notifier.notify("Checking Spotify...");
        try {
            ProcessBuilder check = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-Process Spotify -ErrorAction SilentlyContinue).Count");
            check.redirectErrorStream(true);
            Process proc = check.start();
            String count = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor(5, TimeUnit.SECONDS);

            if (count.equals("0") || count.isBlank()) {
                // Launch Spotify
                new ProcessBuilder("cmd", "/c", "start", "", "spotify:").start();
                Thread.sleep(3000); // Give it time to launch
                return "Spotify was not running. Launched it — give it a moment to start.";
            }
            return "Spotify is already running.";
        } catch (Exception e) {
            return "Failed to check/launch Spotify: " + e.getMessage();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Sets the Windows master volume to an exact percentage using the Core Audio API
     * (IAudioEndpointVolume) exposed via inline C# in PowerShell. Requires no extra
     * modules — pure .NET + COM, ships with every Windows install.
     * Returns true on success, false on any failure.
     */
    /**
     * Set Windows master volume via Core Audio API (MMDevice + IAudioEndpointVolume).
     * Script is written to a temp .ps1 file so PowerShell's here-string rules aren't mangled
     * by command-line quoting. Works on PowerShell 5.1 (ships with Windows) and 7+.
     * Stderr is logged at WARN so failures are visible without needing debug level.
     */
    private boolean setVolumeViaPowerShell(int pct) {
        float scalar = Math.max(0f, Math.min(1f, pct / 100f));
        java.nio.file.Path scriptFile = null;
        try {
            String csSource = String.join("\n",
                "using System;",
                "using System.Runtime.InteropServices;",
                "[Guid(\"5CDF2C82-841E-4546-9722-0CF74078229A\"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
                "public interface IMinsAudioEndpointVolume {",
                "  int _f(); int _g(); int _h(); int _i();",
                "  [PreserveSig] int SetMasterVolumeLevelScalar(float level, Guid ctx);",
                "  int _j();",
                "  [PreserveSig] int GetMasterVolumeLevelScalar(out float level);",
                "}",
                "[Guid(\"A95664D2-9614-4F35-A746-DE8DB63617E6\"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
                "public interface IMinsMMDeviceEnumerator {",
                "  int _f();",
                "  [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMinsMMDevice endpoint);",
                "}",
                "[Guid(\"D666063F-1587-4E43-81F1-B948E807363F\"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
                "public interface IMinsMMDevice {",
                "  [PreserveSig] int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);",
                "}",
                "[ComImport, Guid(\"BCDE0395-E52F-467C-8E3D-C4579291692E\")] public class MinsMMDeviceEnumerator { }",
                "public class MinsAudio {",
                "  public static void Set(float level) {",
                "    var enumerator = (IMinsMMDeviceEnumerator)(new MinsMMDeviceEnumerator());",
                "    IMinsMMDevice device = null;",
                "    Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out device));",
                "    Guid iid = typeof(IMinsAudioEndpointVolume).GUID; object epv;",
                "    Marshal.ThrowExceptionForHR(device.Activate(ref iid, 0x17, IntPtr.Zero, out epv));",
                "    Marshal.ThrowExceptionForHR(((IMinsAudioEndpointVolume)epv).SetMasterVolumeLevelScalar(level, Guid.Empty));",
                "  }",
                "}"
            );
            String psSource = String.join("\r\n",
                "$ErrorActionPreference='Stop'",
                "$src = @'",
                csSource,
                "'@",
                "if (-not ('MinsAudio' -as [type])) {",
                "  Add-Type -TypeDefinition $src -Language CSharp",
                "}",
                "[MinsAudio]::Set(" + scalar + ")"
            );

            scriptFile = java.nio.file.Files.createTempFile("mins_setvol_", ".ps1");
            java.nio.file.Files.writeString(scriptFile, psSource, java.nio.charset.StandardCharsets.UTF_8);

            // Try powershell (5.1) first, then pwsh (7+)
            for (String exe : new String[]{"powershell", "pwsh"}) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            exe, "-NoProfile", "-ExecutionPolicy", "Bypass",
                            "-File", scriptFile.toAbsolutePath().toString());
                    java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
                    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    Process p = pb.start();
                    Thread drainer = new Thread(() -> {
                        try (var in = p.getErrorStream()) {
                            byte[] buf = new byte[1024];
                            int n;
                            while ((n = in.read(buf)) > 0) errBuf.write(buf, 0, n);
                        } catch (Exception ignored) {}
                    }, "ps-stderr-drain");
                    drainer.setDaemon(true);
                    drainer.start();
                    boolean done = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (done && p.exitValue() == 0) {
                        return true;
                    }
                    String err = errBuf.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (done) {
                        log.warn("[Music] {} exit={}: {}", exe, p.exitValue(),
                                err.isEmpty() ? "(no stderr)" : err);
                    } else {
                        p.destroyForcibly();
                        log.warn("[Music] {} timed out after 10s", exe);
                    }
                } catch (java.io.IOException notInstalled) {
                    log.debug("[Music] {} not on PATH, trying next", exe);
                } catch (Exception e) {
                    log.warn("[Music] {} launch failed: {}", exe, e.getMessage());
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("[Music] setVolumeViaPowerShell outer failure: {}", e.getMessage());
            return false;
        } finally {
            if (scriptFile != null) {
                try { java.nio.file.Files.deleteIfExists(scriptFile); } catch (Exception ignored) {}
            }
        }
    }

    private String pressMediaKey(int keyCode, String successMessage) {
        try {
            Robot robot = new Robot();
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
            return successMessage;
        } catch (Exception e) {
            return "Failed to send media key: " + e.getMessage();
        }
    }

    /** Send the global Play/Pause media key (0xB3 = VK_MEDIA_PLAY_PAUSE). */
    private void sendPlay() {
        try {
            Robot robot = new Robot();
            robot.keyPress(0xB3);
            robot.keyRelease(0xB3);
        } catch (Exception ignored) {}
    }

    /** Check whether the Spotify desktop app is currently running. */
    private boolean isSpotifyRunning() {
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-Process Spotify -ErrorAction SilentlyContinue).Count");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String count = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor(4, TimeUnit.SECONDS);
            return !count.isBlank() && !count.equals("0");
        } catch (Exception e) {
            return false;
        }
    }

    /** Bring the Spotify desktop window to the foreground so keyboard input lands there. */
    private void focusSpotifyWindow() {
        try {
            // PowerShell + Win32 SetForegroundWindow via reflection is heavy;
            // the simplest reliable method on Windows is to re-invoke the spotify: URI
            // (clicking it refocuses the already-running app) or use AppActivate.
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "$w=New-Object -ComObject wscript.shell; $null=$w.AppActivate('Spotify')");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private String checkOtherPlayers() {
        try {
            // Check common media player window titles
            String[] players = {"foobar2000", "AIMP", "Winamp", "VLC", "Windows Media Player",
                                "YouTube Music", "Amazon Music", "Apple Music"};
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Get-Process | Where-Object { $_.MainWindowTitle -ne '' } | " +
                    "Select-Object ProcessName, MainWindowTitle | Format-Table -AutoSize");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor(5, TimeUnit.SECONDS);

            // Look for known player names in the output
            for (String line : output.split("\n")) {
                String lower = line.toLowerCase();
                for (String player : players) {
                    if (lower.contains(player.toLowerCase())) {
                        return "Detected media player: " + line.trim();
                    }
                }
                // Chrome/Edge with music sites
                if ((lower.contains("chrome") || lower.contains("msedge")) &&
                    (lower.contains("youtube") || lower.contains("spotify") || lower.contains("soundcloud"))) {
                    return "Detected music playing in browser: " + line.trim();
                }
            }
            return "No music player detected. Spotify is not running. Try 'open Spotify' first.";
        } catch (Exception e) {
            return "No music player detected.";
        }
    }
}
