package com.minsbot;

import com.minsbot.agent.ProactiveEngineService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** UI surface for the Proactive Engine — config, custom rules, stats, trigger. */
@RestController
@RequestMapping("/api/proactive")
public class ProactiveController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProactiveEngineService engine;

    public ProactiveController(ProactiveEngineService engine) {
        this.engine = engine;
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", engine.isEnabled());
        out.put("quietHoursStart", engine.getQuietHoursStart());
        out.put("quietHoursEnd", engine.getQuietHoursEnd());
        LocalDateTime last = engine.getLastCheckTime();
        out.put("lastCheckTime", last == null ? null : last.format(FMT));
        out.put("totalCheckCount", engine.getTotalCheckCount());
        out.put("totalNotificationsSent", engine.getTotalNotificationsSent());
        out.put("customRules", ruleList());
        return out;
    }

    @PostMapping(value = "/enabled", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setEnabled(@RequestBody Map<String, Object> body) {
        Object val = body == null ? null : body.get("enabled");
        if (val != null) engine.setEnabled(Boolean.parseBoolean(val.toString()));
        return Map.of("enabled", engine.isEnabled());
    }

    @PostMapping(value = "/quiet-hours", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setQuietHours(@RequestBody Map<String, Object> body) {
        int start = parseInt(body, "start", engine.getQuietHoursStart());
        int end = parseInt(body, "end", engine.getQuietHoursEnd());
        engine.setQuietHours(start, end);
        return Map.of(
                "quietHoursStart", engine.getQuietHoursStart(),
                "quietHoursEnd", engine.getQuietHoursEnd()
        );
    }

    @GetMapping(value = "/rules", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> rules() {
        return ruleList();
    }

    @PostMapping(value = "/rules", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> addRule(@RequestBody Map<String, Object> body) {
        String desc = body == null ? null : (String) body.get("description");
        int minutes = parseInt(body, "intervalMinutes", 60);
        if (desc == null || desc.isBlank()) {
            return Map.of("success", false, "message", "description is required");
        }
        String id = engine.addCustomRule(desc, minutes);
        return Map.of("success", true, "id", id);
    }

    @DeleteMapping(value = "/rules/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteRule(@PathVariable String id) {
        return Map.of("deleted", engine.removeCustomRule(id));
    }

    @PostMapping(value = "/trigger", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> trigger() {
        engine.triggerImmediateCheck();
        return Map.of("triggered", true);
    }

    private List<Map<String, Object>> ruleList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProactiveEngineService.ProactiveRule r : engine.getCustomRules()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("description", r.description);
            m.put("intervalMinutes", r.intervalMinutes);
            out.add(m);
        }
        return out;
    }

    private static int parseInt(Map<String, Object> body, String key, int fallback) {
        if (body == null) return fallback;
        Object v = body.get(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return fallback; }
    }
}
