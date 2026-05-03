package com.minsbot.skills.proxyswitcher;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Toggle Windows system proxy / list current setting. Linux: edits ~/.bashrc-style env;
 * since per-shell, returns the env vars to set rather than persisting (safer).
 */
@Service
public class ProxySwitcherService {
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    private final ProxySwitcherConfig.ProxySwitcherProperties props;
    public ProxySwitcherService(ProxySwitcherConfig.ProxySwitcherProperties props) { this.props = props; }

    public Map<String, Object> get() throws Exception {
        if (WIN) {
            String out = run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                    "/v", "ProxyEnable");
            String enabled = "off";
            for (String line : out.split("\\R")) if (line.contains("ProxyEnable")) {
                if (line.contains("0x1")) enabled = "on";
            }
            String server = run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                    "/v", "ProxyServer");
            String s = "";
            for (String line : server.split("\\R")) if (line.contains("ProxyServer")) {
                String[] f = line.trim().split("\\s+");
                if (f.length > 0) s = f[f.length - 1];
            }
            return Map.of("enabled", enabled, "server", s, "presets", props.getPresets());
        } else {
            return Map.of("note", "Linux proxy is per-shell via http_proxy/https_proxy env vars. Use /set to get the export commands.",
                    "presets", props.getPresets());
        }
    }

    public Map<String, Object> set(String hostPort) throws Exception {
        if (WIN) {
            run("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                    "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f");
            run("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                    "/v", "ProxyServer", "/t", "REG_SZ", "/d", hostPort, "/f");
            return Map.of("ok", true, "set", hostPort, "note", "Restart browsers to pick up the change.");
        } else {
            return Map.of("ok", true,
                    "exportCommands", List.of("export http_proxy=http://" + hostPort,
                            "export https_proxy=http://" + hostPort,
                            "export no_proxy=localhost,127.0.0.1"),
                    "note", "Run these in your shell — Java can't persist env to the parent shell.");
        }
    }

    public Map<String, Object> off() throws Exception {
        if (WIN) {
            run("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                    "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "0", "/f");
            return Map.of("ok", true, "message", "System proxy disabled.");
        } else {
            return Map.of("ok", true, "exportCommands", List.of("unset http_proxy", "unset https_proxy", "unset no_proxy"));
        }
    }

    public Map<String, Object> usePreset(String name) throws Exception {
        String hp = props.getPresets().get(name);
        if (hp == null) throw new IllegalArgumentException("no preset named '" + name + "'. Defined: " + props.getPresets().keySet());
        return set(hp);
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }
}
