package com.minsbot.mission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.minsbot.ChatService;
import com.minsbot.TranscriptService;
import com.minsbot.agent.SystemContextProvider;
import com.minsbot.agent.tools.ToolRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Long-running goal-directed task runner. Unlike a single chat-with-tools turn
 * (which caps out when the context window overflows), a mission decomposes the
 * user's goal into discrete steps, runs each in its own isolated LLM call, and
 * keeps the per-step context small by only passing the goal + a tiny scratchpad
 * of the last few step summaries.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   pending → planning → running → done
 *                              ↘ failed
 *                              ↘ cancelled
 * </pre>
 *
 * <h3>Files</h3>
 * <ul>
 *   <li>{@code memory/missions/<id>/mission.json} — full checkpoint, rewritten after every step</li>
 *   <li>{@code memory/missions/<id>/log.txt} — human-readable progress log</li>
 * </ul>
 */
@Service
public class MissionService {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);

    private static final Path ROOT =
            Paths.get("memory", "missions").toAbsolutePath();

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Serial executor — missions don't run in parallel today. Keep it simple. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mission-loop");
        t.setDaemon(true);
        return t;
    });

    /** In-memory index of missions by id — populated lazily from disk on demand. */
    private final Map<String, Mission> missions = new ConcurrentHashMap<>();
    /** Stop signals keyed by mission id. */
    private final Map<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    private final ChatService chatService;
    private final ToolRouter toolRouter;
    private final SystemContextProvider systemCtx;
    private final TranscriptService transcriptService;

    public MissionService(@Lazy ChatService chatService,
                          @Lazy ToolRouter toolRouter,
                          SystemContextProvider systemCtx,
                          TranscriptService transcriptService) {
        this.chatService = chatService;
        this.toolRouter = toolRouter;
        this.systemCtx = systemCtx;
        this.transcriptService = transcriptService;
        try { Files.createDirectories(ROOT); } catch (IOException ignored) {}
    }

    // ═══ Public API ══════════════════════════════════════════════════

    /**
     * Kick off a new mission. Decomposition (LLM → step list) + execution run on
     * a background thread. Returns the mission id immediately.
     */
    public String startMission(String goal) {
        return startMission(goal, null);
    }

    /**
     * Same as {@link #startMission(String)} but lets the caller supply an explicit
     * step list (skips the planner LLM call). Useful for testing + resuming.
     */
    public String startMission(String goal, List<String> explicitSteps) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("Mission goal is required");
        }
        Mission m = new Mission(UUID.randomUUID().toString(), goal.trim());
        m.status = explicitSteps == null ? "planning" : "running";
        if (explicitSteps != null) {
            for (int i = 0; i < explicitSteps.size(); i++) {
                m.steps.add(new MissionStep(i, explicitSteps.get(i)));
            }
        }
        missions.put(m.id, m);
        stopFlags.put(m.id, new AtomicBoolean(false));
        persist(m);
        executor.submit(() -> runMission(m.id));
        return m.id;
    }

    public Mission getMission(String id) {
        if (id == null) return null;
        Mission cached = missions.get(id);
        if (cached != null) return cached;
        // Try to read from disk (resume case).
        Path file = ROOT.resolve(id).resolve("mission.json");
        if (!Files.exists(file)) return null;
        try {
            Mission m = mapper.readValue(file.toFile(), Mission.class);
            missions.put(id, m);
            return m;
        } catch (Exception e) {
            log.warn("[Mission] failed to read {}: {}", file, e.getMessage());
            return null;
        }
    }

    public List<Mission> listMissions() {
        List<Mission> out = new ArrayList<>(missions.values());
        // Also surface any on-disk missions not yet loaded.
        try {
            if (Files.exists(ROOT)) {
                Files.list(ROOT).filter(Files::isDirectory).forEach(dir -> {
                    String id = dir.getFileName().toString();
                    if (!missions.containsKey(id)) {
                        Mission m = getMission(id);
                        if (m != null) out.add(m);
                    }
                });
            }
        } catch (IOException ignored) {}
        out.sort(Comparator.comparing((Mission m) -> m.createdAt == null ? "" : m.createdAt).reversed());
        return out;
    }

    public boolean stopMission(String id) {
        AtomicBoolean flag = stopFlags.get(id);
        if (flag == null) return false;
        flag.set(true);
        Mission m = missions.get(id);
        if (m != null && ("running".equals(m.status) || "planning".equals(m.status))) {
            appendLog(m, "Cancel requested by user.");
        }
        return true;
    }

    // ═══ Main loop ═══════════════════════════════════════════════════

    private void runMission(String id) {
        Mission m = missions.get(id);
        if (m == null) return;
        AtomicBoolean stop = stopFlags.get(id);
        m.startedAt = Instant.now().toString();
        appendLog(m, "Mission started: " + shortGoal(m.goal));

        try {
            // 1. Decompose goal into steps (if not pre-supplied).
            if (m.steps.isEmpty()) {
                m.status = "planning";
                persist(m);
                List<String> steps = decomposeGoal(m.goal);
                if (steps.isEmpty()) {
                    fail(m, "Planner returned no steps — goal may be too vague.");
                    return;
                }
                for (int i = 0; i < steps.size(); i++) {
                    m.steps.add(new MissionStep(i, steps.get(i)));
                }
                appendLog(m, "Plan ready: " + m.steps.size() + " steps.");
            }

            // 2. Execute steps sequentially, with per-step retries.
            m.status = "running";
            persist(m);
            for (int i = m.currentStepIndex; i < m.steps.size(); i++) {
                if (stop != null && stop.get()) {
                    m.status = "cancelled";
                    m.completedAt = Instant.now().toString();
                    persist(m);
                    appendLog(m, "Mission cancelled at step " + (i + 1) + ".");
                    return;
                }
                if (m.tokenBudget > 0 && m.tokensUsed >= m.tokenBudget) {
                    fail(m, "Token budget exhausted at step " + (i + 1)
                            + " (" + m.tokensUsed + " / " + m.tokenBudget + ").");
                    return;
                }
                MissionStep step = m.steps.get(i);
                m.currentStepIndex = i;
                executeStep(m, step, stop);
                persist(m);
                if ("failed".equals(step.status) && step.attempts >= m.maxRetries) {
                    // Policy: if a step permanently fails, continue with remaining
                    // steps (scratchpad captures the failure) so downstream work
                    // can still complete what's possible. Mission is marked failed
                    // at the end if any step never succeeded.
                    appendLog(m, "Step " + (i + 1) + " failed after " + step.attempts
                            + " attempts — continuing with remaining steps.");
                }
            }

            // 3. Wrap up.
            boolean anyFailed = m.steps.stream().anyMatch(s -> "failed".equals(s.status));
            m.status = anyFailed ? "failed" : "done";
            m.completedAt = Instant.now().toString();
            persist(m);
            appendLog(m, "Mission " + m.status + ". " + m.steps.size() + " steps, "
                    + m.tokensUsed + " tokens approx.");
        } catch (Exception e) {
            log.error("[Mission] {} crashed: {}", id, e.getMessage(), e);
            fail(m, "Loop crashed: " + e.getMessage());
        } finally {
            stopFlags.remove(id);
        }
    }

    /** Ask the LLM to split a goal into atomic, tool-friendly steps. */
    private List<String> decomposeGoal(String goal) {
        ChatClient client = chatService.getChatClient();
        if (client == null) {
            return List.of(); // No LLM configured — caller can supply explicit steps.
        }
        String system = "You are a task planner. Break the user's goal into an ordered list of "
                + "CONCRETE, INDEPENDENT, TOOL-FRIENDLY steps. Each step should be one sentence, "
                + "actionable in a single tool call or short chain. Keep the list short — "
                + "prefer 5-12 steps. Do NOT add meta-steps like 'verify', 'summarize', 'report' "
                + "unless the user explicitly asked. Return ONLY the steps, one per line, "
                + "no numbering, no headers, no blank lines.";
        String reply;
        try {
            reply = client.prompt()
                    .system(system)
                    .user("Goal: " + goal)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("[Mission] decomposition failed: {}", e.getMessage());
            return List.of();
        }
        if (reply == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : reply.split("\\R")) {
            String cleaned = line.trim();
            if (cleaned.isEmpty()) continue;
            // Strip common leading list markers the LLM adds despite instructions.
            cleaned = cleaned.replaceFirst("^\\s*(?:\\d+[.)]|[•\\-*])\\s*", "").trim();
            if (!cleaned.isEmpty()) out.add(cleaned);
        }
        return out;
    }

    /** One attempt at running a step, with up to {@code maxRetries} retries on failure. */
    private void executeStep(Mission m, MissionStep step, AtomicBoolean stop) {
        step.startedAt = Instant.now().toString();
        int attemptCap = Math.max(1, m.maxRetries);
        for (int attempt = 1; attempt <= attemptCap; attempt++) {
            if (stop != null && stop.get()) return;
            step.status = "running";
            step.attempts = attempt;
            persist(m);
            appendLog(m, "Step " + (step.index + 1) + "/" + m.steps.size()
                    + " (attempt " + attempt + "/" + attemptCap + "): "
                    + shortGoal(step.description));
            try {
                String result = callStep(m, step);
                step.result = result;
                step.status = "done";
                step.completedAt = Instant.now().toString();
                appendToScratchpad(m, step);
                appendLog(m, "  ✓ " + shortGoal(result == null ? "(no output)" : result));
                return;
            } catch (Exception e) {
                step.error = e.getMessage();
                log.warn("[Mission] step {} attempt {} failed: {}",
                        step.index, attempt, e.getMessage());
                appendLog(m, "  ✗ attempt " + attempt + " failed: " + e.getMessage());
                if (attempt >= attemptCap) {
                    step.status = "failed";
                    step.completedAt = Instant.now().toString();
                    return;
                }
                // Brief backoff before retry.
                try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** The actual LLM call for one step. Fresh conversation, minimal context. */
    private String callStep(Mission m, MissionStep step) {
        ChatClient client = chatService.getChatClient();
        if (client == null) throw new IllegalStateException("No ChatClient configured");

        StringBuilder system = new StringBuilder(systemCtx.buildSystemMessage(step.description));
        system.append("\n\n══ MISSION CONTEXT ══\n")
              .append("You are executing ONE step of a larger mission. Do exactly this step, ")
              .append("then return a short (1-3 sentence) summary of what you did. ")
              .append("Do NOT work on other steps.\n")
              .append("Mission goal: ").append(m.goal).append('\n');
        if (!m.scratchpad.isEmpty()) {
            system.append("\nRecent step outcomes:\n");
            for (String s : m.scratchpad) system.append("  • ").append(s).append('\n');
        }

        String reply = client.prompt()
                .system(system.toString())
                .user("Step " + (step.index + 1) + " of " + m.steps.size() + ": " + step.description)
                .tools(toolRouter.selectTools(step.description))
                .call()
                .content();

        // Rough token accounting — 1 token ≈ 4 chars.
        if (reply != null) m.tokensUsed += reply.length() / 4;
        m.tokensUsed += system.length() / 4;
        return reply;
    }

    /** Keep the scratchpad to the last 5 step summaries, each capped at 500 chars. */
    private void appendToScratchpad(Mission m, MissionStep step) {
        String summary = step.status.equals("failed")
                ? "[FAILED step " + (step.index + 1) + "] " + safeShort(step.error, 500)
                : "[step " + (step.index + 1) + "] " + safeShort(step.result, 500);
        m.scratchpad.add(summary);
        while (m.scratchpad.size() > 5) m.scratchpad.remove(0);
    }

    private void fail(Mission m, String reason) {
        m.status = "failed";
        m.lastError = reason;
        m.completedAt = Instant.now().toString();
        persist(m);
        appendLog(m, "Mission FAILED: " + reason);
    }

    // ═══ Persistence & logs ═══════════════════════════════════════════

    private synchronized void persist(Mission m) {
        try {
            Path dir = ROOT.resolve(m.id);
            Files.createDirectories(dir);
            Path file = dir.resolve("mission.json");
            mapper.writeValue(file.toFile(), m);
        } catch (IOException e) {
            log.warn("[Mission] persist failed for {}: {}", m.id, e.getMessage());
        }
    }

    private void appendLog(Mission m, String line) {
        String stamped = "[" + Instant.now() + "] " + line;
        log.info("[Mission {}] {}", shortId(m.id), line);
        try {
            Path dir = ROOT.resolve(m.id);
            Files.createDirectories(dir);
            Path file = dir.resolve("log.txt");
            Files.writeString(file, stamped + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
        // Surface mission events in the main chat so the user sees progress.
        transcriptService.save("BOT(mission)", "🎯 " + line);
    }

    // ═══ helpers ═════════════════════════════════════════════════════

    private static String shortGoal(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 117) + "…" : t;
    }

    private static String safeShort(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(8, id.length()));
    }

    /** Small DTO for API responses (full mission JSON is also fine but noisy). */
    public Map<String, Object> toDto(Mission m) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", m.id);
        out.put("goal", m.goal);
        out.put("status", m.status);
        out.put("createdAt", m.createdAt);
        out.put("startedAt", m.startedAt);
        out.put("completedAt", m.completedAt);
        out.put("totalSteps", m.steps.size());
        out.put("currentStepIndex", m.currentStepIndex);
        long done = m.steps.stream().filter(s -> "done".equals(s.status)).count();
        long failed = m.steps.stream().filter(s -> "failed".equals(s.status)).count();
        out.put("stepsDone", done);
        out.put("stepsFailed", failed);
        out.put("tokensUsed", m.tokensUsed);
        out.put("tokenBudget", m.tokenBudget);
        out.put("lastError", m.lastError);
        List<Map<String, Object>> stepDtos = new ArrayList<>();
        for (MissionStep s : m.steps) {
            java.util.LinkedHashMap<String, Object> sd = new java.util.LinkedHashMap<>();
            sd.put("index", s.index);
            sd.put("description", s.description);
            sd.put("status", s.status);
            sd.put("attempts", s.attempts);
            sd.put("result", safeShort(s.result, 300));
            sd.put("error", s.error);
            stepDtos.add(sd);
        }
        out.put("steps", stepDtos);
        return out;
    }
}
