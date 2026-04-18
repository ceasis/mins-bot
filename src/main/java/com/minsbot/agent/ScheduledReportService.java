package com.minsbot.agent;

import com.minsbot.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.MouseInfo;
import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Daily scheduled reports with <strong>guaranteed delivery</strong>.
 *
 * <p>Reports are defined as Markdown files in
 * {@code ~/mins_bot_data/scheduled_reports/}. Each file specifies a
 * time-of-day plus either an AI {@code prompt:} or a registered Java
 * {@code action:} that produces the report body.
 *
 * <p><strong>Generate-and-queue model:</strong>
 * <ol>
 *   <li>Every minute, check each active task: has today's scheduled time passed,
 *       and have we NOT fired today? If yes, generate the report and persist it
 *       to {@code ~/mins_bot_data/pending_reports/}.</li>
 *   <li>Every 30 s, check pending reports. If the user is currently <em>active</em>
 *       (mouse moved within the last 60 s), push them into the chat via
 *       {@link AsyncMessageService} and archive to {@code ~/mins_bot_data/delivered_reports/}.</li>
 * </ol>
 *
 * <p><strong>No backfill:</strong> last-fired state is a single date per task. Skipping
 * 60 days yields exactly one report on the next firing day, never sixty.
 *
 * <p><strong>Survives restarts:</strong> last-fired date and pending reports are both
 * file-backed. A 4 AM firing missed because the bot was off is generated immediately
 * the next time the bot starts (assuming today's 4 AM has passed and we haven't
 * fired today yet).
 */
