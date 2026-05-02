package com.minsbot.agent.tools;

import com.minsbot.skills.recurringtask.RecurringTaskService;
import com.minsbot.skills.recurringtask.RecurringTaskService.RecurringTask;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recurring checks + reminders are stored as individual Markdown files under
 * {@code ~/mins_bot_data/mins_recurring_tasks/} — one file per task, managed by
 * {@link RecurringTaskService}.
 *
 * <p>This class keeps the original {@code CronConfigTools} API shape (used by the
 * AI as tools and by {@link com.minsbot.TabsController}) but all reads/writes go
 * through {@link RecurringTaskService} so there is a single source of truth.</p>
 *
 * <p>On first start, any legacy {@code cron_config.txt} at the user's home is converted
 * to .md tasks and renamed to {@code cron_config.txt.migrated} so the migration runs once.</p>
 */
@Component
public class CronConfigTools {

    private static final Logger log = LoggerFactory.getLogger(CronConfigTools.class);

    /** Canonical section names we still surface to the AI/UI. */
    private static final List<String> KNOWN_SECTIONS = List.of(
            "Daily checks", "Weekly checks", "Reminders", "Other schedule");

    private static final Path LEGACY_CRON_FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "cron_config.txt");

    private final ToolExecutionNotifier notifier;

    @Autowired
    private RecurringTaskService taskService;

    public CronConfigTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @PostConstruct
    void migrateLegacyCronConfigIfPresent() {
        if (!Files.isRegularFile(LEGACY_CRON_FILE)) return;
        try {
            String content = Files.readString(LEGACY_CRON_FILE, StandardCharsets.UTF_8);
            String currentSection = null;
            int converted = 0;

            for (String raw : content.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("## ")) {
                    currentSection = line.substring(3).trim();
                    continue;
                }
                // Accept both "- entry" and bare "entry" under a section
                if (line.isEmpty() || line.startsWith("#") || line.equals("-")) continue;
                String entry = line.startsWith("- ") ? line.substring(2).trim() : line;
                if (entry.isEmpty() || currentSection == null) continue;

                try {
                    createTaskFromEntry(currentSection, entry);
                    converted++;
                } catch (Exception e) {
                    log.warn("[CronConfig] Could not migrate '{}' under '{}': {}",
                            entry, currentSection, e.getMessage());
                }
            }

            // Rename so we don't re-migrate on restart
            Path archived = LEGACY_CRON_FILE.resolveSibling("cron_config.txt.migrated");
            Files.move(LEGACY_CRON_FILE, archived, StandardCopyOption.REPLACE_EXISTING);
            log.info("[CronConfig] Migrated {} entries from cron_config.txt → mins_recurring_tasks/ (archived to {})",
                    converted, archived.getFileName());
        } catch (IOException e) {
            log.warn("[CronConfig] Migration failed: {}", e.getMessage());
        }
    }

    // ═══ Tools (AI-callable) ═══════════════════════════════════════════════

    @Tool(description = "Read the user's recurring scheduled checks/reminders. "
            + "Each task is a Markdown file under ~/mins_bot_data/mins_recurring_tasks/. "
            + "Use when the user asks what checks are scheduled, what reminders they have, or 'show my cron config'.")
    public String getCronConfig() {
        notifier.notify("Reading recurring tasks...");
        Map<String, List<String>> grouped = loadTasksGroupedBySection();
        if (grouped.values().stream().allMatch(List::isEmpty)) {
            return "No recurring tasks configured yet.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Recurring checks (from mins_recurring_tasks/)\n\n");
        for (String section : KNOWN_SECTIONS) {
            List<String> entries = grouped.getOrDefault(section, List.of());
            sb.append("## ").append(section).append("\n");
            if (entries.isEmpty()) {
                sb.append("-\n");
            } else {
                for (String e : entries) sb.append("- ").append(e).append("\n");
            }
        }
        // Anything we couldn't map into KNOWN_SECTIONS
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            if (KNOWN_SECTIONS.contains(entry.getKey())) continue;
            if (entry.getValue().isEmpty()) continue;
            sb.append("## ").append(entry.getKey()).append("\n");
            for (String e : entry.getValue()) sb.append("- ").append(e).append("\n");
        }
        return sb.toString().trim();
    }

    // @Tool removed for LLM scope — natural-language → cron regex guessing here is lossy
    // (it silently falls through to "noon daily" if the input doesn't match a pattern).
    // The LLM should use RemindersTools.createDailyReminder / createWeeklyReminder /
    // createCronReminder which take structured time/cron directly. Method retained for
    // the UI controller (TabsController) which already passes structured section + content.
    public String updateCronInfo(
            @ToolParam(description = "Section name, e.g. 'Daily checks', 'Weekly checks', 'Reminders', 'Other schedule'") String section,
            @ToolParam(description = "Description of the scheduled check or reminder") String content) {
        notifier.notify("Adding recurring task to " + section + "...");
        if (content == null || content.isBlank()) {
            return "Content is required. To remove a task, use removeCronEntry.";
        }
        try {
            String taskId = createTaskFromEntry(section != null ? section.trim() : "Other schedule", content.trim());
            return "Added recurring task (id=" + taskId + ") under '" + section + "': " + content;
        } catch (Exception e) {
            return "Failed to add recurring task: " + e.getMessage();
        }
    }

    /** Append a single entry under a section. Keeps backward-compat with ScheduledTaskTools callers. */
    public String appendCronEntry(String section, String entry) {
        try {
            String id = createTaskFromEntry(section, entry);
            return "Added recurring task " + id + " under '" + section + "'.";
        } catch (Exception e) {
            return "Failed to add recurring task: " + e.getMessage();
        }
    }

    /** Remove a task whose label or prompt contains the given substring. */
    public String removeCronEntry(String section, String entrySubstring) {
        if (entrySubstring == null || entrySubstring.isBlank()) {
            return "Substring is required.";
        }
        String needle = entrySubstring.trim().toLowerCase();
        List<RecurringTask> matches = new ArrayList<>();
        for (RecurringTask t : taskService.list()) {
            if (matchesSection(t, section)) {
                String hay = ((t.label == null ? "" : t.label) + " " + (t.prompt == null ? "" : t.prompt))
                        .toLowerCase();
                if (hay.contains(needle)) matches.add(t);
            }
        }
        if (matches.isEmpty()) return "No recurring task matched '" + entrySubstring + "' under " + section + ".";
        for (RecurringTask t : matches) {
            taskService.delete(t.id);
        }
        return "Removed " + matches.size() + " task(s) matching '" + entrySubstring + "'.";
    }

    // ═══ Internals ═════════════════════════════════════════════════════════

    /** Create a recurring task .md file from a natural-language entry under a section. */
    private String createTaskFromEntry(String section, String entry) throws IOException {
        String cron = guessCronFromEntry(section, entry);
        String label = "[" + section + "] " + entry;
        // Task's prompt is the entry text itself — when it fires, the AI will speak/respond to it.
        return taskService.create(cron, entry, label);
    }

    /**
     * Best-effort mapping from a section + free-text entry to a Spring cron expression.
     * Handles common phrasings: "every N min/hour", "at HH[:MM]am/pm", "every Monday", etc.
     */
    private String guessCronFromEntry(String section, String entry) {
        String s = entry == null ? "" : entry.toLowerCase();

        // "every N min" or "every N minutes"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("every\\s+(\\d+)\\s*min").matcher(s);
        if (m.find()) {
            int n = Math.max(1, Integer.parseInt(m.group(1)));
            return "0 0/" + n + " * * * *";
        }
        // "every N hour(s)"
        m = java.util.regex.Pattern.compile("every\\s+(\\d+)\\s*hour").matcher(s);
        if (m.find()) {
            int n = Math.max(1, Integer.parseInt(m.group(1)));
            return "0 0 0/" + n + " * * *";
        }
        // "every N sec" — treat as "every minute" since cron granularity is 1s min in Spring
        m = java.util.regex.Pattern.compile("every\\s+(\\d+)\\s*sec").matcher(s);
        if (m.find()) {
            int n = Math.max(1, Integer.parseInt(m.group(1)));
            return "0/" + Math.max(1, n) + " * * * * *";
        }
        // "at 8pm" / "at 09:30" / "at 4:00 PM"
        m = java.util.regex.Pattern.compile("at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").matcher(s);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            int minute = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            String ampm = m.group(3);
            if ("pm".equals(ampm) && hour < 12) hour += 12;
            if ("am".equals(ampm) && hour == 12) hour = 0;
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) hour = 9;
            return "0 " + minute + " " + hour + " * * " + dowForSection(section, s);
        }
        // "every Monday" / "every Friday" etc.
        String[] days = {"sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};
        String[] dow  = {"SUN",    "MON",    "TUE",     "WED",       "THU",      "FRI",    "SAT"};
        for (int i = 0; i < days.length; i++) {
            if (s.contains(days[i])) {
                return "0 0 9 * * " + dow[i];
            }
        }

        // Section-based defaults
        String sec = section == null ? "" : section.toLowerCase();
        if (sec.contains("daily")) return "0 0 9 * * *";        // 9am daily
        if (sec.contains("weekly")) return "0 0 9 * * MON";     // Monday 9am
        if (sec.contains("reminder")) return "0 0 * * * *";     // hourly
        return "0 0 12 * * *";                                  // default: noon daily
    }

    /** If the section is "Weekly checks" and no day appears in text, default to Monday. */
    private String dowForSection(String section, String lowerText) {
        if (section != null && section.toLowerCase().contains("weekly")) {
            return "MON";
        }
        return "*";
    }

    /** Reverse-map a recurring task to a likely section for display purposes. */
    private String sectionForTask(RecurringTask t) {
        // Prefer explicit label prefix from createTaskFromEntry: "[Section] ..."
        if (t.label != null && t.label.startsWith("[") && t.label.contains("]")) {
            int close = t.label.indexOf(']');
            String claimed = t.label.substring(1, close).trim();
            for (String s : KNOWN_SECTIONS) {
                if (s.equalsIgnoreCase(claimed)) return s;
            }
            if (!claimed.isEmpty()) return claimed;
        }
        // Heuristic from cron expression
        String c = t.cron == null ? "" : t.cron;
        if (c.matches("\\d+\\s+\\d+\\s+\\d+\\s+\\*\\s+\\*\\s+(MON|TUE|WED|THU|FRI|SAT|SUN)")) return "Weekly checks";
        if (c.matches("\\d+\\s+\\d+\\s+\\d+\\s+\\*\\s+\\*\\s+\\*")) return "Daily checks";
        if (c.matches("\\d+\\s+0/\\d+\\s+\\*.*")
                || c.matches("\\d+\\s+\\*\\s+\\*\\s+\\*\\s+\\*\\s+\\*")
                || c.matches("0\\s+\\*\\s+\\*\\s+\\*\\s+\\*\\s+\\*")) return "Reminders";
        return "Other schedule";
    }

    private boolean matchesSection(RecurringTask t, String section) {
        if (section == null || section.isBlank()) return true;
        return sectionForTask(t).equalsIgnoreCase(section.trim());
    }

    /** Group all recurring tasks by their best-guess section. */
    private Map<String, List<String>> loadTasksGroupedBySection() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String s : KNOWN_SECTIONS) out.put(s, new ArrayList<>());
        for (RecurringTask t : taskService.list()) {
            String section = sectionForTask(t);
            String label = t.label != null ? t.label : (t.prompt != null ? t.prompt : t.id);
            // Strip the "[Section] " prefix for display
            if (label.startsWith("[") && label.contains("] ")) {
                label = label.substring(label.indexOf("] ") + 2);
            }
            out.computeIfAbsent(section, k -> new ArrayList<>()).add(label);
        }
        return out;
    }
}
