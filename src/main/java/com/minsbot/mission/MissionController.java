package com.minsbot.mission;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/missions")
public class MissionController {

    private final MissionService missions;

    public MissionController(MissionService missions) {
        this.missions = missions;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body) {
        String goal = body == null ? null : (String) body.get("goal");
        @SuppressWarnings("unchecked")
        List<String> steps = body == null ? null : (List<String>) body.get("steps");
        if (goal == null || goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        String id = missions.startMission(goal, steps);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list() {
        List<Map<String, Object>> dtos = missions.listMissions().stream()
                .map(missions::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("missions", dtos));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> get(@PathVariable String id) {
        Mission m = missions.getMission(id);
        if (m == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(missions.toDto(m));
    }

    @PostMapping(value = "/{id}/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> stop(@PathVariable String id) {
        boolean ok = missions.stopMission(id);
        return ResponseEntity.ok(Map.of("stopped", ok));
    }
}
