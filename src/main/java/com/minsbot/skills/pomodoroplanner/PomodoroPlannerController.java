package com.minsbot.skills.pomodoroplanner;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/pomodoroplanner")
public class PomodoroPlannerController {
    private final PomodoroPlannerService service;
    private final PomodoroPlannerConfig.PomodoroPlannerProperties properties;
    public PomodoroPlannerController(PomodoroPlannerService service, PomodoroPlannerConfig.PomodoroPlannerProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "pomodoroplanner", "enabled", properties.isEnabled())); }

    @PostMapping("/plan") @SuppressWarnings("unchecked") public ResponseEntity<?> plan(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "PomodoroPlanner skill is disabled"));
        try {
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) body.get("tasks");
            String start = (String) body.get("startTime");
            int work = body.get("workMinutes") == null ? 25 : ((Number) body.get("workMinutes")).intValue();
            int shortB = body.get("shortBreak") == null ? 5 : ((Number) body.get("shortBreak")).intValue();
            int longB = body.get("longBreak") == null ? 15 : ((Number) body.get("longBreak")).intValue();
            int after = body.get("longBreakAfter") == null ? 4 : ((Number) body.get("longBreakAfter")).intValue();
            return ResponseEntity.ok(service.plan(tasks, start, work, shortB, longB, after));
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
