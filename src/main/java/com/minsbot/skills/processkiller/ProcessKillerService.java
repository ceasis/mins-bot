package com.minsbot.skills.processkiller;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

@Service
public class ProcessKillerService {

    private final ProcessKillerConfig.ProcessKillerProperties props;
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");

    public ProcessKillerService(ProcessKillerConfig.ProcessKillerProperties props) { this.props = props; }

    public Map<String, Object> findByName(String pattern) throws Exception {
        if (pattern == null || pattern.isBlank()) throw new IllegalArgumentException("pattern required");
        String lower = pattern.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> p : listAll()) {
            String name = String.valueOf(p.get("name")).toLowerCase(Locale.ROOT);
            if (name.contains(lower)) matches.add(p);
        }
        return Map.of("pattern", pattern, "matches", matches, "count", matches.size());
    }

    public Map<String, Object> killByPid(int pid, boolean force) throws Exception {
        Map<String, Object> info = pidInfo(pid);
        String name = String.valueOf(info.getOrDefault("name", ""));
        if (isProtected(name)) return Map.of("ok", false, "error",
                "PID " + pid + " (" + name + ") is in protected-names — refusing.");
        return doKill(pid, name, force);
    }

    public Map<String, Object> killByName(String pattern, boolean force) throws Exception {
        if (isProtected(pattern)) return Map.of("ok", false, "error",
                "pattern '" + pattern + "' matches a protected process name.");
        Map<String, Object> found = findByName(pattern);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) found.get("matches");
        if (matches.isEmpty()) return Map.of("ok", true, "killed", 0, "message", "No process matched '" + pattern + "'");
        int killed = 0;
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (Map<String, Object> m : matches) {
            int pid = ((Number) m.get("pid")).intValue();
            String name = String.valueOf(m.get("name"));
            if (isProtected(name)) {
                attempts.add(Map.of("pid", pid, "name", name, "killed", false, "reason", "protected"));
                continue;
            }
            Map<String, Object> r = doKill(pid, name, force);
            attempts.add(r);
            if (Boolean.TRUE.equals(r.get("killed"))) killed++;
        }
        return Map.of("ok", killed > 0, "killed", killed, "attempts", attempts);
    }

    public List<Map<String, Object>> topByCpu(int n) throws Exception {
        // Best-effort: on Windows, tasklist doesn't give CPU%. Use wmic for cpu time as proxy.
        // On *nix, ps -eo pid,comm,%cpu,%mem --sort=-%cpu | head
        if (WIN) {
            String out = run("wmic", "process", "get", "ProcessId,Name,WorkingSetSize,KernelModeTime,UserModeTime", "/format:csv");
            List<Map<String, Object>> rows = new ArrayList<>();
            String[] lines = out.split("\\R");
            for (String line : lines) {
                String[] f = line.split(",");
                if (f.length < 6) continue;
                if (!f[5].matches("\\d+")) continue; // skip header
                try {
                    long kt = Long.parseLong(f[1]);
                    long ut = Long.parseLong(f[4]);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("pid", Integer.parseInt(f[5]));
                    r.put("name", f[3]);
                    r.put("cpuTime", kt + ut);
                    r.put("memoryBytes", Long.parseLong(f[6 < f.length ? 6 : 5]));
                    rows.add(r);
                } catch (Exception ignored) {}
            }
            rows.sort((a, b) -> Long.compare(((Number) b.get("cpuTime")).longValue(), ((Number) a.get("cpuTime")).longValue()));
            return rows.size() > n ? rows.subList(0, n) : rows;
        } else {
            String out = run("ps", "-eo", "pid,comm,%cpu,%mem", "--sort=-%cpu");
            return parsePsOutput(out, n);
        }
    }

    public List<Map<String, Object>> topByMemory(int n) throws Exception {
        if (WIN) {
            // tasklist with memory
            String out = run("tasklist", "/FO", "CSV", "/NH");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String line : out.split("\\R")) {
                String[] f = csvSplit(line);
                if (f.length < 5) continue;
                try {
                    long mem = Long.parseLong(f[4].replaceAll("[^0-9]", ""));
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("pid", Integer.parseInt(f[1]));
                    r.put("name", f[0]);
                    r.put("memoryKb", mem);
                    rows.add(r);
                } catch (Exception ignored) {}
            }
            rows.sort((a, b) -> Long.compare(((Number) b.get("memoryKb")).longValue(), ((Number) a.get("memoryKb")).longValue()));
            return rows.size() > n ? rows.subList(0, n) : rows;
        } else {
            String out = run("ps", "-eo", "pid,comm,%cpu,%mem", "--sort=-%mem");
            return parsePsOutput(out, n);
        }
    }

    private List<Map<String, Object>> listAll() throws Exception {
        if (WIN) {
            String out = run("tasklist", "/FO", "CSV", "/NH");
            List<Map<String, Object>> all = new ArrayList<>();
            for (String line : out.split("\\R")) {
                String[] f = csvSplit(line);
                if (f.length < 2) continue;
                try {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("pid", Integer.parseInt(f[1]));
                    r.put("name", f[0]);
                    all.add(r);
                } catch (Exception ignored) {}
            }
            return all;
        } else {
            String out = run("ps", "-eo", "pid,comm");
            List<Map<String, Object>> all = new ArrayList<>();
            for (String line : out.split("\\R")) {
                String t = line.trim();
                int sp = t.indexOf(' ');
                if (sp < 1 || !t.substring(0, sp).matches("\\d+")) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("pid", Integer.parseInt(t.substring(0, sp)));
                r.put("name", t.substring(sp + 1).trim());
                all.add(r);
            }
            return all;
        }
    }

    private Map<String, Object> pidInfo(int pid) throws Exception {
        if (WIN) {
            String out = run("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH");
            String[] f = csvSplit(out.split("\\R")[0]);
            if (f.length >= 2 && f[1].matches("\\d+"))
                return Map.of("pid", pid, "name", f[0]);
        } else {
            String out = run("ps", "-p", String.valueOf(pid), "-o", "comm=");
            return Map.of("pid", pid, "name", out.trim());
        }
        return Map.of("pid", pid, "name", "?");
    }

    private Map<String, Object> doKill(int pid, String name, boolean force) {
        try {
            String[] cmd = WIN
                    ? (force ? new String[]{"taskkill", "/F", "/PID", String.valueOf(pid)} : new String[]{"taskkill", "/PID", String.valueOf(pid)})
                    : (force ? new String[]{"kill", "-9", String.valueOf(pid)} : new String[]{"kill", String.valueOf(pid)});
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = readAll(p);
            int code = p.waitFor();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("pid", pid); r.put("name", name);
            r.put("killed", code == 0);
            r.put("output", out.trim());
            return r;
        } catch (Exception e) {
            return Map.of("pid", pid, "name", name, "killed", false, "error", e.getMessage());
        }
    }

    private boolean isProtected(String s) {
        if (s == null) return false;
        String low = s.toLowerCase(Locale.ROOT);
        for (String p : props.getProtectedNames()) if (low.contains(p.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static List<Map<String, Object>> parsePsOutput(String out, int n) {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean header = true;
        for (String line : out.split("\\R")) {
            if (header) { header = false; continue; }
            String[] parts = line.trim().split("\\s+", 4);
            if (parts.length < 4) continue;
            try {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("pid", Integer.parseInt(parts[0]));
                r.put("name", parts[1]);
                r.put("cpu", Double.parseDouble(parts[2]));
                r.put("mem", Double.parseDouble(parts[3]));
                rows.add(r);
                if (rows.size() >= n) break;
            } catch (Exception ignored) {}
        }
        return rows;
    }

    static String[] csvSplit(String line) {
        // simple csv: "a","b","c"
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inQ = !inQ;
            else if (c == ',' && !inQ) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String s = readAll(p);
        p.waitFor();
        return s;
    }

    static String readAll(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
