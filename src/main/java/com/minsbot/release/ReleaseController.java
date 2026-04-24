package com.minsbot.release;

import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exposes update + crash status to the frontend for a small notification banner. */
@RestController
@RequestMapping("/api/release")
public class ReleaseController {

    private final UpdateCheckService updates;

    public ReleaseController(UpdateCheckService updates) {
        this.updates = updates;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new HashMap<>();
        out.put("currentVersion", updates.currentVersion());
        out.put("lastCheck", updates.lastCheck());
        out.put("lastError", updates.lastError());

        UpdateCheckService.UpdateInfo info = updates.available();
        if (info != null) {
            Map<String, Object> u = new HashMap<>();
            u.put("version", info.version());
            u.put("url", info.url());
            u.put("notes", info.notes());
            out.put("update", u);
        }

        List<Path> crashes = CrashReporter.listCrashes();
        out.put("crashCount", crashes.size());
        out.put("crashDir", CrashReporter.crashDir().toString());
        return out;
    }

    @PostMapping("/check")
    public Map<String, Object> forceCheck() {
        updates.check();
        return status();
    }

    @GetMapping("/crashes")
    public List<Map<String, Object>> crashes() {
        return CrashReporter.listCrashes().stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("name", p.getFileName().toString());
            m.put("path", p.toString());
            try { m.put("size", Files.size(p)); } catch (Exception ignored) {}
            return m;
        }).toList();
    }

    @GetMapping("/crashes/{name}")
    public Map<String, Object> crash(@PathVariable String name) throws Exception {
        // Prevent path traversal — only accept simple filenames we created.
        if (!name.matches("crash-[A-Za-z0-9._\\-]+\\.log")) {
            return Map.of("error", "invalid name");
        }
        Path p = CrashReporter.crashDir().resolve(name);
        if (!Files.isRegularFile(p)) return Map.of("error", "not found");
        return Map.of("name", name, "content", Files.readString(p));
    }

    @DeleteMapping("/crashes/{name}")
    public Map<String, Object> deleteCrash(@PathVariable String name) throws Exception {
        if (!name.matches("crash-[A-Za-z0-9._\\-]+\\.log")) {
            return Map.of("ok", false, "error", "invalid name");
        }
        Path p = CrashReporter.crashDir().resolve(name);
        boolean ok = Files.deleteIfExists(p);
        return Map.of("ok", ok);
    }
}
