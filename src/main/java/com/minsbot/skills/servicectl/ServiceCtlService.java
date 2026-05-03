package com.minsbot.skills.servicectl;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

@Service
public class ServiceCtlService {
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");

    public Map<String, Object> list(String filter) throws Exception {
        if (WIN) {
            String out = run("sc", "query", "type=", "service", "state=", "all");
            List<Map<String, Object>> services = new ArrayList<>();
            String name = null, displayName = null, state = null;
            for (String line : out.split("\\R")) {
                String t = line.trim();
                if (t.startsWith("SERVICE_NAME:")) {
                    if (name != null) addService(services, name, displayName, state, filter);
                    name = t.substring(13).trim(); displayName = null; state = null;
                } else if (t.startsWith("DISPLAY_NAME:")) displayName = t.substring(13).trim();
                else if (t.startsWith("STATE")) {
                    int i = t.indexOf(':'); if (i > 0) state = t.substring(i + 1).trim().split("\\s+", 3)[1].trim();
                }
            }
            if (name != null) addService(services, name, displayName, state, filter);
            return Map.of("count", services.size(), "services", services);
        } else {
            String out = run("systemctl", "list-units", "--type=service", "--all", "--no-pager", "--no-legend");
            List<Map<String, Object>> services = new ArrayList<>();
            for (String line : out.split("\\R")) {
                String[] f = line.trim().split("\\s+", 5);
                if (f.length < 4) continue;
                if (filter != null && !f[0].toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) continue;
                services.add(Map.of("name", f[0], "load", f[1], "active", f[2], "sub", f[3], "description", f.length > 4 ? f[4] : ""));
            }
            return Map.of("count", services.size(), "services", services);
        }
    }

    private static void addService(List<Map<String, Object>> list, String name, String dn, String state, String filter) {
        if (filter != null && !name.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))
                && (dn == null || !dn.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))) return;
        list.add(Map.of("name", name, "displayName", dn == null ? "" : dn, "state", state == null ? "?" : state));
    }

    public Map<String, Object> action(String name, String op) throws Exception {
        String[] cmd;
        if (WIN) {
            cmd = switch (op) {
                case "start" -> new String[]{"sc", "start", name};
                case "stop" -> new String[]{"sc", "stop", name};
                case "restart" -> new String[]{"powershell", "-NoProfile", "-Command", "Restart-Service -Name '" + name + "' -Force"};
                default -> throw new IllegalArgumentException("op must be start|stop|restart");
            };
        } else {
            cmd = new String[]{"systemctl", op, name};
        }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = readAll(p);
        int code = p.waitFor();
        return Map.of("ok", code == 0, "exitCode", code, "output", out.trim());
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String s = readAll(p); p.waitFor(); return s;
    }
    private static String readAll(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
