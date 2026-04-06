package com.minsbot.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Health tracking tools backed by flat text files in ~/mins_bot_data/health/.
 * Tracks water, meals, exercise, weight, mood, sleep, and medications.
 */
@Component
public class HealthTrackerTools {

    private static final Path HEALTH_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "health");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final ToolExecutionNotifier notifier;

    public HealthTrackerTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    // ─── Logging tools ─────────────────────────────────────────────────────

    @Tool(description = "Log water intake for today. Each call appends a timestamped entry. "
            + "Use when the user says 'I drank 2 glasses of water', 'log water', etc.")
    public String logWater(
            @ToolParam(description = "Number of glasses of water") int glasses) {
        notifier.notify("Logging water intake...");
        try {
            ensureDirExists();
            String today = LocalDate.now().format(DATE_FMT);
            Path file = HEALTH_DIR.resolve("water_" + today + ".txt");
            String entry = "[" + now() + "] " + glasses + " glass(es)\n";
            appendToFile(file, entry);

            int total = countWaterForFile(file);
            return "Logged " + glasses + " glass(es) of water. Total today: " + total + " glass(es).";
        } catch (IOException e) {
            return "Failed to log water: " + e.getMessage();
        }
    }

    @Tool(description = "Log a meal with description and calories. "
            + "Use when the user says 'I had chicken salad for lunch, about 450 calories'.")
    public String logMeal(
            @ToolParam(description = "Meal description, e.g. 'Chicken salad for lunch'") String description,
            @ToolParam(description = "Estimated calories") int calories) {
        notifier.notify("Logging meal...");
        try {
            ensureDirExists();
            String today = LocalDate.now().format(DATE_FMT);
            Path file = HEALTH_DIR.resolve("meals_" + today + ".txt");
            String entry = "[" + now() + "] " + description + " | " + calories + " cal\n";
            appendToFile(file, entry);
            return "Logged meal: " + description + " (" + calories + " cal).";
        } catch (IOException e) {
            return "Failed to log meal: " + e.getMessage();
        }
    }

    @Tool(description = "Log an exercise/workout session. "
            + "Use when the user says 'I ran for 30 minutes', 'did a 45 min yoga session'.")
    public String logExercise(
            @ToolParam(description = "Exercise type, e.g. 'Running', 'Yoga', 'Weight training'") String type,
            @ToolParam(description = "Duration in minutes") int durationMinutes,
            @ToolParam(description = "Optional notes, e.g. '5K pace', 'upper body focus'. Use '-' if none") String notes) {
        notifier.notify("Logging exercise...");
        try {
            ensureDirExists();
            String today = LocalDate.now().format(DATE_FMT);
            Path file = HEALTH_DIR.resolve("exercise_" + today + ".txt");
            String notePart = (notes != null && !notes.equals("-") && !notes.isBlank()) ? " | " + notes : "";
            String entry = "[" + now() + "] " + type + " | " + durationMinutes + " min" + notePart + "\n";
            appendToFile(file, entry);
            return "Logged exercise: " + type + " for " + durationMinutes + " minutes.";
        } catch (IOException e) {
            return "Failed to log exercise: " + e.getMessage();
        }
    }

    @Tool(description = "Log body weight. Appended to a running weight log with date. "
            + "Use when the user says 'my weight is 75 kg', 'I weigh 165 lbs'.")
    public String logWeight(
            @ToolParam(description = "Weight in kg") double kg) {
        notifier.notify("Logging weight...");
        try {
            ensureDirExists();
            Path file = HEALTH_DIR.resolve("weight_log.txt");
            String today = LocalDate.now().format(DATE_FMT);
            String entry = "[" + today + "] " + String.format("%.1f", kg) + " kg\n";
            appendToFile(file, entry);
            return "Logged weight: " + String.format("%.1f", kg) + " kg on " + today + ".";
        } catch (IOException e) {
            return "Failed to log weight: " + e.getMessage();
        }
    }

    @Tool(description = "Log mood on a 1-10 scale with optional notes. "
            + "Use when the user says 'my mood is 7', 'feeling great today, 9/10'.")
    public String logMood(
            @ToolParam(description = "Mood rating from 1 (worst) to 10 (best)") int mood,
            @ToolParam(description = "Optional notes about mood, e.g. 'stressed about work'. Use '-' if none") String notes) {
        notifier.notify("Logging mood...");
        try {
            if (mood < 1 || mood > 10) {
                return "Mood must be between 1 and 10.";
            }
            ensureDirExists();
            String today = LocalDate.now().format(DATE_FMT);
            Path file = HEALTH_DIR.resolve("mood_" + today + ".txt");
            String notePart = (notes != null && !notes.equals("-") && !notes.isBlank()) ? " | " + notes : "";
            String entry = "[" + now() + "] " + mood + "/10" + notePart + "\n";
            appendToFile(file, entry);
            return "Logged mood: " + mood + "/10.";
        } catch (IOException e) {
            return "Failed to log mood: " + e.getMessage();
        }
    }

    @Tool(description = "Log sleep duration and quality. "
            + "Use when the user says 'I slept 7 hours, good quality', 'got 5 hours of sleep'.")
    public String logSleep(
            @ToolParam(description = "Hours of sleep") double hours,
            @ToolParam(description = "Sleep quality, e.g. 'good', 'poor', 'restless', 'deep'") String quality) {
        notifier.notify("Logging sleep...");
        try {
            ensureDirExists();
            Path file = HEALTH_DIR.resolve("sleep_log.txt");
            String today = LocalDate.now().format(DATE_FMT);
            String entry = "[" + today + "] " + String.format("%.1f", hours) + " hrs | " + safe(quality) + "\n";
            appendToFile(file, entry);
            return "Logged sleep: " + String.format("%.1f", hours) + " hours (" + safe(quality) + ").";
        } catch (IOException e) {
            return "Failed to log sleep: " + e.getMessage();
        }
    }

    @Tool(description = "Log medication taken. "
            + "Use when the user says 'I took 500mg ibuprofen', 'took my vitamins at 8am'.")
    public String logMedication(
            @ToolParam(description = "Medication name, e.g. 'Ibuprofen', 'Vitamin D'") String name,
            @ToolParam(description = "Dose, e.g. '500mg', '1 tablet', '10ml'") String dose,
            @ToolParam(description = "Time taken, e.g. '08:00', 'morning', 'with lunch'") String time) {
        notifier.notify("Logging medication...");
        try {
            ensureDirExists();
            String today = LocalDate.now().format(DATE_FMT);
            Path file = HEALTH_DIR.resolve("medications_" + today + ".txt");
            String entry = "[" + now() + "] " + name + " | " + dose + " | " + time + "\n";
            appendToFile(file, entry);
            return "Logged medication: " + name + " (" + dose + ") at " + time + ".";
        } catch (IOException e) {
            return "Failed to log medication: " + e.getMessage();
        }
    }

    // ─── Summary & trend tools ─────────────────────────────────────────────

    @Tool(description = "Get a health summary for a specific date showing water, meals, exercise, mood, "
            + "and medications. Use when the user asks 'how was my health yesterday?', 'show my health for today'.")
    public String getHealthSummary(
            @ToolParam(description = "Date in YYYY-MM-DD format, e.g. '2025-01-15'") String date) {
        notifier.notify("Loading health summary for " + date + "...");
        try {
            ensureDirExists();
            StringBuilder sb = new StringBuilder();
            sb.append("Health Summary for ").append(date).append("\n");
            sb.append("═══════════════════════════════\n\n");

            // Water
            Path waterFile = HEALTH_DIR.resolve("water_" + date + ".txt");
            if (Files.exists(waterFile)) {
                int total = countWaterForFile(waterFile);
                sb.append("Water: ").append(total).append(" glass(es)\n");
                sb.append(readFileContent(waterFile)).append("\n");
            } else {
                sb.append("Water: No entries\n\n");
            }

            // Meals
            Path mealsFile = HEALTH_DIR.resolve("meals_" + date + ".txt");
            if (Files.exists(mealsFile)) {
                String meals = readFileContent(mealsFile);
                int totalCal = countCalories(meals);
                sb.append("Meals: ").append(totalCal).append(" cal total\n");
                sb.append(meals).append("\n");
            } else {
                sb.append("Meals: No entries\n\n");
            }

            // Exercise
            Path exerciseFile = HEALTH_DIR.resolve("exercise_" + date + ".txt");
            if (Files.exists(exerciseFile)) {
                sb.append("Exercise:\n");
                sb.append(readFileContent(exerciseFile)).append("\n");
            } else {
                sb.append("Exercise: No entries\n\n");
            }

            // Mood
            Path moodFile = HEALTH_DIR.resolve("mood_" + date + ".txt");
            if (Files.exists(moodFile)) {
                sb.append("Mood:\n");
                sb.append(readFileContent(moodFile)).append("\n");
            } else {
                sb.append("Mood: No entries\n\n");
            }

            // Medications
            Path medsFile = HEALTH_DIR.resolve("medications_" + date + ".txt");
            if (Files.exists(medsFile)) {
                sb.append("Medications:\n");
                sb.append(readFileContent(medsFile)).append("\n");
            } else {
                sb.append("Medications: No entries\n\n");
            }

            // Sleep (check the log file for this date)
            Path sleepFile = HEALTH_DIR.resolve("sleep_log.txt");
            if (Files.exists(sleepFile)) {
                String sleepEntry = findEntriesForDate(sleepFile, date);
                if (!sleepEntry.isEmpty()) {
                    sb.append("Sleep:\n").append(sleepEntry).append("\n");
                } else {
                    sb.append("Sleep: No entry\n\n");
                }
            } else {
                sb.append("Sleep: No entry\n\n");
            }

            // Weight (check the log file for this date)
            Path weightFile = HEALTH_DIR.resolve("weight_log.txt");
            if (Files.exists(weightFile)) {
                String weightEntry = findEntriesForDate(weightFile, date);
                if (!weightEntry.isEmpty()) {
                    sb.append("Weight:\n").append(weightEntry).append("\n");
                }
            }

            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to get health summary: " + e.getMessage();
        }
    }

    @Tool(description = "Get a trend for a health metric over the past N days. "
            + "Supported metrics: weight, mood, sleep, water, exercise. "
            + "Use when the user asks 'how has my weight changed?', 'show my mood trend this week'.")
    public String getHealthTrend(
            @ToolParam(description = "Metric: 'weight', 'mood', 'sleep', 'water', or 'exercise'") String metric,
            @ToolParam(description = "Number of days to look back") int days) {
        notifier.notify("Analyzing " + metric + " trend over " + days + " days...");
        try {
            ensureDirExists();
            LocalDate today = LocalDate.now();
            StringBuilder sb = new StringBuilder();
            sb.append(capitalize(metric)).append(" Trend (last ").append(days).append(" days)\n");
            sb.append("═══════════════════════════════\n\n");

            List<String> entries = new ArrayList<>();

            for (int i = days - 1; i >= 0; i--) {
                LocalDate date = today.minusDays(i);
                String dateStr = date.format(DATE_FMT);
                String value = getTrendValue(metric, dateStr);
                if (value != null) {
                    entries.add(dateStr + " | " + value);
                }
            }

            if (entries.isEmpty()) {
                return "No " + metric + " data found in the last " + days + " days.";
            }

            for (String entry : entries) {
                sb.append(entry).append("\n");
            }

            sb.append("\nTotal entries: ").append(entries.size()).append(" / ").append(days).append(" days");
            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to get trend: " + e.getMessage();
        }
    }

    // ─── Goals ─────────────────────────────────────────────────────────────

    @Tool(description = "Set a health goal, e.g. 'drink 8 glasses of water daily', 'exercise 30 minutes daily'. "
            + "Use when the user says 'set a goal to drink 8 glasses', 'my target weight is 70 kg'.")
    public String setHealthGoal(
            @ToolParam(description = "Metric, e.g. 'water', 'exercise', 'weight', 'sleep', 'calories'") String metric,
            @ToolParam(description = "Target value, e.g. '8', '30', '70', '7'") String target,
            @ToolParam(description = "Unit, e.g. 'glasses/day', 'minutes/day', 'kg', 'hours/night', 'cal/day'") String unit) {
        notifier.notify("Setting health goal...");
        try {
            ensureDirExists();
            Path file = HEALTH_DIR.resolve("goals.txt");

            // Read existing goals and replace if metric exists
            List<String> lines = new ArrayList<>();
            if (Files.exists(file)) {
                lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }

            String goalLine = metric.trim().toLowerCase() + " | " + target + " | " + unit;
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase().startsWith(metric.trim().toLowerCase() + " |")) {
                    lines.set(i, goalLine);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(goalLine);
            }

            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return "Health goal set: " + metric + " = " + target + " " + unit + ".";
        } catch (IOException e) {
            return "Failed to set goal: " + e.getMessage();
        }
    }

    @Tool(description = "List all health goals with current progress. "
            + "Use when the user asks 'what are my health goals?', 'how am I doing on my goals?'.")
    public String getHealthGoals() {
        notifier.notify("Loading health goals...");
        try {
            ensureDirExists();
            Path file = HEALTH_DIR.resolve("goals.txt");
            if (!Files.exists(file)) {
                return "No health goals set yet. Use setHealthGoal to create one.";
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return "No health goals set yet.";
            }

            String today = LocalDate.now().format(DATE_FMT);
            StringBuilder sb = new StringBuilder();
            sb.append("Health Goals\n");
            sb.append("═══════════════════════════════\n\n");

            for (String line : lines) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 3) continue;
                String metric = parts[0].trim();
                String target = parts[1].trim();
                String unit = parts[2].trim();

                String current = getTrendValue(metric, today);
                sb.append("Goal: ").append(metric).append(" = ").append(target).append(" ").append(unit).append("\n");
                sb.append("  Today: ").append(current != null ? current : "no data").append("\n\n");
            }

            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to get goals: " + e.getMessage();
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private void ensureDirExists() throws IOException {
        if (!Files.exists(HEALTH_DIR)) {
            Files.createDirectories(HEALTH_DIR);
        }
    }

    private void appendToFile(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }

    private String safe(String s) {
        return (s == null || s.isBlank()) ? "-" : s.trim();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private String readFileContent(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).trim();
    }

    private int countWaterForFile(Path file) throws IOException {
        int total = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            // Lines look like: [HH:mm] 3 glass(es)
            try {
                String afterBracket = line.substring(line.indexOf(']') + 2).trim();
                String numStr = afterBracket.split("\\s+")[0];
                total += Integer.parseInt(numStr);
            } catch (Exception ignored) {
                // skip malformed lines
            }
        }
        return total;
    }

    private int countCalories(String content) {
        int total = 0;
        for (String line : content.split("\n")) {
            // Lines look like: [HH:mm] description | 450 cal
            try {
                int calIdx = line.lastIndexOf("| ");
                if (calIdx >= 0) {
                    String calPart = line.substring(calIdx + 2).trim();
                    String numStr = calPart.replace("cal", "").trim();
                    total += Integer.parseInt(numStr);
                }
            } catch (Exception ignored) {
                // skip malformed lines
            }
        }
        return total;
    }

    private String findEntriesForDate(Path file, String date) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.contains("[" + date + "]")) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String getTrendValue(String metric, String dateStr) {
        try {
            switch (metric.toLowerCase()) {
                case "water": {
                    Path file = HEALTH_DIR.resolve("water_" + dateStr + ".txt");
                    if (!Files.exists(file)) return null;
                    return countWaterForFile(file) + " glasses";
                }
                case "mood": {
                    Path file = HEALTH_DIR.resolve("mood_" + dateStr + ".txt");
                    if (!Files.exists(file)) return null;
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    if (lines.isEmpty()) return null;
                    // Average mood for the day
                    int sum = 0, count = 0;
                    for (String line : lines) {
                        try {
                            String afterBracket = line.substring(line.indexOf(']') + 2).trim();
                            String numStr = afterBracket.split("/")[0];
                            sum += Integer.parseInt(numStr);
                            count++;
                        } catch (Exception ignored) {}
                    }
                    return count > 0 ? String.format("%.1f/10", (double) sum / count) : null;
                }
                case "sleep": {
                    Path file = HEALTH_DIR.resolve("sleep_log.txt");
                    if (!Files.exists(file)) return null;
                    String entry = findEntriesForDate(file, dateStr);
                    if (entry.isEmpty()) return null;
                    // Extract hours from first entry
                    try {
                        String afterBracket = entry.split("\n")[0];
                        afterBracket = afterBracket.substring(afterBracket.indexOf(']') + 2).trim();
                        return afterBracket;
                    } catch (Exception e) {
                        return entry.split("\n")[0];
                    }
                }
                case "weight": {
                    Path file = HEALTH_DIR.resolve("weight_log.txt");
                    if (!Files.exists(file)) return null;
                    String entry = findEntriesForDate(file, dateStr);
                    if (entry.isEmpty()) return null;
                    try {
                        String afterBracket = entry.split("\n")[0];
                        afterBracket = afterBracket.substring(afterBracket.indexOf(']') + 2).trim();
                        return afterBracket;
                    } catch (Exception e) {
                        return entry.split("\n")[0];
                    }
                }
                case "exercise": {
                    Path file = HEALTH_DIR.resolve("exercise_" + dateStr + ".txt");
                    if (!Files.exists(file)) return null;
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    int totalMin = 0;
                    List<String> types = new ArrayList<>();
                    for (String line : lines) {
                        try {
                            String afterBracket = line.substring(line.indexOf(']') + 2).trim();
                            String[] parts = afterBracket.split("\\|");
                            types.add(parts[0].trim());
                            String minPart = parts[1].trim().replace("min", "").trim();
                            totalMin += Integer.parseInt(minPart);
                        } catch (Exception ignored) {}
                    }
                    return totalMin + " min (" + String.join(", ", types) + ")";
                }
                default:
                    return null;
            }
        } catch (IOException e) {
            return null;
        }
    }
}
