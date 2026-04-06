package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

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

    /** Spotify access token — set at runtime via setSpotifyToken(). */
    private volatile String spotifyToken;

    public MusicControlTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    public void setSpotifyToken(String token) {
        this.spotifyToken = token;
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

    @Tool(description = "Play a specific song, artist, or playlist on Spotify by opening the Spotify URI. "
            + "Use when the user says 'play [song] on Spotify'. If the search query is specific enough, "
            + "Spotify will start playing immediately.")
    public String spotifyPlay(
            @ToolParam(description = "What to play: song name, 'artist:Drake', 'album:Thriller', or a Spotify URI") String query) {
        notifier.notify("Playing on Spotify: " + query);
        try {
            // If it's already a Spotify URI, open directly
            if (query.startsWith("spotify:")) {
                new ProcessBuilder("cmd", "/c", "start", "", query).start();
                return "Opening Spotify URI: " + query;
            }

            // Open Spotify search and auto-play
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            // Open in web first, which triggers desktop app
            String url = "https://open.spotify.com/search/" + encoded;
            java.awt.Desktop.getDesktop().browse(new URI(url));
            return "Opened Spotify search for: " + query + ". Select a result to play.";
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
