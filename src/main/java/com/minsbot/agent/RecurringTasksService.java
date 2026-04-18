package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * File-driven recurring task scheduler. Reads task definition files from
 * {@code ~/mins_bot_data/mins_recurring_tasks/*.md} and schedules each one
 * on its own interval.
 *
 * <p><strong>File format:</strong> Markdown with simple key:value lines at the top:
 * <pre>
 * # Reindex Skills
 *
 * action: reindex_skills
 * intervalMinutes: 5
 * enabled: true
 *
 * Optional human-readable description below the metadata.
 * </pre>
 *
 * <p>Other services <strong>register actions</strong> via {@link #registerAction(String, Runnable)}.
 * The scheduler then dispatches to the right action by name.
 *
 * <p>The folder itself is rescanned every minute so adding/removing/editing task
 * files takes effect without restarting.
 */
@Service
public class RecurringTasksService {

    private static final Logger log = LoggerFactory.getLogger(RecurringTasksService.class);

    public static final Path TASKS_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "mins_recurring_tasks");

    /** Action name → Runnable. Services register here in their @PostConstruct. */
    private final Map<String, Runnable> actionRegistry = new ConcurrentHashMap<>();

    /** Currently-scheduled tasks by file name → scheduled future, so we can cancel on file removal. */
    private final Map<String, ScheduledTask> scheduled = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "mins-recurring");
                t.setDaemon(true);
                return t;
            });

    private record ScheduledTask(TaskDef def, FileTime mtime, ScheduledFuture<?> future) {}

    public record TaskDef(String fileName, String name, String action,
                          long intervalMinutes, boolean enabled) {}

    @PostConstruct
    public void init() {
        try { Files.createDirectories(TASKS_DIR); } catch (IOException ignored) {}
        // Initial scan happens 5s after startup so action registrations from other beans land first.
        scheduler.schedule(this::rescan, 5, TimeUnit.SECONDS);
        // Then periodic rescans every minute pick up file adds/edits/removes.
        scheduler.scheduleWithFixedDelay(this::rescan, 60, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * Register a named action. Task files reference this name in their {@code action:} field.
     * Safe to call any time; if a task file already references this name, the next rescan will pick it up.
     */
    public void registerAction(String name, Runnable runnable) {
        if (name == null || runnable == null) return;
        actionRegistry.put(name, runnable);
        log.info("[RecurringTasks] Registered action '{}'", name);
    }

    public Map<String, TaskDef> listScheduled() {
        Map<String, TaskDef> out = new LinkedHashMap<>();
        scheduled.forEach((k, v) -> out.put(k, v.def()));
        return out;
    }

    /** Rescans {@link #TASKS_DIR}. Adds new tasks, updates changed ones, cancels removed ones. */
    public void rescan() {
        if (!Files.isDirectory(TASKS_DIR)) {
            log.info("[RecurringTasks] Scan: folder missing ({}), 0 task(s) scheduled", TASKS_DIR);
            return;
        }
        long t0 = System.currentTimeMillis();
        int added = 0, updated = 0, disabled = 0, unknownAction = 0, parseFails = 0, unchanged = 0;
        Set<String> seen = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(TASKS_DIR, "*.md")) {
            for (Path file : stream) {
                String fname = file.getFileName().toString();
                if (fname.equalsIgnoreCase("README.md")) continue;
                seen.add(fname);
                try {
                    FileTime mtime = Files.getLastModifiedTime(file);
                    ScheduledTask existing = scheduled.get(fname);
                    if (existing != null && existing.mtime().equals(mtime)) {
                        unchanged++;
                        continue;
                    }
                    TaskDef def = parseTaskFile(fname, file);
                    if (def == null) { parseFails++; continue; }
                    // Cancel old version if any
                    if (existing != null) existing.future().cancel(false);
                    if (!def.enabled()) {
                        scheduled.remove(fname);
                        disabled++;
                        log.info("[RecurringTasks] '{}' is disabled — skipping", fname);
                        continue;
                    }
                    Runnable action = actionRegistry.get(def.action());
                    if (action == null) {
                        unknownAction++;
                        log.warn("[RecurringTasks] '{}' references unknown action '{}' — known: {}",
                                fname, def.action(), actionRegistry.keySet());
                        continue;
                    }
                    long delaySec = Math.max(1, def.intervalMinutes() * 60);
                    ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                            () -> safelyRun(def, action),
                            delaySec, delaySec, TimeUnit.SECONDS);
                    scheduled.put(fname, new ScheduledTask(def, mtime, future));
                    if (existing == null) added++; else updated++;
                    log.info("[RecurringTasks] Scheduled '{}' → action '{}' every {} minute(s)",
                            def.name(), def.action(), def.intervalMinutes());
                } catch (IOException e) {
                    parseFails++;
                    log.warn("[RecurringTasks] Failed to read {}: {}", fname, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[RecurringTasks] Failed to scan {}: {}", TASKS_DIR, e.getMessage());
        }

        // Cancel tasks whose file was removed
        int removed = 0;
        for (String name : scheduled.keySet().stream().filter(n -> !seen.contains(n)).toList()) {
            ScheduledTask st = scheduled.remove(name);
            if (st != null) {
                st.future().cancel(false);
                removed++;
                log.info("[RecurringTasks] Removed '{}' (file deleted)", name);
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[RecurringTasks] Scan complete in {}ms — {} active, {} unchanged, "
                        + "{} added, {} updated, {} removed, {} disabled, {} unknown-action, {} parse-fail",
                elapsed, scheduled.size(), unchanged, added, updated, removed, disabled, unknownAction, parseFails);
    }

    private void safelyRun(TaskDef def, Runnable action) {
        try {
            log.debug("[RecurringTasks] Running '{}' (action={})", def.name(), def.action());
            action.run();
        } catch (Exception e) {
            log.warn("[RecurringTasks] Task '{}' threw: {}", def.name(), e.getMessage());
        }
    }

    /** Parse the simple key:value frontmatter at the top of a task .md file. */
    private TaskDef parseTaskFile(String fname, Path file) throws IOException {
        String name = fname.endsWith(".md") ? fname.substring(0, fname.length() - 3) : fname;
        String action = null;
        long intervalMinutes = 0;
        boolean enabled = true;

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int colon = t.indexOf(':');
            if (colon <= 0) continue;
            String key = t.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val = t.substring(colon + 1).trim();
            switch (key) {
                case "name" -> name = val;
                case "action" -> action = val;
                case "intervalminutes", "interval_minutes", "interval" -> {
                    try { intervalMinutes = Long.parseLong(val.replaceAll("[^0-9]", "")); }
                    catch (NumberFormatException ignored) {}
                }
                case "enabled" -> enabled = val.equalsIgnoreCase("true") || val.equals("1");
                default -> { /* ignore */ }
            }
        }

        if (action == null || intervalMinutes <= 0) {
            log.warn("[RecurringTasks] Skipping '{}' — missing action or intervalMinutes", fname);
            return null;
        }
        return new TaskDef(fname, name, action, intervalMinutes, enabled);
    }
}
