package com.minsbot.skills.portkiller;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies and kills processes listening on a TCP port. Cross-platform
 * (Windows uses netstat+taskkill, *nix uses lsof or ss). Refuses to kill
 * ports listed in protectedPorts (defaults: 8765 = mins-bot itself).
 */
@Service
public class PortKillerService {

    private final PortKillerConfig.PortKillerProperties props;

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Pattern WIN_LISTEN = Pattern.compile(
            "TCP\\s+\\S+:(\\d+)\\s+\\S+\\s+LISTENING\\s+(\\d+)");

    public PortKillerService(PortKillerConfig.PortKillerProperties props) { this.props = props; }

    public Map<String, Object> findOnPort(int port) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("port", port);
        result.put("os", WINDOWS ? "windows" : "unix");
        List<Map<String, Object>> processes = WINDOWS ? findWindows(port) : findUnix(port);
        result.put("processes", processes);
        result.put("count", processes.size());
        return result;
    }

    public Map<String, Object> kill(int port, boolean force) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("port", port);
        if (props.getProtectedPorts() != null && props.getProtectedPorts().contains(port)) {
            result.put("ok", false);
            result.put("error", "port " + port + " is protected (in app.skills.portkiller.protected-ports). " +
                    "Refusing to kill — that would shut down the bot itself.");
            return result;
        }

        List<Map<String, Object>> procs = WINDOWS ? findWindows(port) : findUnix(port);
        if (procs.isEmpty()) {
            result.put("ok", true);
            result.put("killed", 0);
            result.put("message", "Nothing was listening on port " + port);
            return result;
        }

        List<Map<String, Object>> attempts = new ArrayList<>();
        int killed = 0;
        for (Map<String, Object> p : procs) {
            String pid = String.valueOf(p.get("pid"));
            Map<String, Object> r = new LinkedHashMap<>(p);
            try {
                String[] cmd = WINDOWS
                        ? (force ? new String[]{"taskkill", "/F", "/PID", pid} : new String[]{"taskkill", "/PID", pid})
                        : (force ? new String[]{"kill", "-9", pid} : new String[]{"kill", pid});
                Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String out = readAll(proc);
                int code = proc.waitFor();
                r.put("exitCode", code);
                r.put("output", out.trim());
                r.put("killed", code == 0);
                if (code == 0) killed++;
            } catch (Exception e) {
                r.put("killed", false);
                r.put("error", e.getMessage());
            }
            attempts.add(r);
        }
        result.put("ok", killed > 0);
        result.put("killed", killed);
        result.put("attempts", attempts);
        return result;
    }

    private List<Map<String, Object>> findWindows(int port) throws Exception {
        Process p = new ProcessBuilder("netstat", "-ano", "-p", "TCP").redirectErrorStream(true).start();
        String out = readAll(p);
        p.waitFor();
        Set<String> seenPids = new LinkedHashSet<>();
        Matcher m = WIN_LISTEN.matcher(out);
        while (m.find()) {
            int p1 = Integer.parseInt(m.group(1));
            if (p1 == port) seenPids.add(m.group(2));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String pid : seenPids) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("pid", Integer.parseInt(pid));
            rec.put("name", lookupWindowsProcessName(pid));
            result.add(rec);
        }
        return result;
    }

    private String lookupWindowsProcessName(String pid) {
        try {
            Process p = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH")
                    .redirectErrorStream(true).start();
            String out = readAll(p);
            p.waitFor();
            // CSV: "name.exe","pid","Console","1","12,345 K"
            int q1 = out.indexOf('"');
            int q2 = q1 == -1 ? -1 : out.indexOf('"', q1 + 1);
            if (q1 >= 0 && q2 > q1) return out.substring(q1 + 1, q2);
        } catch (Exception ignored) {}
        return "?";
    }

    private List<Map<String, Object>> findUnix(int port) throws Exception {
        // Try lsof first, fall back to ss
        List<Map<String, Object>> r = tryLsof(port);
        if (r != null) return r;
        return trySs(port);
    }

    private List<Map<String, Object>> tryLsof(int port) {
        try {
            Process p = new ProcessBuilder("lsof", "-iTCP:" + port, "-sTCP:LISTEN", "-n", "-P", "-Fpcn")
                    .redirectErrorStream(true).start();
            String out = readAll(p);
            int code = p.waitFor();
            if (code != 0 && out.contains("command not found")) return null;
            // -F output: lines starting with p<pid>, c<command>, n<address>
            List<Map<String, Object>> result = new ArrayList<>();
            String pid = null, cmd = null;
            for (String line : out.split("\n")) {
                if (line.startsWith("p")) {
                    if (pid != null) {
                        Map<String, Object> rec = new LinkedHashMap<>();
                        rec.put("pid", Integer.parseInt(pid));
                        rec.put("name", cmd == null ? "?" : cmd);
                        result.add(rec);
                    }
                    pid = line.substring(1).trim();
                    cmd = null;
                } else if (line.startsWith("c")) {
                    cmd = line.substring(1).trim();
                }
            }
            if (pid != null) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("pid", Integer.parseInt(pid));
                rec.put("name", cmd == null ? "?" : cmd);
                result.add(rec);
            }
            return result;
        } catch (Exception e) { return null; }
    }

    private List<Map<String, Object>> trySs(int port) throws Exception {
        Process p = new ProcessBuilder("ss", "-tlnp", "sport", "= :" + port).redirectErrorStream(true).start();
        String out = readAll(p);
        p.waitFor();
        // Lines like: LISTEN 0  ... users:(("processname",pid=12345,fd=10))
        Pattern pat = Pattern.compile("\"([^\"]+)\",pid=(\\d+)");
        Matcher m = pat.matcher(out);
        List<Map<String, Object>> result = new ArrayList<>();
        while (m.find()) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("pid", Integer.parseInt(m.group(2)));
            rec.put("name", m.group(1));
            result.add(rec);
        }
        return result;
    }

    private static String readAll(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
