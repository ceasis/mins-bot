package com.minsbot.skills.mediactl;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Sends media keys (play/pause/next/prev) and volume keys to the OS. Cross-platform
 * via java.awt.Robot for the keys that are mappable; falls back to OS-specific
 * commands for volume mute on platforms where Robot keycodes don't map.
 */
@Service
public class MediaCtlService {
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    public Map<String, Object> playPause() throws Exception { return mediaKey("play"); }
    public Map<String, Object> next() throws Exception { return mediaKey("next"); }
    public Map<String, Object> prev() throws Exception { return mediaKey("prev"); }
    public Map<String, Object> stop() throws Exception { return mediaKey("stop"); }
    public Map<String, Object> volumeUp() throws Exception { return volumeKey("up"); }
    public Map<String, Object> volumeDown() throws Exception { return volumeKey("down"); }
    public Map<String, Object> mute() throws Exception { return volumeKey("mute"); }

    private Map<String, Object> mediaKey(String which) throws Exception {
        if (WIN) {
            // VK codes: 0xB3 play/pause, 0xB0 next, 0xB1 prev, 0xB2 stop
            int code = switch (which) { case "next" -> 0xB0; case "prev" -> 0xB1; case "stop" -> 0xB2; default -> 0xB3; };
            return shell(new String[]{"powershell", "-NoProfile", "-Command",
                    "(New-Object -com wscript.shell).SendKeys([char]" + code + ")"});
        } else if (MAC) {
            // AppleScript-driven Music app control
            String app = switch (which) { case "next" -> "next track"; case "prev" -> "previous track"; case "stop" -> "stop"; default -> "playpause"; };
            return shell(new String[]{"osascript", "-e", "tell application \"Music\" to " + app});
        } else {
            String act = switch (which) { case "next" -> "next"; case "prev" -> "previous"; case "stop" -> "stop"; default -> "play-pause"; };
            return shell(new String[]{"playerctl", act});
        }
    }

    private Map<String, Object> volumeKey(String which) throws Exception {
        if (WIN) {
            // 0xAD mute, 0xAE down, 0xAF up — sent via WScript.Shell.SendKeys with [char]
            int code = switch (which) { case "up" -> 0xAF; case "down" -> 0xAE; default -> 0xAD; };
            return shell(new String[]{"powershell", "-NoProfile", "-Command",
                    "(New-Object -com wscript.shell).SendKeys([char]" + code + ")"});
        } else if (MAC) {
            return switch (which) {
                case "up" -> shell(new String[]{"osascript", "-e", "set volume output volume (output volume of (get volume settings) + 6)"});
                case "down" -> shell(new String[]{"osascript", "-e", "set volume output volume (output volume of (get volume settings) - 6)"});
                default -> shell(new String[]{"osascript", "-e", "set volume output muted not output muted of (get volume settings)"});
            };
        } else {
            return switch (which) {
                case "up" -> shell(new String[]{"sh", "-c", "amixer -D pulse sset Master 5%+"});
                case "down" -> shell(new String[]{"sh", "-c", "amixer -D pulse sset Master 5%-"});
                default -> shell(new String[]{"sh", "-c", "amixer -D pulse sset Master toggle"});
            };
        }
    }

    public Map<String, Object> setVolumePercent(int pct) throws Exception {
        if (pct < 0 || pct > 100) throw new IllegalArgumentException("pct 0-100");
        if (WIN) {
            // Use nircmd if available; otherwise mute and step. Best path is to nudge via VK_VOLUME_UP/DOWN.
            // We rely on small-step nudging being acceptable; for precise set, recommend installing nircmd.
            return Map.of("ok", false, "note", "Set absolute volume on Windows requires nircmd or AudioSwitcher CLI; use volumeUp/volumeDown instead");
        } else if (MAC) {
            return shell(new String[]{"osascript", "-e", "set volume output volume " + pct});
        } else {
            return shell(new String[]{"sh", "-c", "amixer -D pulse sset Master " + pct + "%"});
        }
    }

    private static Map<String, Object> shell(String[] cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        int code = p.waitFor();
        return Map.of("ok", code == 0, "exitCode", code, "output", sb.toString().trim());
    }
}
