package com.minsbot.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Habit pattern detection: learns user behavior by tracking actions over time.
 * Detects recurring patterns like "always checks email at 9am", "opens VS Code after lunch".
 * Persisted to ~/mins_bot_data/habits.json.
 */
@Component
public class HabitDetectionTools {

    private static final Logger log = LoggerFactory.getLogger(HabitDetectionTools.class);
    private static final Path HABITS_FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "habits.json");
    private static final Path EVENTS_FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "habit_events.json");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_EVENTS = 5000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final ToolExecutionNotifier notifier;
    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> detectedHabits = new CopyOnWriteArrayList<>();

    public HabitDetectionTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @PostConstruct
    public void init() {
        loadEvents();
        loadHabits();
    }

    // ═══ Event logging (called by other tools / ChatService) ═══

    /** Record a user action for habit detection. Called internally. */
    public void recordAction(String action, String category) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("action", action);
        event.put("category", category);
        event.put("timestamp", System.currentTimeMillis());
        event.put("hour", LocalTime.now().getHour());
        event.put("dayOfWeek", LocalDate.now().getDayOfWeek().name());
        events.add(event);
        while (events.size() > MAX_EVENTS) events.remove(0);
        saveEvents();
    }

    // ═══ AI-callable tools ═══

    @Tool(description = "Log a user action for habit tracking. Call this when you notice the user "
            + "doing something repeatedly (opening an app, checking email, running a command). "
            + "Over time, patterns will emerge.")
    public String logHabit(
            @ToolParam(description = "What the user did, e.g. 'checked email', 'opened VS Code', 'asked about weather'") String action,
            @ToolParam(description = "Category: communication, development, browsing, productivity, health, entertainment, other") String category) {
        recordAction(action, category);
        return "Logged: " + action + " (" + category + ")";
    }

    @Tool(description = "Analyze recorded actions and detect habit patterns. "
            + "Finds recurring behaviors like 'checks email every morning at 9am' or "
            + "'opens Spotify every afternoon'. Use when the user asks 'what are my habits?', "
            + "'what patterns have you noticed?', 'what do I usually do?'.")
    public String detectPatterns() {
        notifier.notify("Analyzing behavior patterns...");
        if (events.size() < 10) {
            return "Not enough data yet (" + events.size() + " events). I need at least 10 recorded actions to detect patterns. "
                    + "Keep using the bot and I'll learn your habits over time.";
        }

        List<Map<String, Object>> patterns = new ArrayList<>();

        // ── Time-of-day patterns ──
        Map<String, Map<Integer, Integer>> actionByHour = new LinkedHashMap<>();
        for (Map<String, Object> e : events) {
            String action = (String) e.get("action");
            int hour = ((Number) e.get("hour")).intValue();
            actionByHour.computeIfAbsent(action, k -> new LinkedHashMap<>())
                    .merge(hour, 1, Integer::sum);
        }

        for (var entry : actionByHour.entrySet()) {
            String action = entry.getKey();
            Map<Integer, Integer> hourCounts = entry.getValue();
            int totalForAction = hourCounts.values().stream().mapToInt(Integer::intValue).sum();
            if (totalForAction < 3) continue;

            // Find dominant hour
            var best = hourCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElse(null);
            if (best != null && best.getValue() >= 3) {
                double ratio = (double) best.getValue() / totalForAction;
                if (ratio >= 0.4) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("type", "time_pattern");
                    p.put("action", action);
                    p.put("hour", best.getKey());
                    p.put("timeLabel", timeLabel(best.getKey()));
                    p.put("occurrences", best.getValue());
                    p.put("confidence", Math.round(ratio * 100));
                    patterns.add(p);
                }
            }
        }

        // ── Day-of-week patterns ──
        Map<String, Map<String, Integer>> actionByDay = new LinkedHashMap<>();
        for (Map<String, Object> e : events) {
            String action = (String) e.get("action");
            String day = (String) e.get("dayOfWeek");
            actionByDay.computeIfAbsent(action, k -> new LinkedHashMap<>())
                    .merge(day, 1, Integer::sum);
        }

        for (var entry : actionByDay.entrySet()) {
            String action = entry.getKey();
            Map<String, Integer> dayCounts = entry.getValue();
            int total = dayCounts.values().stream().mapToInt(Integer::intValue).sum();
            if (total < 4) continue;

            var best = dayCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElse(null);
            if (best != null && best.getValue() >= 3) {
                double ratio = (double) best.getValue() / total;
                if (ratio >= 0.35) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("type", "day_pattern");
                    p.put("action", action);
                    p.put("dayOfWeek", best.getKey());
                    p.put("occurrences", best.getValue());
                    p.put("confidence", Math.round(ratio * 100));
                    patterns.add(p);
                }
            }
        }

        // ── Frequency patterns (how often) ──
        Map<String, Integer> actionCounts = new LinkedHashMap<>();
        for (Map<String, Object> e : events) {
            actionCounts.merge((String) e.get("action"), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> topActions = actionCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toList());

        // Save detected patterns
        detectedHabits.clear();
        detectedHabits.addAll(patterns);
        saveHabits();

        // Build response
        StringBuilder sb = new StringBuilder("Detected Patterns (" + events.size() + " events analyzed):\n\n");

        if (patterns.isEmpty() && topActions.isEmpty()) {
            sb.append("No strong patterns detected yet. Keep using the bot!\n");
        }

        if (!patterns.isEmpty()) {
            sb.append("⏰ Recurring behaviors:\n");
            for (Map<String, Object> p : patterns) {
                if ("time_pattern".equals(p.get("type"))) {
                    sb.append("  • You ").append(p.get("action"))
                            .append(" most often around ").append(p.get("timeLabel"))
                            .append(" (").append(p.get("occurrences")).append("x, ")
                            .append(p.get("confidence")).append("% confidence)\n");
                } else if ("day_pattern".equals(p.get("type"))) {
                    String day = DayOfWeek.valueOf((String) p.get("dayOfWeek"))
                            .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                    sb.append("  • You ").append(p.get("action"))
                            .append(" most often on ").append(day)
                            .append(" (").append(p.get("occurrences")).append("x, ")
                            .append(p.get("confidence")).append("% confidence)\n");
                }
            }
            sb.append("\n");
        }

        if (!topActions.isEmpty()) {
            sb.append("📊 Most frequent actions:\n");
            for (var entry : topActions) {
                sb.append("  • ").append(entry.getKey()).append(" (").append(entry.getValue()).append("x)\n");
            }
        }

        return sb.toString();
    }

    @Tool(description = "Show habit detection statistics: total events, categories, date range.")
    public String habitStats() {
        if (events.isEmpty()) return "No events recorded yet.";

        Map<String, Integer> categories = new LinkedHashMap<>();
        long earliest = Long.MAX_VALUE, latest = 0;
        for (Map<String, Object> e : events) {
            categories.merge((String) e.getOrDefault("category", "other"), 1, Integer::sum);
            long ts = ((Number) e.get("timestamp")).longValue();
            if (ts < earliest) earliest = ts;
            if (ts > latest) latest = ts;
        }

        long days = Duration.between(Instant.ofEpochMilli(earliest), Instant.ofEpochMilli(latest)).toDays() + 1;
        StringBuilder sb = new StringBuilder("Habit Tracking Stats:\n");
        sb.append("  Total events: ").append(events.size()).append("\n");
        sb.append("  Tracking period: ").append(days).append(" day(s)\n");
        sb.append("  Detected patterns: ").append(detectedHabits.size()).append("\n");
        sb.append("  Categories:\n");
        categories.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sb.append("    • ").append(e.getKey()).append(": ").append(e.getValue()).append("\n"));
        return sb.toString();
    }

    @Tool(description = "Clear all habit tracking data and start fresh.")
    public String clearHabits() {
        int count = events.size();
        events.clear();
        detectedHabits.clear();
        saveEvents();
        saveHabits();
        return "Cleared " + count + " events and all detected patterns.";
    }

    // ═══ Helpers ═══

    private String timeLabel(int hour) {
        if (hour >= 5 && hour < 9) return "early morning (" + hour + ":00)";
        if (hour >= 9 && hour < 12) return "morning (" + hour + ":00)";
        if (hour >= 12 && hour < 14) return "midday (" + hour + ":00)";
        if (hour >= 14 && hour < 17) return "afternoon (" + hour + ":00)";
        if (hour >= 17 && hour < 21) return "evening (" + hour + ":00)";
        return "night (" + hour + ":00)";
    }

    private void loadEvents() {
        if (Files.exists(EVENTS_FILE)) {
            try {
                events.addAll(mapper.readValue(EVENTS_FILE.toFile(), new TypeReference<>() {}));
                log.info("[Habits] Loaded {} events", events.size());
            } catch (IOException e) { log.warn("[Habits] Failed to load events: {}", e.getMessage()); }
        }
    }

    private void saveEvents() {
        try {
            Files.createDirectories(EVENTS_FILE.getParent());
            mapper.writeValue(EVENTS_FILE.toFile(), events);
        } catch (IOException e) { log.error("[Habits] Failed to save events: {}", e.getMessage()); }
    }

    private void loadHabits() {
        if (Files.exists(HABITS_FILE)) {
            try {
                detectedHabits.addAll(mapper.readValue(HABITS_FILE.toFile(), new TypeReference<>() {}));
            } catch (IOException ignored) {}
        }
    }

    private void saveHabits() {
        try {
            Files.createDirectories(HABITS_FILE.getParent());
            mapper.writeValue(HABITS_FILE.toFile(), detectedHabits);
        } catch (IOException ignored) {}
    }
}
