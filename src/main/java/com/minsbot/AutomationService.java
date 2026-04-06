package com.minsbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rule engine for automations: "when X happens, do Y".
 * Rules persisted to ~/mins_bot_data/automations.json.
 */
@Service
public class AutomationService {

    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);
    private static final Path RULES_FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "automations.json");
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AtomicLong idGen = new AtomicLong(1);
    private final List<Map<String, Object>> rules = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        loadRules();
    }

    private void loadRules() {
        if (Files.exists(RULES_FILE)) {
            try {
                List<Map<String, Object>> loaded = mapper.readValue(
                        RULES_FILE.toFile(), new TypeReference<>() {});
                rules.clear();
                rules.addAll(loaded);
                long maxId = rules.stream()
                        .mapToLong(r -> ((Number) r.getOrDefault("id", 0)).longValue())
                        .max().orElse(0);
                idGen.set(maxId + 1);
                log.info("[Automations] Loaded {} rules from {}", rules.size(), RULES_FILE);
            } catch (IOException e) {
                log.warn("[Automations] Failed to load rules: {}", e.getMessage());
            }
        }
    }

    private void saveRules() {
        try {
            Files.createDirectories(RULES_FILE.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(RULES_FILE.toFile(), rules);
        } catch (IOException e) {
            log.error("[Automations] Failed to save rules: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> getRules() {
        return new ArrayList<>(rules);
    }

    public Map<String, Object> createRule(String trigger, String condition, String action, String description) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", idGen.getAndIncrement());
        rule.put("trigger", trigger);
        rule.put("condition", condition);
        rule.put("action", action);
        rule.put("description", description);
        rule.put("enabled", true);
        rule.put("hitCount", 0);
        rule.put("createdAt", System.currentTimeMillis());
        rules.add(rule);
        saveRules();
        return rule;
    }

    public Map<String, Object> updateRule(long id, Map<String, Object> updates) {
        for (Map<String, Object> rule : rules) {
            if (((Number) rule.get("id")).longValue() == id) {
                if (updates.containsKey("trigger")) rule.put("trigger", updates.get("trigger"));
                if (updates.containsKey("condition")) rule.put("condition", updates.get("condition"));
                if (updates.containsKey("action")) rule.put("action", updates.get("action"));
                if (updates.containsKey("description")) rule.put("description", updates.get("description"));
                if (updates.containsKey("enabled")) rule.put("enabled", updates.get("enabled"));
                saveRules();
                return rule;
            }
        }
        return null;
    }

    public boolean deleteRule(long id) {
        boolean removed = rules.removeIf(r -> ((Number) r.get("id")).longValue() == id);
        if (removed) saveRules();
        return removed;
    }

    public Map<String, Object> toggleRule(long id) {
        for (Map<String, Object> rule : rules) {
            if (((Number) rule.get("id")).longValue() == id) {
                rule.put("enabled", !(Boolean) rule.getOrDefault("enabled", true));
                saveRules();
                return rule;
            }
        }
        return null;
    }

    public List<String> checkTriggers(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return List.of();
        List<String> actions = new ArrayList<>();
        String lowerMsg = userMessage.toLowerCase().trim();
        for (Map<String, Object> rule : rules) {
            if (!(Boolean) rule.getOrDefault("enabled", true)) continue;
            String trigger = (String) rule.getOrDefault("trigger", "");
            String condition = (String) rule.getOrDefault("condition", "");
            String action = (String) rule.getOrDefault("action", "");

            boolean matched = switch (trigger) {
                case "message_contains" -> lowerMsg.contains(condition.toLowerCase());
                case "message_equals" -> lowerMsg.equals(condition.toLowerCase());
                case "message_starts_with" -> lowerMsg.startsWith(condition.toLowerCase());
                case "message_regex" -> {
                    try { yield userMessage.matches(condition); }
                    catch (Exception e) { yield false; }
                }
                default -> false;
            };

            if (matched && !action.isBlank()) {
                rule.put("hitCount", ((Number) rule.getOrDefault("hitCount", 0)).intValue() + 1);
                actions.add(action);
            }
        }
        if (!actions.isEmpty()) saveRules();
        return actions;
    }
}
