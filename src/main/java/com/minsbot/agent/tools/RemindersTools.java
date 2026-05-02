package com.minsbot.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AI-callable reminder management. The bot creates / lists / pauses / deletes
 * reminder files; the existing {@link com.minsbot.agent.ScheduledReportService}
 * and {@link com.minsbot.skills.recurringtask.RecurringTaskService} pick up
 * those files within ~60 s and handle scheduling + delivery automatically.
 *
 * <p>The two flavors of reminders this tool can create:
 * <ul>
 *   <li><strong>Daily</strong> ({@code scheduled_reports/}) — fires once per
 *       day at a chosen time. Generated even when the user is AFK; delivery
 *       held in {@code pending_reports/} until the user is at the keyboard.
 *       No backfill of missed days.</li>
 *   <li><strong>Cron / weekly</strong> ({@code mins_recurring_tasks/}) — uses
 *       Spring cron expressions. Fires immediately when the time arrives
 *       (no held-delivery semantics).</li>
 * </ul>
 *
 * <p>Pattern the AI should follow: when the user says
 * <em>"help me [achieve goal]"</em> (e.g. "reduce weight", "sleep better",
 * "learn Spanish"), spawn a coordinated set of 2–4 reminders — a daily
 * check-in or two, plus a weekly progress review. Each reminder's prompt
 * should be specific enough that future-you can act on it without context.
 */
@Component
public class RemindersTools {

