package com.minsbot.skills.recurringtask;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.agent.AsyncMessageService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Recurring AI-driven tasks persisted as Markdown files in
 * {@code ~/mins_bot_data/mins_recurring_tasks/}.
 * Each task re-runs on a Spring cron expression (supports "every 8pm", "every Mon 9am", etc.)
 * and survives app restarts.
 *
 * <p>File format (human-readable markdown):
 * <pre>
 * # &lt;label&gt;
 *
 * id: rt_abc1234
 * cron: 0 0 20 * * *
 * enabled: true
 * createdAt: 2026-04-18 21:16:00
 * lastFiredAt: 2026-04-18 21:16:00
 *
 * ## Prompt
 * Give me a short encouraging quote to end the day.
 * </pre>
 *
 * <p>Short one-line prompts may also be written inline as
 * {@code prompt: ...} in the frontmatter. The {@code ## Prompt} body wins if both are present.
 */
@Service
public class RecurringTaskService {

    private static final Logger log = LoggerFactory.getLogger(RecurringTaskService.class);
    /** Canonical location — shared with {@link com.minsbot.agent.RecurringTasksService}. */
    private static final Path DIR = Path.of(System.getProperty("user.home"),
            "mins_bot_data", "mins_recurring_tasks");
    /** Legacy folder we auto-migrate .json files out of on startup. */
    private static final Path LEGACY_DIR = Path.of(System.getProperty("user.home"),
            "mins_bot_data", "recurring_tasks");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper = new ObjectMapper();
    private final AsyncMessageService asyncMessages;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "recurring-task");
        t.setDaemon(true);
        return t;
    });

    /** id → active scheduled future (so we can cancel on delete/disable). */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private ChatClient chatClient;

    public RecurringTaskService(AsyncMessageService asyncMessages) {
        this.asyncMessages = asyncMessages;
    }

    @PostConstruct
    public void loadAll() {
        try {
            Files.createDirectories(DIR);
            migrateLegacyJsonIfAny();
            renameRandomIdsToHumanSlugs();
            try (var stream = Files.list(DIR)) {
                stream.filter(p -> p.toString().endsWith(".md")).forEach(this::loadAndSchedule);
            }
            log.info("[RecurringTask] Loaded {} task(s) from {}", activeFutures.size(), DIR);
        } catch (IOException e) {
            log.warn("[RecurringTask] Could not load tasks: {}", e.getMessage());
        }
    }

    /**
     * One-time migration: rename any {@code rt_<hex>.md} files to human-readable slugs
     * like {@code every-10-minutes-motivational-quote.md}. Rewrites the file so the
     * {@code id} field inside matches the new filename.
     */
    private void renameRandomIdsToHumanSlugs() {
        try (var stream = Files.list(DIR)) {
            stream.filter(p -> p.getFileName().toString().matches("rt_[a-f0-9]+\\.md"))
                  .forEach(oldPath -> {
                      try {
                          RecurringTask t = parseMarkdown(oldPath);
                          if (t == null || t.cron == null || t.prompt == null) return;
                          String newId = buildHumanId(t.cron, t.label, t.prompt);
                          if (newId.equals(t.id)) return;
                          Path newPath = DIR.resolve(newId + ".md");
                          if (Files.exists(newPath)) return; // don't clobber
                          t.id = newId;
                          writeMarkdown(t, newPath);
                          Files.deleteIfExists(oldPath);
                          log.info("[RecurringTask] Renamed {} → {}", oldPath.getFileName(), newPath.getFileName());
                      } catch (Exception e) {
                          log.warn("[RecurringTask] Rename failed for {}: {}", oldPath.getFileName(), e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.debug("[RecurringTask] rename scan: {}", e.getMessage());
        }
    }

    /**
     * One-time migration: converts any {@code rt_*.json} in the legacy
     * {@code ~/mins_bot_data/recurring_tasks/} folder into {@code .md} files
     * in the unified {@link #DIR}. The legacy JSON is deleted after conversion.
     */
    private void migrateLegacyJsonIfAny() {
        if (!Files.isDirectory(LEGACY_DIR)) return;
        try (var stream = Files.list(LEGACY_DIR)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    RecurringTask t = mapper.readValue(p.toFile(), RecurringTask.class);
                    Path target = DIR.resolve(t.id + ".md");
                    if (!Files.exists(target)) {
                        writeMarkdown(t, target);
                        log.info("[RecurringTask] Migrated legacy JSON → {}", target.getFileName());
                    }
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    log.warn("[RecurringTask] Could not migrate {}: {}", p.getFileName(), e.getMessage());
                }
            });
            // Clean up the empty legacy directory if nothing else is in it.
            try (var check = Files.list(LEGACY_DIR)) {
                if (check.findAny().isEmpty()) {
                    Files.deleteIfExists(LEGACY_DIR);
                }
            }
        } catch (IOException e) {
            log.debug("[RecurringTask] Legacy scan: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * Create a new recurring task.
     * @param cron Spring cron expression (6-field: sec min hour day-of-month month day-of-week).
     *             Example: "0 0 20 * * *" = every day at 20:00. "0 30 9 * * MON-FRI" = weekdays 9:30am.
     * @param prompt what to ask the AI each time the task fires (e.g. "Give me a short encouraging quote")
     * @param label short human-readable description, e.g. "Daily encouraging quote at 8pm"
     */
    public String create(String cron, String prompt, String label) throws IOException {
        if (cron == null || cron.isBlank()) throw new IllegalArgumentException("cron is required");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt is required");
        // Validate cron
        CronExpression.parse(cron.trim());

        RecurringTask task = new RecurringTask();
        task.cron = cron.trim();
        task.prompt = prompt.trim();
        task.label = (label != null && !label.isBlank()) ? label.trim() : prompt.trim();
        task.enabled = true;
        task.createdAt = LocalDateTime.now().format(FMT);
        task.id = buildHumanId(task.cron, task.label, task.prompt);
        save(task);
        scheduleNext(task);
        return task.id;
    }

    /**
     * Build a human-readable, filesystem-safe task id from the cron expression and label.
     * e.g. cron="0 0/10 * * * *" label="Motivational quote"  →  "every-10-minutes-motivational-quote"
     *      cron="0 0 9 * * *" label="Check email"            →  "daily-9am-check-email"
     *      cron="0 0 9 * * MON" label="Budget review"        →  "weekly-mon-9am-budget-review"
     * Collisions are resolved by appending -2, -3, etc.
     */
    private String buildHumanId(String cron, String label, String prompt) {
        String cronSlug = slugifyCron(cron);
        String textSlug = slugifyText(label != null && !label.isBlank() ? label : prompt);
        String base = (cronSlug.isEmpty() ? "task" : cronSlug)
                + (textSlug.isEmpty() ? "" : "-" + textSlug);
        // Cap length so filenames stay sane
        if (base.length() > 80) base = base.substring(0, 80).replaceAll("-+$", "");

        // Ensure uniqueness
        String candidate = base;
        int n = 2;
        while (Files.exists(DIR.resolve(candidate + ".md"))) {
            candidate = base + "-" + n++;
            if (n > 99) { candidate = base + "-" + UUID.randomUUID().toString().substring(0, 4); break; }
        }
        return candidate;
    }

    /** Convert a 6-field cron expression to a short human slug. */
    static String slugifyCron(String cron) {
        if (cron == null) return "";
        String c = cron.trim();
        String[] parts = c.split("\\s+");
        if (parts.length < 6) return "task";
        String sec = parts[0], min = parts[1], hour = parts[2], dom = parts[3], mon = parts[4], dow = parts[5];

        // every-N-seconds: "0/N * * * * *"
        if (sec.startsWith("0/") && min.equals("*") && hour.equals("*")) {
            return "every-" + sec.substring(2) + "-seconds";
        }
        // every-N-minutes: "0 0/N * * * *"
        if (min.startsWith("0/") && hour.equals("*")) {
            return "every-" + min.substring(2) + "-minutes";
        }
        // every-minute: "0 * * * * *"
        if (min.equals("*") && hour.equals("*")) {
            return "every-minute";
        }
        // every-N-hours: "0 0 0/N * * *"
        if (hour.startsWith("0/")) {
            return "every-" + hour.substring(2) + "-hours";
        }
        // hourly on the hour: "0 0 * * * *"
        if (min.matches("\\d+") && hour.equals("*")) {
            return "hourly-at-" + min;
        }
        // weekly: day-of-week is a specific day
        if (dow.matches("(?i)(MON|TUE|WED|THU|FRI|SAT|SUN)")) {
            return "weekly-" + dow.toLowerCase() + "-" + formatHourMinute(hour, min);
        }
        // daily
        if (hour.matches("\\d+") && dom.equals("*") && mon.equals("*")) {
            return "daily-" + formatHourMinute(hour, min);
        }
        return "scheduled";
    }

    /** "9" + "0" → "9am"; "14" + "30" → "2-30pm". */
    private static String formatHourMinute(String hour, String min) {
        try {
            int h = Integer.parseInt(hour);
            int m = Integer.parseInt(min);
            String ampm = h < 12 ? "am" : "pm";
            int disp = h == 0 ? 12 : (h > 12 ? h - 12 : h);
            return m == 0 ? disp + ampm : disp + "-" + String.format("%02d", m) + ampm;
        } catch (NumberFormatException e) {
            return "at-" + hour + "-" + min;
        }
    }

    /** Slugify free text → lowercase, dashes between words, strip everything else, cap at 50 chars. */
    static String slugifyText(String s) {
        if (s == null) return "";
        // Strip "[Section] " prefix that createTaskFromEntry adds
        String t = s.trim();
        if (t.startsWith("[") && t.contains("] ")) t = t.substring(t.indexOf("] ") + 2);
        t = t.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (t.length() > 50) {
            t = t.substring(0, 50).replaceAll("-+$", "");
        }
        return t;
    }

    public List<RecurringTask> list() {
        List<RecurringTask> out = new ArrayList<>();
        try {
            if (!Files.isDirectory(DIR)) return out;
            try (var stream = Files.list(DIR)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                      .filter(p -> !p.getFileName().toString().equalsIgnoreCase("README.md"))
                      .forEach(p -> {
                          RecurringTask t = parseMarkdown(p);
                          if (t != null && t.cron != null && t.prompt != null) out.add(t);
                      });
            }
        } catch (IOException e) {
            log.warn("[RecurringTask] list failed: {}", e.getMessage());
        }
        return out;
    }

    public boolean delete(String id) {
        ScheduledFuture<?> f = activeFutures.remove(id);
        if (f != null) f.cancel(false);
        Path p = DIR.resolve(id + ".md");
        try { return Files.deleteIfExists(p); } catch (IOException e) { return false; }
    }

    public boolean setEnabled(String id, boolean enabled) throws IOException {
        RecurringTask t = read(id);
        if (t == null) return false;
        t.enabled = enabled;
        save(t);
        ScheduledFuture<?> f = activeFutures.remove(id);
        if (f != null) f.cancel(false);
        if (enabled) scheduleNext(t);
        return true;
    }

    // ─── Internals ───

    private void loadAndSchedule(Path path) {
        try {
            RecurringTask t = parseMarkdown(path);
            // Ignore files that aren't our format (e.g. interval-based tasks read by
            // the sibling com.minsbot.agent.RecurringTasksService). Our tasks must have
            // both a cron expression and a prompt to be schedulable here.
            if (t == null || t.cron == null || t.prompt == null) return;
            if (t.enabled) scheduleNext(t);
        } catch (Exception e) {
            log.warn("[RecurringTask] Failed to load {}: {}", path, e.getMessage());
        }
    }

    private void scheduleNext(RecurringTask task) {
        try {
            CronExpression expr = CronExpression.parse(task.cron);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next = expr.next(now);
            if (next == null) {
                log.warn("[RecurringTask] No next fire time for '{}' cron='{}'", task.label, task.cron);
                return;
            }
            long delayMs = java.time.Duration.between(now, next).toMillis();
            if (delayMs < 0) delayMs = 1000;

            ScheduledFuture<?> f = scheduler.schedule(() -> {
                fire(task);
                // Reschedule the next occurrence
                scheduleNext(task);
            }, delayMs, TimeUnit.MILLISECONDS);
            activeFutures.put(task.id, f);
            log.info("[RecurringTask] '{}' next fires {}", task.label, next);
        } catch (Exception e) {
            log.warn("[RecurringTask] scheduleNext failed for '{}': {}", task.label, e.getMessage());
        }
    }

    private void fire(RecurringTask task) {
        try {
            String content;
            if (chatClient != null) {
                content = chatClient.prompt()
                        .system("You are a concise assistant. Respond with ONLY the requested content — no preamble, no explanation, no surrounding quotes. 1–3 sentences max.")
                        .user(task.prompt)
                        .call()
                        .content();
            } else {
                content = task.prompt;  // fallback: post the prompt verbatim
            }
            if (content != null && !content.isBlank()) {
                asyncMessages.push("⏰ " + task.label + "\n\n" + content.strip());
                log.info("[RecurringTask] Fired '{}' → {}",
                        task.label, content.strip().substring(0, Math.min(60, content.strip().length())));
            }
            task.lastFiredAt = LocalDateTime.now().format(FMT);
            save(task);
        } catch (Exception e) {
            log.warn("[RecurringTask] fire '{}' failed: {}", task.label, e.getMessage());
        }
    }

    private RecurringTask read(String id) {
        Path p = DIR.resolve(id + ".md");
        if (!Files.isRegularFile(p)) return null;
        return parseMarkdown(p);
    }

    private synchronized void save(RecurringTask t) throws IOException {
        Files.createDirectories(DIR);
        Path p = DIR.resolve(t.id + ".md");
        writeMarkdown(t, p);
    }

    // ─── Markdown format (parse + write) ─────────────────────────────────────

    /**
     * Parse a task .md file. Recognized frontmatter keys:
     * {@code id, label, cron, enabled, createdAt, lastFiredAt, prompt}.
     * A multi-line prompt body under a {@code ## Prompt} heading wins over inline {@code prompt:}.
     */
    private RecurringTask parseMarkdown(Path path) {
        try {
            RecurringTask t = new RecurringTask();
            t.id = stripExt(path.getFileName().toString());
            t.enabled = true;

            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            StringBuilder promptBody = null;
            for (String raw : lines) {
                String line = raw;
                String trimmed = line.trim();
                if (promptBody != null) {
                    // We're inside a ## Prompt body — capture until another heading or EOF.
                    if (trimmed.startsWith("#") && !trimmed.startsWith("# ")) {
                        continue;
                    }
                    if (trimmed.startsWith("## ") || trimmed.startsWith("# ")) {
                        break;
                    }
                    promptBody.append(line).append("\n");
                    continue;
                }
                if (trimmed.isEmpty()) continue;
                if (trimmed.equalsIgnoreCase("## Prompt")) {
                    promptBody = new StringBuilder();
                    continue;
                }
                if (trimmed.startsWith("#")) {
                    // The top "# <label>" line becomes the label if we don't have one yet.
                    if (t.label == null && trimmed.startsWith("# ")) {
                        t.label = trimmed.substring(2).trim();
                    }
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon <= 0) continue;
                String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String val = trimmed.substring(colon + 1).trim();
                switch (key) {
                    case "id" -> t.id = val;
                    case "label" -> t.label = val;
                    case "cron" -> t.cron = val;
                    case "enabled" -> t.enabled = val.equalsIgnoreCase("true") || val.equals("1");
                    case "createdat", "created_at" -> t.createdAt = val;
                    case "lastfiredat", "last_fired_at" -> t.lastFiredAt = val;
                    case "prompt" -> { if (t.prompt == null) t.prompt = stripQuotes(val); }
                    default -> { /* ignore unknown keys — reserved for the sibling interval-based service */ }
                }
            }
            if (promptBody != null) {
                String body = promptBody.toString().trim();
                if (!body.isEmpty()) t.prompt = body;
            }
            if (t.label == null || t.label.isBlank()) t.label = t.prompt != null ? t.prompt : t.id;
            return t;
        } catch (Exception e) {
            log.debug("[RecurringTask] parseMarkdown skip {}: {}", path.getFileName(), e.getMessage());
            return null;
        }
    }

    /** Write a task as markdown with metadata frontmatter + prompt body. */
    private void writeMarkdown(RecurringTask t, Path target) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(t.label == null ? t.id : t.label).append("\n\n");
        sb.append("id: ").append(t.id).append("\n");
        if (t.label != null) sb.append("label: ").append(t.label).append("\n");
        sb.append("cron: ").append(t.cron == null ? "" : t.cron).append("\n");
        sb.append("enabled: ").append(t.enabled).append("\n");
        if (t.createdAt != null) sb.append("createdAt: ").append(t.createdAt).append("\n");
        if (t.lastFiredAt != null) sb.append("lastFiredAt: ").append(t.lastFiredAt).append("\n");
        sb.append("\n## Prompt\n");
        sb.append(t.prompt == null ? "" : t.prompt.trim()).append("\n");
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String stripExt(String fname) {
        int dot = fname.lastIndexOf('.');
        return dot > 0 ? fname.substring(0, dot) : fname;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\""))
                 || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * Convert "8pm", "20:00", "8:30pm", "09:15" style time strings into a Spring daily cron.
     * Returns a 6-field cron like "0 0 20 * * *".
     */
    public static String dailyCronFromTime(String timeOfDay) {
        if (timeOfDay == null) throw new IllegalArgumentException("timeOfDay is required");
        String s = timeOfDay.trim().toLowerCase().replaceAll("\\s+", "");
        boolean pm = s.endsWith("pm");
        boolean am = s.endsWith("am");
        if (pm || am) s = s.substring(0, s.length() - 2);
        int hour, minute = 0;
        if (s.contains(":")) {
            String[] parts = s.split(":");
            hour = Integer.parseInt(parts[0]);
            minute = Integer.parseInt(parts[1]);
        } else {
            hour = Integer.parseInt(s);
        }
        if (pm && hour < 12) hour += 12;
        if (am && hour == 12) hour = 0;
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("Invalid time: " + timeOfDay);
        }
        return "0 " + minute + " " + hour + " * * *";
    }

    // ─── DTO ───

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RecurringTask {
        public String id;
        public String label;
        public String cron;
        public String prompt;
        public boolean enabled = true;
        public String createdAt;
        public String lastFiredAt;
    }
}
