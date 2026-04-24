package com.minsbot;

import com.minsbot.agent.tools.ClaudeCodeTools;
import com.minsbot.agent.tools.SpecialCodeGenerator;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Backs the /code.html page — runs ClaudeCodeTools, SpecialCodeGenerator, or both. */
@RestController
@RequestMapping("/api/code")
public class CodeController {

    private final ClaudeCodeTools claudeCodeTools;
    private final SpecialCodeGenerator specialCodeGenerator;

    public CodeController(ClaudeCodeTools claudeCodeTools, SpecialCodeGenerator specialCodeGenerator) {
        this.claudeCodeTools = claudeCodeTools;
        this.specialCodeGenerator = specialCodeGenerator;
    }

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> generate(@RequestBody Map<String, Object> body) {
        String task = str(body, "task");
        String workingDir = str(body, "workingDir");
        String mode = str(body, "mode").toLowerCase();
        if (mode.isBlank()) mode = "primary";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("workingDir", workingDir);

        long t0 = System.currentTimeMillis();
        switch (mode) {
            case "primary" -> out.put("primary", runPrimary(task, workingDir));
            case "special" -> out.put("special", runSpecial(task, workingDir));
            case "both" -> {
                Path base = Path.of(workingDir);
                out.put("primary", runPrimary(task, base.resolve("claude-cli").toString()));
                out.put("special", runSpecial(task, base.resolve("special").toString()));
            }
            default -> out.put("error", "Unknown mode: " + mode + " (expected primary | special | both)");
        }
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        return out;
    }

    private String runPrimary(String task, String dir) {
        try { return claudeCodeTools.run(task, dir); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String runSpecial(String task, String dir) {
        try { return specialCodeGenerator.run(task, dir); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }
}
