package com.minsbot.skills.appkill;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Kill an app by executable name (e.g. "chrome", "notepad"). Differs from
 * processkiller in that this targets visible apps by simple name and works
 * with /IM on Windows so all instances die at once.
 */
@Service
public class AppKillService {
    private final AppKillConfig.AppKillProperties props;
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    public AppKillService(AppKillConfig.AppKillProperties props) { this.props = props; }

    public Map<String, Object> killByExeName(String name) throws Exception {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        String low = name.toLowerCase(Locale.ROOT);
        for (String p : props.getProtectedNames())
            if (low.contains(p.toLowerCase(Locale.ROOT)))
                return Map.of("ok", false, "error", "name '" + name + "' matches protected app");

        String exe = WIN && !name.toLowerCase().endsWith(".exe") ? name + ".exe" : name;
        String[] cmd = WIN ? new String[]{"taskkill", "/F", "/IM", exe}
                : new String[]{"pkill", "-f", name};
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        int code = p.waitFor();
        return Map.of("ok", code == 0, "exitCode", code, "name", name, "output", sb.toString().trim());
    }
}
