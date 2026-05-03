package com.minsbot.skills.applauncher;

import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.*;

@Service
public class AppLauncherService {
    private final AppLauncherConfig.AppLauncherProperties props;
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    public AppLauncherService(AppLauncherConfig.AppLauncherProperties props) { this.props = props; }

    public Map<String, Object> launch(String target) throws Exception {
        // 1. Registry alias
        String resolved = props.getRegistry().getOrDefault(target.toLowerCase(Locale.ROOT), target);
        // 2. URL?
        if (resolved.matches("^[a-z]+://.+")) {
            try { Desktop.getDesktop().browse(URI.create(resolved)); return Map.of("ok", true, "opened", resolved, "type", "url"); }
            catch (Exception e) {
                String[] cmd = WIN ? new String[]{"cmd", "/c", "start", "", resolved}
                        : MAC ? new String[]{"open", resolved} : new String[]{"xdg-open", resolved};
                new ProcessBuilder(cmd).start();
                return Map.of("ok", true, "opened", resolved, "type", "url");
            }
        }
        // 3. Existing file or path
        File f = new File(resolved);
        if (f.exists()) {
            new ProcessBuilder(WIN ? new String[]{"cmd", "/c", "start", "", resolved}
                    : MAC ? new String[]{"open", resolved}
                    : new String[]{"xdg-open", resolved}).start();
            return Map.of("ok", true, "opened", resolved, "type", "file");
        }
        // 4. Try as command
        try {
            new ProcessBuilder(WIN ? new String[]{"cmd", "/c", "start", "", resolved} : new String[]{resolved}).start();
            return Map.of("ok", true, "launched", resolved, "type", "command");
        } catch (Exception e) {
            throw new RuntimeException("Could not launch '" + target + "' (resolved to '" + resolved + "'): " + e.getMessage());
        }
    }
}
