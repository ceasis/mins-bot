package com.minsbot;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * REST endpoints for the Automations tab.
 */
@RestController
@RequestMapping("/api/automations")
public class AutomationController {

    private final AutomationService automationService;

    public AutomationController(AutomationService automationService) {
        this.automationService = automationService;
    }

    @GetMapping
    public List<Map<String, Object>> listRules() {
        return automationService.getRules();
    }

    @PostMapping
    public Map<String, Object> createRule(@RequestBody Map<String, String> body) {
        String trigger = body.getOrDefault("trigger", "message_contains");
        String condition = body.getOrDefault("condition", "");
        String action = body.getOrDefault("action", "");
        String description = body.getOrDefault("description", "");
        return automationService.createRule(trigger, condition, action, description);
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateRule(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> updated = automationService.updateRule(id, body);
        if (updated == null) return Map.of("error", "Rule not found");
        return updated;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteRule(@PathVariable long id) {
        boolean deleted = automationService.deleteRule(id);
        return Map.of("deleted", deleted);
    }

    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggleRule(@PathVariable long id) {
        Map<String, Object> toggled = automationService.toggleRule(id);
        if (toggled == null) return Map.of("error", "Rule not found");
        return toggled;
    }
}