    private static final Path SCHEDULED_REPORTS_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "scheduled_reports");
    private static final Path RECURRING_TASKS_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "mins_recurring_tasks");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolExecutionNotifier notifier;

    public RemindersTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    // ─── Create ─────────────────────────────────────────────────────────────

    @Tool(description = "CANONICAL way to create a DAILY recurring reminder. Persistent (file-backed, "
            + "survives restart), delivered in chat. Use whenever the user says 'remind me every day at "
            + "<time> to <thing>', 'every morning at 7 do X', 'each evening ask me Y'. Also pair with "
            + "createWeeklyReminder when the user says 'help me <goal>' (e.g. lose weight, sleep better) — "
            + "spawn 1-2 daily check-ins plus a weekly progress review. The bot generates fresh text via AI "
            + "at fire time from the prompt you provide; delivery is held until the user is at the keyboard. "
            + "Prefer this over TimerTools (one-shot OS popup, lost on restart), CronConfigTools, or any "
            + "*Recurring* / *Scheduled* tool.")
    public String createDailyReminder(
            @ToolParam(description = "Short human-readable name, e.g. 'Morning weight log'") String name,
            @ToolParam(description = "Time of day in 24-hour HH:MM format, e.g. '07:00' or '21:30'") String time,
            @ToolParam(description = "Prompt sent to the AI at fire time. Be specific — what should the AI ask, prompt, or report? E.g. 'Ask me what I weighed this morning and log the number to my health profile.'") String prompt) {
        notifier.notify("Creating daily reminder: " + name);
        try {
            LocalTime t = parseTime(time);
            if (t == null) return "Invalid time '" + time + "'. Use HH:MM in 24-hour format (e.g. '07:00', '21:30').";
            if (name == null || name.isBlank()) return "Reminder name is required.";
            if (prompt == null || prompt.isBlank()) return "Prompt is required.";

            String slug = toSlug(name);
            Path file = SCHEDULED_REPORTS_DIR.resolve(slug + ".md");
            Files.createDirectories(SCHEDULED_REPORTS_DIR);
            String content = "# " + name.trim() + "\n\n"
                    + "time: " + t.format(DateTimeFormatter.ofPattern("HH:mm")) + "\n"
                    + "enabled: true\n"
                    + "prompt: " + escapeForFrontmatter(prompt) + "\n\n"
                    + "_Auto-created via createDailyReminder on " + LocalDateTime.now().format(TS) + "._\n";
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return "Created daily reminder '" + name + "' at " + t + ". File: " + file
                    + ". The bot will pick it up within 60s and start firing tomorrow at " + t
                    + " (or today if " + t + " hasn't passed yet). Delivery is held until you're at the keyboard.";
        } catch (IOException e) {
            return "Failed to create reminder: " + e.getMessage();
        }
    }

    @Tool(description = "CANONICAL way to create a WEEKLY recurring reminder. Persistent (file-backed, "
            + "survives restart). Use whenever the user says 'every Monday at 9am do X', 'every Friday "
            + "review my week', 'each Sunday plan the week'. Pair with createDailyReminder for goal "
            + "tracking (daily logging + weekly review). Prefer this over CronConfigTools or any "
            + "*Recurring* / *Scheduled* tool.")
    public String createWeeklyReminder(
            @ToolParam(description = "Short human-readable name, e.g. 'Weekly weight progress'") String name,
            @ToolParam(description = "Day of week — one of: MON, TUE, WED, THU, FRI, SAT, SUN") String dayOfWeek,
            @ToolParam(description = "Time of day in 24-hour HH:MM format, e.g. '08:00'") String time,
            @ToolParam(description = "Prompt sent to the AI at fire time. Make it useful — what should the AI report, ask, or summarize?") String prompt) {
        notifier.notify("Creating weekly reminder: " + name);
        String dow = normalizeDayOfWeek(dayOfWeek);
        if (dow == null) return "Invalid day '" + dayOfWeek + "'. Use one of: MON, TUE, WED, THU, FRI, SAT, SUN.";
        LocalTime t = parseTime(time);
        if (t == null) return "Invalid time '" + time + "'. Use HH:MM in 24-hour format.";
        String cron = "0 " + t.getMinute() + " " + t.getHour() + " * * " + dow;
        return createCronReminderInternal(name, cron, prompt,
                "weekly on " + dow + " at " + t);
    }

    @Tool(description = "CANONICAL way to create a recurring reminder with a custom cron schedule. "
            + "Persistent (file-backed, survives restart). Use ONLY when createDailyReminder / "
            + "createWeeklyReminder aren't expressive enough — weekdays only, every N hours during a window, "
            + "first of the month, etc. Format: '<sec> <min> <hour> <day> <month> <dow>'. "
            + "Examples: '0 0 9 * * MON-FRI' = weekdays 9 AM. '0 0 */2 9-17 * MON-FRI' = every 2 hours, "
            + "9am–5pm, weekdays. Prefer this over any *Recurring* / *Scheduled* / cron tool.")
    public String createCronReminder(
            @ToolParam(description = "Short human-readable name") String name,
            @ToolParam(description = "Spring cron expression (6 fields)") String cron,
            @ToolParam(description = "Prompt sent to the AI at fire time") String prompt) {
        notifier.notify("Creating cron reminder: " + name);
        if (cron == null || cron.isBlank()) return "Cron expression is required.";
        return createCronReminderInternal(name, cron.trim(), prompt, "cron " + cron);
    }

    private String createCronReminderInternal(String name, String cron, String prompt, String humanCadence) {
        try {
            if (name == null || name.isBlank()) return "Reminder name is required.";
            if (prompt == null || prompt.isBlank()) return "Prompt is required.";
            // Validate cron — fail early with a useful message
            try { org.springframework.scheduling.support.CronExpression.parse(cron); }
            catch (Exception e) { return "Invalid cron expression '" + cron + "': " + e.getMessage(); }

            String slug = toSlug(name);
            Path file = RECURRING_TASKS_DIR.resolve(slug + ".md");
            Files.createDirectories(RECURRING_TASKS_DIR);
            String content = "# " + name.trim() + "\n\n"
                    + "id: " + slug + "\n"
                    + "label: " + name.trim() + "\n"
                    + "cron: " + cron + "\n"
                    + "enabled: true\n"
                    + "createdAt: " + LocalDateTime.now().format(TS) + "\n\n"
                    + "## Prompt\n"
                    + prompt.trim() + "\n";
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return "Created reminder '" + name + "' (" + humanCadence + "). File: " + file
                    + ". The bot picks it up within 60s and schedules the next firing.";
        } catch (IOException e) {
            return "Failed to create reminder: " + e.getMessage();
        }
    }

    // ─── List / inspect ─────────────────────────────────────────────────────

    @Tool(description = "CANONICAL list of all reminders — daily (scheduled_reports/) AND cron/weekly "
            + "(mins_recurring_tasks/). Use whenever the user asks 'what reminders do I have', 'show my "
            + "scheduled tasks', 'list my recurring tasks'. Also call before creating a new reminder to "
            + "avoid duplicates. Prefer this over any *Recurring* / *Scheduled* / *Cron* list tool.")
    public String listReminders() {
        notifier.notify("Listing reminders...");
        StringBuilder sb = new StringBuilder();
        sb.append("Reminders:\n\n");

        List<String> daily = listFolder(SCHEDULED_REPORTS_DIR, "daily");
        sb.append("Daily (").append(daily.size()).append("):\n");
        if (daily.isEmpty()) sb.append("  (none)\n");
        else daily.forEach(line -> sb.append("  ").append(line).append("\n"));

        sb.append("\n");

        List<String> recurring = listFolder(RECURRING_TASKS_DIR, "cron");
        sb.append("Cron / weekly (").append(recurring.size()).append("):\n");
        if (recurring.isEmpty()) sb.append("  (none)\n");
        else recurring.forEach(line -> sb.append("  ").append(line).append("\n"));

        return sb.toString();
    }

    @Tool(description = "Show the full content of a single reminder file by name (or slug). "
            + "Use to review a reminder before editing it.")
    public String getReminder(
            @ToolParam(description = "Reminder name or slug") String name) {
        Path file = locateReminder(name);
        if (file == null) return "Reminder '" + name + "' not found. Use listReminders to see what exists.";
        try {
            return "=== " + file.getFileName() + " ===\n\n"
                    + Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Failed to read reminder: " + e.getMessage();
        }
    }

    // ─── Pause / resume / delete ────────────────────────────────────────────

    @Tool(description = "Pause a reminder without deleting it. Flips enabled: true → false in the file. "
            + "Use when the user says 'turn off' or 'pause' a reminder. Re-enable with resumeReminder.")
    public String pauseReminder(@ToolParam(description = "Reminder name or slug") String name) {
        return setEnabled(name, false);
    }

    @Tool(description = "Resume a paused reminder. Flips enabled: false → true in the file.")
    public String resumeReminder(@ToolParam(description = "Reminder name or slug") String name) {
        return setEnabled(name, true);
    }

    @Tool(description = "Permanently delete a reminder. Use when the user says 'delete', 'remove', "
            + "'get rid of' a reminder. Prefer pauseReminder if the user might want it back.")
    public String deleteReminder(@ToolParam(description = "Reminder name or slug") String name) {
        notifier.notify("Deleting reminder: " + name);
        Path file = locateReminder(name);
        if (file == null) return "Reminder '" + name + "' not found.";
        try {
            Files.delete(file);
            return "Deleted reminder '" + name + "'. File: " + file
                    + ". The scheduler will drop it within 60s.";
        } catch (IOException e) {
            return "Failed to delete: " + e.getMessage();
        }
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    private String setEnabled(String name, boolean enabled) {
        notifier.notify((enabled ? "Resuming" : "Pausing") + " reminder: " + name);
        Path file = locateReminder(name);
        if (file == null) return "Reminder '" + name + "' not found.";
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String updated = content.replaceAll(
                    "(?im)^enabled:\\s*(true|false)\\b",
                    "enabled: " + enabled);
            if (updated.equals(content)) {
                // No enabled: line found — append one
                updated = content + "\nenabled: " + enabled + "\n";
            }
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return (enabled ? "Resumed" : "Paused") + " reminder '" + name + "'. The scheduler picks up the change within 60s.";
        } catch (IOException e) {
            return "Failed to update reminder: " + e.getMessage();
        }
    }

    /** Find a reminder file by name or slug across both folders. */
    private Path locateReminder(String name) {
        if (name == null || name.isBlank()) return null;
        String slug = toSlug(name);
        for (Path dir : new Path[]{SCHEDULED_REPORTS_DIR, RECURRING_TASKS_DIR}) {
            Path exact = dir.resolve(slug + ".md");
            if (Files.isRegularFile(exact)) return exact;
            // Also try matching by raw name (in case slug differs from a manual rename)
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
                for (Path p : stream) {
                    String fname = p.getFileName().toString();
                    if (fname.equalsIgnoreCase(slug + ".md") || fname.equalsIgnoreCase(name + ".md")) {
                        return p;
                    }
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    /** One-line summary per file: name + cadence + enabled flag. */
    private List<String> listFolder(Path dir, String kind) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path file : stream) {
                String fname = file.getFileName().toString();
                if (fname.equalsIgnoreCase("README.md")) continue;
                Map<String, String> meta = parseFrontmatter(file);
                String slug = fname.substring(0, fname.length() - 3);
                String name = meta.getOrDefault("name", meta.getOrDefault("label", slug));
                String enabled = meta.getOrDefault("enabled", "true");
                String cadence;
                if ("daily".equals(kind)) {
                    cadence = "daily " + meta.getOrDefault("time", "?");
                } else {
                    cadence = "cron " + meta.getOrDefault("cron", "?");
                }
                String dot = "true".equalsIgnoreCase(enabled) ? "●" : "○";
                out.add(dot + " " + name + " (" + cadence + ", slug=" + slug + ")");
            }
        } catch (IOException ignored) {}
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private Map<String, String> parseFrontmatter(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("##")) continue;
                int colon = t.indexOf(':');
                if (colon <= 0) continue;
                String key = t.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String val = t.substring(colon + 1).trim();
                out.putIfAbsent(key, val);
            }
        } catch (IOException ignored) {}
        return out;
    }

    private static String toSlug(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static LocalTime parseTime(String s) {
        if (s == null) return null;
        try {
            String t = s.trim();
            // Handle 'H:MM' as well as 'HH:MM'
            if (t.matches("\\d:\\d{2}")) t = "0" + t;
            return LocalTime.parse(t);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeDayOfWeek(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(Locale.ROOT);
        // Accept full names too (Monday → MON)
        if (t.length() > 3) t = t.substring(0, 3);
        return switch (t) {
            case "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN" -> t;
            default -> null;
        };
    }

    /** Wrap a single-line prompt in quotes if it contains characters that would
        confuse the simple frontmatter parser. */
    private static String escapeForFrontmatter(String s) {
        String trimmed = s.trim();
        if (trimmed.contains("\n")) {
            // Multi-line prompts go in a body block — but for daily reminders we
            // want it inline. Compress newlines to spaces.
            trimmed = trimmed.replaceAll("\\s+", " ");
        }
        // Always wrap in double quotes — predictable, the parser strips them.
        return "\"" + trimmed.replace("\"", "\\\"") + "\"";
    }
}
