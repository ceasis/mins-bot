package com.minsbot;

import com.minsbot.agent.SystemContextProvider;
import com.minsbot.agent.tools.ToolExecutionNotifier;
import com.minsbot.agent.tools.ToolRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runs parallel background AI agents (separate {@link ChatMemory} conversation ids).
 * Tools are limited to search, files, HTTP/web fetch, and headless Playwright (see {@link ToolRouter#selectToolsForBackgroundAgent()}).
 */
@Service
public class BackgroundAgentService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundAgentService.class);

    public enum AgentStatus {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    private static final String[] BOT_NAMES = {
        "Archie", "Nova", "Pixel", "Spark", "Echo", "Atlas", "Byte", "Cleo",
        "Dash", "Flux", "Gizmo", "Helix", "Iris", "Jazz", "Koda", "Luna",
        "Milo", "Neon", "Orbit", "Pulse", "Quinn", "Rex", "Sage", "Tron",
        "Vex", "Wren", "Xeno", "Yara", "Zephyr", "Bolt"
    };

    private static final String[] BOT_AVATARS = {
        "\uD83E\uDD16", "\uD83E\uDDE0", "\u26A1", "\uD83D\uDD2E", "\uD83C\uDFAF", "\uD83E\uDDBE", "\uD83C\uDF00", "\uD83D\uDD2C",
        "\uD83D\uDE80", "\uD83D\uDC8E", "\uD83E\uDD8A", "\uD83D\uDC19", "\uD83C\uDFAD", "\uD83C\uDF1F", "\uD83D\uDD25", "\u2744\uFE0F",
        "\uD83C\uDF0A", "\uD83C\uDFAA", "\uD83E\uDDE9", "\uD83C\uDFB2", "\uD83E\uDD85", "\uD83D\uDC3A", "\uD83E\uDD81", "\uD83D\uDC09",
        "\uD83C\uDF08", "\u2604\uFE0F", "\uD83C\uDF40", "\uD83C\uDFB8", "\uD83C\uDFC6", "\u2B50"
    };

    public static final class AgentJob {
        private final String id;
        private final String mission;
        private final String name;
        private final String avatar;
        private volatile String model = "";
        private volatile long tokenCount = 0;
        private final Instant createdAt = Instant.now();
        private volatile AgentStatus status = AgentStatus.QUEUED;
        private volatile String progress = "Queued…";
        private volatile Instant finishedAt;
        private volatile String result;
        private volatile String error;
        private volatile String plan;
        private final AtomicInteger progressPercent = new AtomicInteger(0);
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private final ArrayDeque<String> logLines = new ArrayDeque<>(48);

        AgentJob(String id, String mission) {
            this.id = id;
            this.mission = mission;
            int idx = new Random().nextInt(BOT_NAMES.length);
            this.name = BOT_NAMES[idx];
            this.avatar = BOT_AVATARS[idx];
        }

        public String getId() { return id; }
        public String getMission() { return mission; }
        public String getName() { return name; }
        public String getAvatar() { return avatar; }
        public String getModel() { return model; }
        public void setModel(String m) { this.model = m != null ? m : ""; }
        public long getTokenCount() { return tokenCount; }
        public void addTokens(long count) { this.tokenCount += count; }
        public Instant getCreatedAt() { return createdAt; }
        public AgentStatus getStatus() { return status; }
        public void setStatus(AgentStatus s) { this.status = s; }
        public String getProgress() { return progress; }
        public void setProgress(String p) { this.progress = p != null ? p : ""; }
        public Instant getFinishedAt() { return finishedAt; }
        public void setFinishedAt(Instant t) { this.finishedAt = t; }
        public String getResult() { return result; }
        public void setResult(String r) { this.result = r; }
        public String getError() { return error; }
        public void setError(String e) { this.error = e; }
        public String getPlan() { return plan; }
        public void setPlan(String p) { this.plan = p; }
        public int getProgressPercent() { return progressPercent.get(); }
        /** Monotonic 0–100 for UI progress bar. */
        public void setProgressPercentAtLeast(int p) {
            int v = Math.min(100, Math.max(0, p));
            progressPercent.updateAndGet(cur -> Math.max(cur, v));
        }
        public boolean isCancelRequested() { return cancelRequested.get(); }
        public void requestCancel() { cancelRequested.set(true); }

        public synchronized void appendLog(String line) {
            if (line == null || line.isBlank()) return;
            String t = line.length() > 200 ? line.substring(0, 197) + "…" : line;
            while (logLines.size() >= 40) logLines.pollFirst();
            logLines.offerLast(t);
            setProgressPercentAtLeast(Math.min(94, 30 + logLines.size() * 6));
        }

        public synchronized List<String> snapshotLog() {
            return new ArrayList<>(logLines);
        }
    }

    private final ConcurrentHashMap<String, AgentJob> jobs = new ConcurrentHashMap<>();
    /** Slots held from submit until job finishes (queued + running). */
    private final AtomicInteger slotsInUse = new AtomicInteger(0);
    private final ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "mins-bg-agent");
        t.setDaemon(true);
        return t;
    });

    private final ChatService chatService;
    private final SystemContextProvider systemCtx;
    private final ToolRouter toolRouter;
    private final ToolExecutionNotifier toolNotifier;
    private final TranscriptService transcriptService;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String defaultModel;

    private final int maxConcurrent;

    @Autowired
    public BackgroundAgentService(
            ChatService chatService,
            SystemContextProvider systemCtx,
            ToolRouter toolRouter,
            ToolExecutionNotifier toolNotifier,
            TranscriptService transcriptService,
            @Value("${app.agents.max-concurrent:4}") int maxConcurrent) {
        this.chatService = chatService;
        this.systemCtx = systemCtx;
        this.toolRouter = toolRouter;
        this.toolNotifier = toolNotifier;
        this.transcriptService = transcriptService;
        this.maxConcurrent = Math.max(1, Math.min(maxConcurrent, 16));
    }

    /**
     * @throws IllegalArgumentException if mission is blank
     * @throws IllegalStateException if AI is unavailable or max concurrent agents are running
     */
    public String startAgent(String mission) {
        return startAgent(mission, null);
    }

    public String startAgent(String mission, String modelOverride) {
        if (mission == null || mission.isBlank()) {
            throw new IllegalArgumentException("Mission text is required.");
        }
        ChatClient client = chatService.getChatClient();
        if (client == null) {
            throw new IllegalStateException("AI is not configured (set spring.ai.openai.api-key and restart).");
        }
        for (;;) {
            int cur = slotsInUse.get();
            if (cur >= maxConcurrent) {
                throw new IllegalStateException("Max concurrent agents reached (" + maxConcurrent + "). Wait for one to finish.");
            }
            if (slotsInUse.compareAndSet(cur, cur + 1)) {
                break;
            }
        }

        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        AgentJob job = new AgentJob(id, mission.trim());
        // Set model: use override if provided, otherwise default
        String useModel = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : defaultModel;
        job.setModel(useModel);
        jobs.put(id, job);

        executor.submit(() -> {
            try {
                runJob(job, client);
            } finally {
                slotsInUse.decrementAndGet();
            }
        });
        return id;
    }

    public List<Map<String, Object>> listJobs() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(AgentJob::getCreatedAt).reversed())
                .limit(80)
                .map(this::toDto)
                .toList();
    }

    public AgentJob getJob(String id) {
        return id == null ? null : jobs.get(id);
    }

    public boolean cancelJob(String id) {
        AgentJob j = jobs.get(id);
        if (j == null) return false;
        if (j.getStatus() == AgentStatus.COMPLETED || j.getStatus() == AgentStatus.FAILED
                || j.getStatus() == AgentStatus.CANCELLED) {
            return false;
        }
        j.requestCancel();
        j.setProgress("Cancel requested…");
        return true;
    }

    public boolean removeJob(String id) {
        return jobs.remove(id) != null;
    }

    private Map<String, Object> toDto(AgentJob j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("status", j.getStatus().name());
        m.put("mission", j.getMission());
        m.put("progress", j.getProgress());
        m.put("progressPercent", j.getProgressPercent());
        m.put("plan", j.getPlan() != null ? j.getPlan() : "");
        m.put("createdAt", j.getCreatedAt().toEpochMilli());
        m.put("finishedAt", j.getFinishedAt() != null ? j.getFinishedAt().toEpochMilli() : 0L);
        m.put("result", j.getResult() != null ? j.getResult() : "");
        m.put("error", j.getError() != null ? j.getError() : "");
        m.put("name", j.getName());
        m.put("model", j.getModel());
        m.put("avatar", j.getAvatar());
        m.put("tokenCount", j.getTokenCount());
        m.put("log", j.snapshotLog());
        return m;
    }

    private void runJob(AgentJob job, ChatClient client) {
        String convId = "agent-" + job.getId();
        Consumer<String> mirror = msg -> {
            job.appendLog(msg);
            job.setProgress(msg);
        };
        toolNotifier.addProgressMirror(mirror);
        try {
            if (job.isCancelRequested()) {
                finishCancelled(job);
                return;
            }

            job.setStatus(AgentStatus.RUNNING);
            if (job.getModel() == null || job.getModel().isBlank()) job.setModel(defaultModel);
            job.setProgressPercentAtLeast(3);
            job.setProgress("Starting…");

            job.setProgressPercentAtLeast(8);
            job.setProgress("Drafting plan…");
            String planText = chatService.generateBackgroundAgentPlan(job.getMission(), job.getId());
            if (planText != null && !planText.isBlank()) {
                job.setPlan(planText);
                job.setProgressPercentAtLeast(28);
            } else {
                job.setProgressPercentAtLeast(22);
            }

            if (job.isCancelRequested()) {
                finishCancelled(job);
                return;
            }

            String system = systemCtx.buildSystemMessage(null)
                    + "\n\n══ BACKGROUND AGENT (RESTRICTED TOOLS) ══\nYou are agent " + job.getId()
                    + " — a parallel worker with ONLY these capabilities:\n"
                    + "• Search: searchWeb (and similar) for web search.\n"
                    + "• Files: read/write/list files, Excel, Word, PDF via the file tools you have.\n"
                    + "• API / fetch: WebScraper-style tools (readWebPage, fetch HTTP HTML, extract links/images).\n"
                    + "• Web (your own browser): Playwright headless tools (browsePage, browseAndGetImages, etc.) — "
                    + "each call uses an isolated headless context, NOT the user's visible Chrome and NOT CDP.\n"
                    + "FORBIDDEN: You do NOT have screenClick, CDP/Chrome control, openUrl on the desktop browser, "
                    + "PowerShell/CMD, mouse/keyboard, email, or other system tools. If the mission requires them, "
                    + "say so briefly and do what you can with search + files + fetch + Playwright only.\n"
                    + "Finish in one coherent turn when possible; summarize clearly.\n";
            if (planText != null && !planText.isBlank()) {
                system += "\n\n══ YOUR PLAN (follow these steps; allowed tools only) ══\n" + planText + "\n";
            }

            transcriptService.save("USER(agent:" + job.getId() + ")", job.getMission());

            job.setProgressPercentAtLeast(30);
            job.setProgress(planText != null && !planText.isBlank() ? "Plan ready — calling AI…" : "Calling AI (tools may run)…");

            String agentModel = job.getModel();
            var prompt = client.prompt()
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                    .system(system)
                    .user(job.getMission())
                    .tools(toolRouter.selectToolsForBackgroundAgent());
            // Override model if different from the default ChatClient model
            if (agentModel != null && !agentModel.isBlank() && !agentModel.equals(defaultModel)) {
                prompt = prompt.options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(agentModel).build());
            }
            String reply = prompt.call().content();

            if (job.isCancelRequested()) {
                finishCancelled(job);
                return;
            }

            job.setResult(reply != null ? reply : "");
            job.setStatus(AgentStatus.COMPLETED);
            job.setFinishedAt(Instant.now());
            job.setProgressPercentAtLeast(100);
            job.setProgress("Completed");
            transcriptService.save("BOT(agent:" + job.getId() + ")", reply != null ? reply : "");
            log.info("[Agent:{}] Completed", job.getId());
        } catch (Exception e) {
            if (job.isCancelRequested()) {
                finishCancelled(job);
            } else {
                job.setStatus(AgentStatus.FAILED);
                job.setError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                job.setFinishedAt(Instant.now());
                job.setProgressPercentAtLeast(100);
                job.setProgress("Failed");
                log.warn("[Agent:{}] Failed: {}", job.getId(), e.getMessage());
            }
        } finally {
            toolNotifier.removeProgressMirror(mirror);
        }
    }

    private static void finishCancelled(AgentJob job) {
        job.setStatus(AgentStatus.CANCELLED);
        job.setFinishedAt(Instant.now());
        job.setProgressPercentAtLeast(100);
        job.setProgress("Cancelled");
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public int getRunningCount() {
        return slotsInUse.get();
    }
}
