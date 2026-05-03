package com.minsbot.skills.scheduledtaskctl;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

@Service
public class ScheduledTaskCtlService {
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");

    public Map<String, Object> list(String filter) throws Exception {
        if (WIN) {
            String out = run("schtasks", "/Query", "/FO", "CSV", "/NH");
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (String line : out.split("\\R")) {
                String[] f = csv(line);
                if (f.length < 3) continue;
                if (filter != null && !f[0].toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) continue;
                tasks.add(Map.of("name", f[0], "nextRun", f[1], "status", f[2]));
            }
            return Map.of("count", tasks.size(), "tasks", tasks);
        } else {
            String out = run("crontab", "-l");
            List<String> lines = new ArrayList<>();
            for (String l : out.split("\\R")) if (!l.startsWith("#") && !l.isBlank()) lines.add(l);
            return Map.of("crontabEntries", lines);
        }
    }

    public Map<String, Object> create(String name, String cmd, String schedule) throws Exception {
        if (WIN) {
            // schedule examples: "MINUTE/5", "HOURLY", "DAILY", "ONCE /ST 09:00"
            // Caller passes raw schtasks /SC /MO etc — keep simple: assume schedule is "/SC DAILY /ST 09:00" style
            List<String> args = new ArrayList<>(List.of("schtasks", "/Create", "/TN", name, "/TR", cmd));
            for (String s : schedule.split("\\s+")) args.add(s);
            args.add("/F");
            return shell(args.toArray(new String[0]));
        } else {
            // For *nix, use 'at' or instruct user; cron edits are racy from a daemon
            throw new UnsupportedOperationException("Linux: edit crontab manually with 'crontab -e' or use systemd timer");
        }
    }

    public Map<String, Object> delete(String name) throws Exception {
        if (WIN) return shell(new String[]{"schtasks", "/Delete", "/TN", name, "/F"});
        throw new UnsupportedOperationException("Linux: edit crontab manually");
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
    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor();
        return sb.toString();
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
}