@Service
public class ScheduledReportService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledReportService.class);

    public static final Path REPORTS_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "scheduled_reports");
    public static final Path PENDING_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "pending_reports");
    public static final Path DELIVERED_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "delivered_reports");
    public static final Path STATE_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "scheduled_reports_state");

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** AI inference timeout — one shot, no retries. */
    private static final long AI_TIMEOUT_SECONDS = 90;

    /** Active window for "user is at the keyboard" — mouse must have moved within this. */
    private static final long USER_ACTIVE_WINDOW_MS = 60_000;

    private final AsyncMessageService asyncMessages;
    private final ChatService chatService;

    /** Action name → supplier of the report body. Other services register here. */
    private final Map<String, Supplier<String>> reportActions = new ConcurrentHashMap<>();

    /** Currently parsed task definitions from {@link #REPORTS_DIR}. */
    private final Map<String, ReportTask> activeTasks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "scheduled-reports");
                t.setDaemon(true);
                return t;
            });
    private final ExecutorService aiExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "scheduled-reports-ai");
                t.setDaemon(true);
                return t;
            });

    /** Last observed mouse position + the wall-clock ms it was last seen to change. */
    private volatile Point lastMousePos = null;
    private volatile long lastMouseMoveMs = 0;

    public record ReportTask(String fileName, String name, LocalTime time,
                             String prompt, String action, boolean enabled) {}

    public ScheduledReportService(AsyncMessageService asyncMessages,
                                  @Lazy ChatService chatService) {
        this.asyncMessages = asyncMessages;
        this.chatService = chatService;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(REPORTS_DIR);
            Files.createDirectories(PENDING_DIR);
            Files.createDirectories(DELIVERED_DIR);
            Files.createDirectories(STATE_DIR);
        } catch (IOException e) {
            log.warn("[ScheduledReports] Could not create folders: {}", e.getMessage());
        }
        // Initial scan after a brief delay so other services have time to register actions.
        scheduler.schedule(this::rescanReports, 5, TimeUnit.SECONDS);
        // Then rescan every minute.
        scheduler.scheduleWithFixedDelay(this::rescanReports, 60, 60, TimeUnit.SECONDS);
        // Mouse activity poll — cheap, every 5s.
        scheduler.scheduleWithFixedDelay(this::pollMouse, 1, 5, TimeUnit.SECONDS);
        // Fire-check loop — every 60s.
        scheduler.scheduleWithFixedDelay(this::fireDueReports, 30, 60, TimeUnit.SECONDS);
        // Delivery loop — every 30s.
        scheduler.scheduleWithFixedDelay(this::deliverPendingReports, 20, 30, TimeUnit.SECONDS);
        log.info("[ScheduledReports] Service started — reports={}, pending={}, delivered={}",
                REPORTS_DIR, PENDING_DIR, DELIVERED_DIR);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        aiExecutor.shutdownNow();
    }

    /** Other services register a named report generator here (used for {@code action:} tasks). */
    public void registerReportAction(String name, Supplier<String> generator) {
        if (name == null || generator == null) return;
        reportActions.put(name, generator);
        log.info("[ScheduledReports] Registered report action '{}'", name);
    }

    // ─── Folder rescan ──────────────────────────────────────────────────────

    private void rescanReports() {
        if (!Files.isDirectory(REPORTS_DIR)) return;
        Set<String> seen = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(REPORTS_DIR, "*.md")) {
            for (Path file : stream) {
                String fname = file.getFileName().toString();
                if (fname.equalsIgnoreCase("README.md")) continue;
                seen.add(fname);
                ReportTask def = parseReportFile(fname, file);
                if (def != null) activeTasks.put(fname, def);
            }
        } catch (IOException e) {
            log.warn("[ScheduledReports] Rescan failed: {}", e.getMessage());
        }
        // Drop tasks whose file disappeared.
        activeTasks.keySet().retainAll(seen);
    }

    private ReportTask parseReportFile(String fname, Path file) {
        String name = fname.endsWith(".md") ? fname.substring(0, fname.length() - 3) : fname;
        String prompt = null;
        String action = null;
        LocalTime time = null;
        boolean enabled = true;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int colon = t.indexOf(':');
                if (colon <= 0) continue;
                String key = t.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String val = t.substring(colon + 1).trim();
                switch (key) {
                    case "name" -> name = val;
                    case "time" -> {
                        try { time = LocalTime.parse(val); }
                        catch (Exception e) {
                            log.warn("[ScheduledReports] '{}' has invalid time '{}'", fname, val);
                        }
                    }
                    case "prompt" -> prompt = stripQuotes(val);
                    case "action" -> action = val;
                    case "enabled" -> enabled = val.equalsIgnoreCase("true") || val.equals("1");
                    default -> { /* ignore — body content */ }
                }
            }
        } catch (IOException e) {
            log.warn("[ScheduledReports] Could not read {}: {}", fname, e.getMessage());
            return null;
        }
        if (time == null) {
            log.warn("[ScheduledReports] '{}' missing time: HH:MM — skipping", fname);
            return null;
        }
        if (prompt == null && action == null) {
            log.warn("[ScheduledReports] '{}' has neither prompt: nor action: — skipping", fname);
            return null;
        }
        return new ReportTask(fname, name, time, prompt, action, enabled);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\""))
                    || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ─── Fire-check loop ────────────────────────────────────────────────────

    private void fireDueReports() {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        for (ReportTask task : activeTasks.values()) {
            if (!task.enabled()) continue;
            if (now.isBefore(task.time())) continue;
            LocalDate lastFired = readLastFiredDate(task);
            if (lastFired != null && !lastFired.isBefore(today)) continue; // already fired today (or future)
            generateAndQueue(task, today);
        }
    }

    private void generateAndQueue(ReportTask task, LocalDate today) {
        log.info("[ScheduledReports] Firing '{}' (scheduled {}, today's {})",
                task.name(), task.time(), today);
        try {
            String body;
            if (task.prompt() != null && !task.prompt().isBlank()) {
                body = generateViaAi(task.prompt());
            } else {
                Supplier<String> action = reportActions.get(task.action());
                if (action == null) {
                    body = "(report not generated — action '" + task.action() + "' not registered)";
                    log.warn("[ScheduledReports] '{}' references unknown action '{}'",
                            task.name(), task.action());
                } else {
                    body = safeRunAction(action);
                }
            }
            if (body == null || body.isBlank()) body = "(empty report)";
            writePending(task, body);
        } catch (Exception e) {
            log.warn("[ScheduledReports] Generation for '{}' failed: {}", task.name(), e.getMessage());
            try { writePending(task, "Report generation failed: " + e.getMessage()); }
            catch (IOException ignored) {}
        } finally {
            // Always advance last-fired so a transient failure doesn't loop-fire all minute.
            try { writeLastFiredDate(task, today); } catch (IOException ignored) {}
        }
    }

    private String generateViaAi(String prompt) {
        ChatClient client = chatService.getChatClient();
        if (client == null) {
            return "(AI not configured — set spring.ai.openai.api-key)";
        }
        Future<String> future = aiExecutor.submit(() ->
                client.prompt().user(prompt).call().content());
        try {
            return future.get(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return "(report generation timed out after " + AI_TIMEOUT_SECONDS + "s)";
        } catch (Exception e) {
            return "(AI call failed: " + e.getMessage() + ")";
        }
    }

    private String safeRunAction(Supplier<String> action) {
        try {
            return action.get();
        } catch (Exception e) {
            return "(action threw: " + e.getMessage() + ")";
        }
    }

    private void writePending(ReportTask task, String body) throws IOException {
        Files.createDirectories(PENDING_DIR);
        String stamp = LocalDateTime.now().format(FILE_TS);
        Path file = PENDING_DIR.resolve(safeName(task.fileName()) + "_" + stamp + ".md");
        String header = "# " + task.name() + " — " + LocalDate.now() + "\n\n"
                + "_Generated " + LocalDateTime.now() + " for scheduled time " + task.time() + "_\n\n";
        Files.writeString(file, header + body, StandardCharsets.UTF_8);
        log.info("[ScheduledReports] Queued pending report: {}", file.getFileName());
    }

    private LocalDate readLastFiredDate(ReportTask task) {
        Path p = STATE_DIR.resolve(safeName(task.fileName()) + ".last-fired.txt");
        if (!Files.isRegularFile(p)) return null;
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8).trim();
            return LocalDate.parse(s, ISO_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeLastFiredDate(ReportTask task, LocalDate when) throws IOException {
        Files.createDirectories(STATE_DIR);
        Path p = STATE_DIR.resolve(safeName(task.fileName()) + ".last-fired.txt");
        Files.writeString(p, when.format(ISO_DATE), StandardCharsets.UTF_8);
    }

    private static String safeName(String fname) {
        return fname.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ─── Delivery loop ──────────────────────────────────────────────────────

    private void deliverPendingReports() {
        if (!Files.isDirectory(PENDING_DIR)) return;
        if (!isUserActive()) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(PENDING_DIR, "*.md")) {
            for (Path file : stream) {
                try {
                    String body = Files.readString(file, StandardCharsets.UTF_8);
                    asyncMessages.push(body);
                    Path archive = DELIVERED_DIR.resolve(file.getFileName());
                    Files.createDirectories(DELIVERED_DIR);
                    Files.move(file, archive, StandardCopyOption.REPLACE_EXISTING);
                    log.info("[ScheduledReports] Delivered: {}", file.getFileName());
                } catch (IOException e) {
                    log.warn("[ScheduledReports] Delivery failed for {}: {}",
                            file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[ScheduledReports] Delivery scan failed: {}", e.getMessage());
        }
    }

    // ─── User-activity tracking ─────────────────────────────────────────────

    private void pollMouse() {
        try {
            Point now = MouseInfo.getPointerInfo().getLocation();
            if (lastMousePos == null) {
                lastMousePos = now;
                lastMouseMoveMs = System.currentTimeMillis();
                return;
            }
            if (!lastMousePos.equals(now)) {
                lastMousePos = now;
                lastMouseMoveMs = System.currentTimeMillis();
            }
        } catch (Exception ignored) {
            // headless, no display, etc. — leave lastMouseMoveMs as-is
        }
    }

    /** "User is at the keyboard right now" — mouse moved within the last minute. */
    public boolean isUserActive() {
        if (lastMouseMoveMs == 0) return false;
        return System.currentTimeMillis() - lastMouseMoveMs < USER_ACTIVE_WINDOW_MS;
    }

    // ─── Public diagnostics ─────────────────────────────────────────────────

    public Collection<ReportTask> listActiveTasks() {
        return Collections.unmodifiableCollection(activeTasks.values());
    }

    public int countPendingReports() {
        if (!Files.isDirectory(PENDING_DIR)) return 0;
        try (java.util.stream.Stream<Path> s = Files.list(PENDING_DIR)) {
            return (int) s.filter(p -> p.getFileName().toString().endsWith(".md")).count();
        } catch (IOException e) {
            return -1;
        }
    }
}
