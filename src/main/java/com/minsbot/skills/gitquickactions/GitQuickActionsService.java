package com.minsbot.skills.gitquickactions;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;

@Service
public class GitQuickActionsService {

    public Map<String, Object> snapshot(String path) throws Exception {
        File dir = new File(path == null || path.isBlank() ? "." : path);
        if (!dir.isDirectory()) throw new IllegalArgumentException("not a directory: " + path);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("path", dir.getAbsolutePath());
        r.put("branch", run(dir, "git", "rev-parse", "--abbrev-ref", "HEAD").trim());
        r.put("status", run(dir, "git", "status", "--short").trim());
        r.put("recentCommits", run(dir, "git", "log", "--oneline", "-n", "5").trim());
        r.put("ahead/behind", run(dir, "git", "rev-list", "--left-right", "--count", "HEAD...@{u}").trim());
        r.put("stashes", run(dir, "git", "stash", "list").trim());
        return r;
    }

    public Map<String, Object> staleBranches(String path, int days) throws Exception {
        File dir = new File(path == null || path.isBlank() ? "." : path);
        String out = run(dir, "git", "for-each-ref", "--sort=-committerdate", "--format=%(refname:short)|%(committerdate:relative)|%(committerdate:iso8601-strict)",
                "refs/heads/");
        List<Map<String, Object>> stale = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000;
        for (String line : out.split("\\R")) {
            String[] f = line.split("\\|", 3);
            if (f.length < 3) continue;
            try {
                long t = java.time.OffsetDateTime.parse(f[2]).toInstant().toEpochMilli();
                if (t < cutoff) stale.add(Map.of("branch", f[0], "lastCommit", f[1], "isoDate", f[2]));
            } catch (Exception ignored) {}
        }
        return Map.of("days", days, "stale", stale);
    }

    private static String run(File cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }
}
