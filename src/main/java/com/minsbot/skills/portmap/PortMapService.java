package com.minsbot.skills.portmap;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PortMapService {
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Pattern WIN_LISTEN = Pattern.compile(
            "TCP\\s+\\S+:(\\d+)\\s+\\S+\\s+LISTENING\\s+(\\d+)");

    public Map<String, Object> listAll() throws Exception {
        Map<Integer, Map<String, Object>> byPort = new TreeMap<>();
        if (WIN) {
            Process p = new ProcessBuilder("netstat", "-ano", "-p", "TCP").redirectErrorStream(true).start();
            String out = read(p); p.waitFor();
            Matcher m = WIN_LISTEN.matcher(out);
            while (m.find()) {
                int port = Integer.parseInt(m.group(1));
                String pid = m.group(2);
                byPort.computeIfAbsent(port, k -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("port", k); r.put("pids", new LinkedHashSet<String>()); return r;
                });
                @SuppressWarnings("unchecked")
                Set<String> pids = (Set<String>) byPort.get(port).get("pids");
                pids.add(pid);
            }
            // resolve pid -> name in one tasklist call
            Map<String, String> nameMap = new HashMap<>();
            try {
                Process tp = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH").redirectErrorStream(true).start();
                String to = read(tp); tp.waitFor();
                for (String line : to.split("\\R")) {
                    String[] f = csv(line);
                    if (f.length >= 2 && f[1].matches("\\d+")) nameMap.put(f[1], f[0]);
                }
            } catch (Exception ignored) {}
            for (Map<String, Object> rec : byPort.values()) {
                @SuppressWarnings("unchecked")
                Set<String> pids = (Set<String>) rec.get("pids");
                List<Map<String, Object>> procs = new ArrayList<>();
                for (String pid : pids) procs.add(Map.of("pid", Integer.parseInt(pid), "name", nameMap.getOrDefault(pid, "?")));
                rec.put("processes", procs);
                rec.remove("pids");
            }
        } else {
            try {
                Process p = new ProcessBuilder("ss", "-tlnp").redirectErrorStream(true).start();
                String out = read(p); p.waitFor();
                Pattern row = Pattern.compile("LISTEN.*?:(\\d+)\\s.*?\"([^\"]+)\",pid=(\\d+)");
                Matcher m = row.matcher(out);
                while (m.find()) {
                    int port = Integer.parseInt(m.group(1));
                    Map<String, Object> rec = byPort.computeIfAbsent(port, k -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("port", k); r.put("processes", new ArrayList<Map<String, Object>>()); return r;
                    });
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> ps = (List<Map<String, Object>>) rec.get("processes");
                    ps.add(Map.of("pid", Integer.parseInt(m.group(3)), "name", m.group(2)));
                }
            } catch (Exception e) { return Map.of("error", e.getMessage()); }
        }
        return Map.of("count", byPort.size(), "ports", byPort.values());
    }

    private static String[] csv(String line) {
        List<String> out = new ArrayList<>(); StringBuilder c = new StringBuilder(); boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') q = !q;
            else if (ch == ',' && !q) { out.add(c.toString()); c.setLength(0); }
            else c.append(ch);
        }
        out.add(c.toString());
        return out.toArray(new String[0]);
    }
    private static String read(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
