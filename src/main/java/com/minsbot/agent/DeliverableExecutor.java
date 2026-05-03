package com.minsbot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.agent.tools.ResearchTool;
import com.minsbot.agent.tools.ToolExecutionNotifier;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Plan → Execute → Synthesize → Critique → Refine loop for producing
 * high-quality deliverables (reports, briefs, memos, slide outlines).
 *
 * <p>This is the agentic spine the bot needs to act like a knowledge worker:
 * decompose a goal, do research, draft, self-critique against quality criteria,
 * and revise until it ships. Each phase is a separate {@link ChatClient} call
 * with a tight, role-specific system prompt — no hidden auto-loop, no shared
 * scratch state across phases except the explicit working scratchpad file.
 *
 * <p>Cost per call: ~5–10 LLM round-trips (1 plan + N research + 1 synthesize
 * + up to 3 × (critique + refine)). Slow on purpose. The point is quality.
 *
 * <p>Outputs land on the user's Desktop in
 * {@code <Desktop>/MinsBot Deliverables/<task-id>/} so they're trivial to find
 * — Desktop > OneDrive\Desktop > user.home, in that order, depending on what
 * exists. Audit-trail layout:
 * <ul>
 *   <li>{@code plan.md}      — the decomposition the planner agreed to</li>
 *   <li>{@code scratchpad.md} — accumulating research notes per step</li>
 *   <li>{@code draft-N.md}   — synthesized deliverable per iteration</li>
 *   <li>{@code critique-N.md} — critic's score + gap list per iteration</li>
 *   <li>{@code FINAL.md}     — the version that cleared the quality bar</li>
 * </ul>
 */
@Service
public class DeliverableExecutor {

    private static final Logger log = LoggerFactory.getLogger(DeliverableExecutor.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Quality bar (1-10). Refine loop stops when critic scores at or above this. */
    private static final int SHIP_SCORE = 8;
    /** Hard cap on critique→refine cycles even if quality bar isn't met. */
    private static final int MAX_REFINE_CYCLES = 3;
    /** Hard cap on plan steps to keep cost bounded. */
    private static final int MAX_PLAN_STEPS = 7;
    /** Budget: total LLM round-trips per task. Plan + N×research + synth + ~3×(critique+refine). */
    private static final int MAX_LLM_CALLS = 30;
    /** Budget: total chars sent to / received from the LLM. Rough proxy for tokens (×4). */
    private static final long MAX_TOTAL_CHARS = 800_000L;
    /** Step failure ratio above which we trigger a single re-plan. */
    private static final double REPLAN_FAILURE_RATIO = 0.5;

    /** Per-task accounting. Threaded via ThreadLocal so the per-phase {@link #call}
     *  helpers can update counters without every callsite plumbing a budget object. */
    private static final ThreadLocal<TaskState> TASK = new ThreadLocal<>();

    /** Per-task accounting. Atomic counters so parallel step execution stays
     *  correct (within ±1 of the cap, which is fine — budget is a soft fence
     *  for safety, not a billing-grade meter). */
    private static final class TaskState {
        final java.util.concurrent.atomic.AtomicInteger callCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicLong totalChars =
                new java.util.concurrent.atomic.AtomicLong(0);
        volatile int replanCount = 0;
    }

    /** Hard cap on how many times the executor will re-plan when execution keeps
     *  failing. Each cycle = one extra LLM call + 2-4 extra step executions. */
    private static final int MAX_REPLAN_CYCLES = 3;

    /** Thrown when a task blows past {@link #MAX_LLM_CALLS} or {@link #MAX_TOTAL_CHARS}.
     *  Caught by {@link #produce} which returns a partial result to the caller. */
    private static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded(String msg) { super(msg); }
    }

    private final ChatClient chatClient;

    /** Per-phase model selection. Each phase reads its own property so the
     *  user (or a skill) can route the cheap stages to Sonnet/Haiku and the
     *  judgment-heavy stages to Opus. Empty string = inherit the chat client's
     *  default (i.e. {@code spring.ai.openai.chat.options.model}). Models
     *  starting with {@code claude-} go through the Anthropic Messages API
     *  via raw HTTP; everything else uses the OpenAI {@link ChatClient}. */
    @org.springframework.beans.factory.annotation.Value("${app.deliverable.plan-model:claude-sonnet-4-6}")
    private String planModel;
    @org.springframework.beans.factory.annotation.Value("${app.deliverable.replan-model:claude-opus-4-7}")
    private String replanModel;
    @org.springframework.beans.factory.annotation.Value("${app.deliverable.execute-model:claude-sonnet-4-6}")
    private String executeModel;
    @org.springframework.beans.factory.annotation.Value("${app.deliverable.synthesize-model:claude-opus-4-7}")
    private String synthesizeModel;
    @org.springframework.beans.factory.annotation.Value("${app.deliverable.critique-model:claude-opus-4-7}")
    private String critiqueModel;
    @org.springframework.beans.factory.annotation.Value("${app.deliverable.refine-model:claude-opus-4-7}")
    private String refineModel;

    /** Anthropic key for {@code claude-*} models. */
    @org.springframework.beans.factory.annotation.Value(
            "${app.claude.api-key:${app.anthropic.api-key:${ANTHROPIC_API_KEY:}}}")
    private String anthropicApiKey;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final java.net.http.HttpClient anthropicHttp =
            java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
    private final ObjectMapper anthropicJson = new ObjectMapper();

    private final ResearchTool researchTool;
    private final ResearchCache researchCache;
    private final ToolExecutionNotifier notifier;
    private final DeliverableFormatter formatter;
    private final DeliverablePrecedentStore precedents;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.minsbot.agent.tools.CalculatorTools calculatorTools;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeliverableFeedbackStore feedback;

    /** Bounded executor for parallel step execution. Size 4 keeps Anthropic
     *  rate-limit risk low while still giving ~3-4× speedup on the typical
     *  5-7-step plan. Daemon threads so app shutdown isn't blocked. */
    private final java.util.concurrent.ExecutorService stepExec =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "deliverable-step");
                t.setDaemon(true);
                return t;
            });

    @PreDestroy
    void shutdown() {
        stepExec.shutdownNow();
    }

    @Autowired
    public DeliverableExecutor(ChatClient.Builder chatClientBuilder,
                               ResearchTool researchTool,
                               ResearchCache researchCache,
                               DeliverableFormatter formatter,
                               DeliverablePrecedentStore precedents,
                               ToolExecutionNotifier notifier) {
        // Plain ChatClient, no tool binding — each phase here is a single targeted
        // LLM call with structured I/O. We don't want the model picking arbitrary
        // tools mid-phase; orchestration is explicit.
        this.chatClient = chatClientBuilder.build();
        this.researchTool = researchTool;
        this.researchCache = researchCache;
        this.formatter = formatter;
        this.precedents = precedents;
        this.notifier = notifier;
    }

    public Result produce(String goal, String format) {
        return produce(goal, format, "md");
    }

    public Result produce(String goal, String format, String output) {
        if (goal == null || goal.isBlank()) {
            return Result.fail("Goal is required.");
        }
        String fmt = (format == null || format.isBlank()) ? "report" : format.trim().toLowerCase();
        String taskId = STAMP.format(LocalDateTime.now()) + "-" + slug(goal);
        // Hard-coded scratch root: ~/mins_bot_data/workfolder/<task-id>/. NOT
        // user-configurable on purpose — every task is a temp workspace, not a
        // long-term home for the document. Old runs are pruned after 30 days
        // by purgeOldWorkfolders(); the user-visible final lives elsewhere.
        // Resolve via the canonical path utility — it sanitises the slug AND
        // asserts the resolved path lives inside the workfolder root. The LLM
        // cannot influence this location no matter what it passes upstream.
        Path workDir;
        try {
            workDir = WorkfolderPaths.resolve(taskId);
            Files.createDirectories(workDir);
            // Best-effort cleanup of >30-day-old siblings. Cheap (≈1 dir scan)
            // and runs once per task instead of on a timer so it can't drift.
            purgeOldWorkfolders(workDir.getParent(), 30);
        } catch (Exception e) {
            return Result.fail("Could not create work dir: " + e.getMessage());
        }
        log.info("[Deliverable] start id={} goal={}", taskId, truncate(goal, 80));

        TaskState state = new TaskState();
        TASK.set(state);
        Path finalPathSoFar = null;
        try {
            // ─── Phase 1: PLAN ─────────────────────────────────────────
            // Coarse 5-phase progress bar: PLAN(1) + EXECUTE(2) + SYNTH(3) + CRITIQUE(4) + DELIVER(5)
            final String PROGRESS_LABEL = "Deliverable";
            notifier.notify("📋 planning…");
            notifier.notifyProgress(PROGRESS_LABEL, 1, 5);
            List<Step> steps = plan(goal, fmt);
            List<String> stepsForPlanFile = new ArrayList<>();
            for (Step s : steps) stepsForPlanFile.add("[" + s.kind + "] " + s.text);
            writeFile(workDir.resolve("plan.md"),
                    "# Plan\n\n## Goal\n" + goal + "\n\n## Format\n" + fmt + "\n\n## Steps\n"
                            + numberedList(stepsForPlanFile));
            if (steps.isEmpty()) {
                return Result.fail("Planner produced no steps.");
            }

            // ─── Phase 2: EXECUTE (parallel) ───────────────────────────
            StringBuilder scratchpad = new StringBuilder();
            scratchpad.append("# Scratchpad — ").append(goal).append("\n\n");
            notifier.notify("🔎 running " + steps.size() + " steps (concurrent)…");
            // Per-step progress bar — runs alongside the coarse 5-phase one.
            final String STEP_LABEL = "Research steps";
            notifier.notifyProgress(STEP_LABEL, 0, steps.size());
            String[] results = runStepsConcurrently(steps, state, "step");
            notifier.notifyProgress(STEP_LABEL, steps.size(), steps.size());
            notifier.notifyProgress(PROGRESS_LABEL, 2, 5);
            int executed = 0;
            int failed = 0;
            List<String> failureNotes = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                String result = results[i];
                scratchpad.append("## Step ").append(i + 1)
                          .append(" [").append(step.kind).append("]: ")
                          .append(step.text).append("\n\n")
                          .append(result).append("\n\n");
                executed++;
                if (looksFailed(result)) {
                    failed++;
                    failureNotes.add("step " + (i + 1) + " [" + step.kind + "] — " + step.text);
                }
            }
            writeFile(workDir.resolve("scratchpad.md"), scratchpad.toString());

            // ─── Multi-cycle re-plan loop ──────────────────────────────
            // Repeats while too many of the most-recent steps failed AND we haven't
            // exhausted the replan budget. Each cycle plans 2-4 fresh recovery
            // steps that work around what's still failing.
            while (executed > 0
                    && (double) failed / executed > REPLAN_FAILURE_RATIO
                    && state.replanCount < MAX_REPLAN_CYCLES) {
                state.replanCount++;
                int cycleNum = state.replanCount;
                notifier.notify("🔁 re-plan " + cycleNum + " (failed " + failed + "/" + executed + ")…");
                List<Step> rescue = replan(goal, fmt, scratchpad.toString(), failureNotes);
                if (rescue.isEmpty()) {
                    // Recovery planner gave up — no point looping further.
                    break;
                }
                writeFile(workDir.resolve("plan-replan-" + cycleNum + ".md"),
                        "# Re-plan " + cycleNum + "\n\n## Reason\n" + failed + " of "
                                + executed + " steps in the previous round failed.\n\n"
                                + "## Failures\n" + "- " + String.join("\n- ", failureNotes)
                                + "\n\n## New Steps\n"
                                + numberedList(rescue.stream().map(s -> "[" + s.kind + "] " + s.text).toList()));
                String[] rescueResults = runStepsConcurrently(rescue, state, "rescue-" + cycleNum);

                // Reset failure tracking to apply only to this rescue round.
                executed = 0;
                failed = 0;
                failureNotes = new ArrayList<>();
                for (int i = 0; i < rescue.size(); i++) {
                    Step step = rescue.get(i);
                    String result = rescueResults[i];
                    scratchpad.append("## Rescue ").append(cycleNum).append(" · Step ").append(i + 1)
                              .append(" [").append(step.kind).append("]: ")
                              .append(step.text).append("\n\n")
                              .append(result).append("\n\n");
                    executed++;
                    if (looksFailed(result)) {
                        failed++;
                        failureNotes.add("rescue-" + cycleNum + " step " + (i + 1)
                                + " [" + step.kind + "] — " + step.text);
                    }
                }
                writeFile(workDir.resolve("scratchpad.md"), scratchpad.toString());
            }

            // ─── Phase 3 & 4 & 5: SYNTHESIZE → CRITIQUE → REFINE loop ─
            notifier.notifyProgress(PROGRESS_LABEL, 3, 5);
            String draft = synthesize(goal, fmt, scratchpad.toString());
            int cycle = 0;
            int score;
            String lastCritique;
            while (true) {
                cycle++;
                writeFile(workDir.resolve("draft-" + cycle + ".md"), draft);
                notifier.notify("🧐 critiquing draft " + cycle + "…");
                Critique c = critique(goal, fmt, draft);
                writeFile(workDir.resolve("critique-" + cycle + ".md"),
                        "# Critique " + cycle + "\n\nScore: " + c.score + "/10\n\n## Gaps\n"
                                + (c.gaps.isEmpty() ? "(none)" : numberedList(c.gaps)));
                score = c.score;
                lastCritique = c.gaps.isEmpty() ? "(no gaps listed)" : String.join("\n- ", c.gaps);

                if (score >= SHIP_SCORE && c.gaps.isEmpty()) {
                    log.info("[Deliverable] ship at cycle={} score={}", cycle, score);
                    break;
                }
                if (cycle >= MAX_REFINE_CYCLES) {
                    log.info("[Deliverable] cap at cycle={} score={} (gaps still: {})",
                            cycle, score, c.gaps.size());
                    break;
                }
                notifier.notify("✏️  refining (cycle " + cycle + ", score " + score + ")…");
                draft = refine(goal, fmt, draft, c);
            }

            notifier.notifyProgress(PROGRESS_LABEL, 4, 5);
            Path finalPath = workDir.resolve("FINAL.md");
            writeFile(finalPath, draft);
            finalPathSoFar = finalPath;

            // Record this deliverable so future similar requests can learn from
            // its shape (prices/images/links/comparison/etc.). Best-effort —
            // failure here must not affect the user-visible result.
            try {
                if (precedents != null) precedents.record(goal, fmt, output, draft);
            } catch (Exception ignored) {}

            // Convert markdown → target file format (pdf/docx/pptx). md is pass-through.
            Path delivered = finalPath;
            if (output != null && !output.isBlank()
                    && !output.equalsIgnoreCase("md") && !output.equalsIgnoreCase("markdown")) {
                notifier.notify("📄 converting → " + output.toLowerCase() + "…");
                delivered = formatter.convert(finalPath, output, goal);
            }
            // Publish: copy the final out of the temp workfolder into the
            // user's visible destination (Desktop\MinsBot Deliverables\).
            // Workfolder stays around for 30 days for audit, then auto-prunes.
            Path published = publishFinal(delivered, taskId);
            if (published != null) delivered = published;

            String budgetLine = "calls=" + state.callCount.get() + "/" + MAX_LLM_CALLS
                    + ", chars=" + state.totalChars.get() + "/" + MAX_TOTAL_CHARS
                    + (state.replanCount > 0 ? ", replanned=" + state.replanCount + "x" : "");
            log.info("[Deliverable] done id={} {}", taskId, budgetLine);
            notifier.notifyProgress(PROGRESS_LABEL, 5, 5);
            notifier.notify("✅ deliverable ready (" + score + "/10, " + cycle + " cycle"
                    + (cycle == 1 ? "" : "s") + ")");
            return Result.ok(delivered, workDir, score, cycle, lastCritique
                    + "\n[" + budgetLine + "]");

        } catch (BudgetExceeded be) {
            log.warn("[Deliverable] budget exceeded id={}: {}", taskId, be.getMessage());
            String partial = "Stopped on budget cap (" + be.getMessage() + "). "
                    + "Partial work in: " + workDir.toAbsolutePath();
            if (finalPathSoFar != null) {
                return Result.ok(finalPathSoFar, workDir, 0, 0, partial);
            }
            return Result.fail(partial);
        } catch (Exception e) {
            log.warn("[Deliverable] failed id={}: {}", taskId, e.getMessage(), e);
            return Result.fail("Deliverable failed: " + e.getMessage());
        } finally {
            TASK.remove();
        }
    }

    // ─── Phase implementations ──────────────────────────────────────────

    private List<Step> plan(String goal, String format) {
        String sys = "You are a planning agent. Given a deliverable goal and target format, "
                + "decompose it into 3–" + MAX_PLAN_STEPS + " concrete steps the executor can run. "
                + "Each step has a KIND that determines how it gets executed:\n"
                + "  - \"research\": web research / news / facts / external info. Step text is "
                + "the search query. Use when external knowledge is needed.\n"
                + "  - \"read-file\": read a local file the user explicitly mentioned. Step text "
                + "MUST be an absolute path (e.g. C:/Users/.../report.csv). Use only when the user "
                + "named a specific file or folder.\n"
                + "  - \"compute\": calculation / data transformation / numeric reasoning that "
                + "depends only on facts already in the scratchpad or in the step text itself. "
                + "Step text describes what to compute (e.g. 'compute total revenue if Q1=120k, "
                + "Q2=180k, Q3=210k, Q4=240k').\n"
                + "  - \"note\": a structuring/outlining decision that needs no external data; "
                + "just records intent for the synthesizer (e.g. 'open with executive summary; "
                + "close with recommendation'). Use sparingly.\n"
                + "Do NOT include a 'write the deliverable' step — that's a later phase. "
                + "If past similar deliverables are listed below, treat them as a strong hint "
                + "about what THIS user wants by default — if they previously asked for prices, "
                + "images, links, or comparison tables in similar reports, plan steps that gather "
                + "those signals too, even if the current goal didn't mention them explicitly. "
                + "If a 'Learned preferences' block is listed, it is the user's EXPLICIT feedback "
                + "from past deliverables. Signals under 'Tends to want' should be planned for "
                + "(add gather steps). Signals under 'Tends to avoid' should NOT be planned for "
                + "(skip steps that would produce them). Explicit feedback OUTRANKS precedent "
                + "shape — if precedents show comparison-tables but feedback says avoid, avoid. "
                + "Output STRICT JSON: "
                + "{\"steps\":[{\"kind\":\"research|read-file|compute|note\",\"text\":\"...\"}]}. "
                + "No prose, no markdown fences.";

        // Inject relevant past deliverables so the planner sees what this user
        // typically wants in similar reports (prices, images, links, comparison
        // tables, etc.). Empty when there's no precedent — first-run is fine.
        String precedentContext = precedents == null ? ""
                : precedents.relevantPrecedentsAsContext(goal);

        // Inject the explicit-feedback preference profile — per-signal weights
        // accumulated from "perfect" / "you forgot X" / "I didn't need Y"
        // reactions on past deliverables. Strongest signal in the bot's
        // ability to actually adapt to the user over time.
        String feedbackContext = feedback == null ? "" : feedback.preferenceProfileAsContext();

        String extras = (precedentContext.isEmpty() ? "" : "\n\n" + precedentContext)
                + (feedbackContext.isEmpty()  ? "" : "\n\n" + feedbackContext);

        String user = "Goal:\n" + goal + "\n\nTarget format: " + format + extras;
        String reply = call(sys, user, planModel);
        // Empty reply → planner LLM was unreachable. Don't silently return an
        // empty plan; use the single-step fallback so downstream still runs.
        if (reply == null || reply.isBlank()) {
            log.warn("[Deliverable] planner returned empty reply — using single-step fallback");
            return List.of(new Step("research",
                    "Research and gather all relevant information for: " + goal));
        }
        try {
            JsonNode root = JSON.readTree(extractJson(reply));
            JsonNode arr = root.path("steps");
            List<Step> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n.isObject()) {
                        String kind = n.path("kind").asText("research").trim().toLowerCase();
                        String text = n.path("text").asText("").trim();
                        if (text.isEmpty()) continue;
                        out.add(new Step(normalizeKind(kind), text));
                    } else if (n.isTextual()) {
                        // Backward-compat: if planner returns plain strings, default to research.
                        String s = n.asText("").trim();
                        if (!s.isEmpty()) out.add(new Step("research", s));
                    }
                    if (out.size() >= MAX_PLAN_STEPS) break;
                }
            }
            // Fallback for when the parse "succeeded" but yielded nothing useful
            // (Jackson's readTree("") returns MissingNode without throwing, so
            // the catch never fires — this guard catches that path).
            if (out.isEmpty()) {
                log.warn("[Deliverable] planner reply parsed but no steps found — using single-step fallback");
                return List.of(new Step("research",
                        "Research and gather all relevant information for: " + goal));
            }
            return out;
        } catch (Exception e) {
            log.warn("[Deliverable] plan parse failed, falling back to single-step: {}", e.getMessage());
            return List.of(new Step("research", "Research and gather all relevant information for: " + goal));
        }
    }

    /**
     * Run a batch of steps in parallel on the bounded {@link #stepExec}, preserving
     * input order in the returned array so the scratchpad reads top-to-bottom.
     *
     * <p>The parent {@link TaskState} is propagated via {@link #TASK} on each worker
     * thread so budget tracking + {@link BudgetExceeded} still flows through the
     * shared per-task accounting. {@code BudgetExceeded} thrown inside a worker is
     * unwrapped and rethrown on the calling thread so the outer try/catch can
     * convert it to a partial-result return.
     */
    private String[] runStepsConcurrently(List<Step> steps, TaskState state, String tag) {
        int n = steps.size();
        // Atomic so parallel completions update the progress bar without races.
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        String progressLabel = "step".equals(tag) ? "Research steps" : ("Rescue " + tag);

        @SuppressWarnings("unchecked")
        java.util.concurrent.CompletableFuture<String>[] futures = new java.util.concurrent.CompletableFuture[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final Step step = steps.get(i);
            futures[i] = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                TASK.set(state);
                try {
                    notifier.notify("🔎 " + tag + " " + (idx + 1) + "/" + n
                            + " [" + step.kind + "]: " + truncate(step.text, 40));
                    String r = executeStep(step);
                    notifier.notifyProgress(progressLabel, done.incrementAndGet(), n);
                    return r;
                } finally {
                    TASK.remove();
                }
            }, stepExec);
        }
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            try {
                out[i] = futures[i].join();
            } catch (java.util.concurrent.CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof BudgetExceeded be) throw be;
                out[i] = "(step failed: " + (cause == null ? ce.getMessage() : cause.getMessage()) + ")";
            } catch (Exception e) {
                out[i] = "(step failed: " + e.getMessage() + ")";
            }
        }
        return out;
    }

    /** Heuristic: did this step's result indicate a failure / empty / no-data outcome?
     *  Used to decide whether the plan needs to be revised. */
    private static boolean looksFailed(String result) {
        if (result == null || result.isBlank()) return true;
        String r = result.trim();
        if (r.length() < 40) return true;
        String low = r.toLowerCase();
        return low.startsWith("(step failed")
            || low.startsWith("(read-file failed")
            || low.startsWith("(read-file:")
            || low.startsWith("(compute returned empty")
            || low.contains("no search results")
            || low.contains("search succeeded but no urls")
            || low.contains("(fetch failed");
    }

    /** Re-plan with knowledge of what already worked and what didn't. The new plan
     *  is shorter (2–4 steps) and explicitly asked to work around the failures. */
    private List<Step> replan(String goal, String format, String scratchpadSoFar, List<String> failures) {
        String sys = "You are a recovery planner. The first plan partially failed. Given the "
                + "original goal, the work that succeeded (in the scratchpad), and the steps that "
                + "failed, propose 2–4 NEW steps that work around the failures and gather only "
                + "what's still missing to write the deliverable. Use the same kinds: "
                + "research, read-file, compute, note. Do NOT repeat already-successful steps. "
                + "If a research query failed, try a different angle or different keywords. "
                + "If a read-file step failed because the path didn't exist, switch to a research "
                + "step that finds the data instead. Output STRICT JSON: "
                + "{\"steps\":[{\"kind\":\"...\",\"text\":\"...\"}]}. No prose, no markdown fences.";
        String user = "Original goal:\n" + goal + "\n\nTarget format: " + format + "\n\n"
                + "Failed steps:\n- " + String.join("\n- ", failures) + "\n\n"
                + "=== SCRATCHPAD SO FAR ===\n" + truncate(scratchpadSoFar, 12000);
        String reply = call(sys, user, replanModel);
        try {
            JsonNode root = JSON.readTree(extractJson(reply));
            JsonNode arr = root.path("steps");
            List<Step> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n.isObject()) {
                        String kind = n.path("kind").asText("research").trim().toLowerCase();
                        String text = n.path("text").asText("").trim();
                        if (!text.isEmpty()) out.add(new Step(normalizeKind(kind), text));
                    }
                    if (out.size() >= 4) break;
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[Deliverable] re-plan parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String normalizeKind(String k) {
        return switch (k) {
            case "research", "search", "web", "lookup" -> "research";
            case "read-file", "read", "file", "load-file" -> "read-file";
            case "compute", "calc", "calculate", "math" -> "compute";
            case "note", "outline", "structure" -> "note";
            default -> "research";
        };
    }

    private String executeStep(Step step) {
        try {
            return switch (step.kind) {
                case "research" -> researchCache.getOrCompute(step.text,
                        () -> researchTool.research(step.text));
                case "read-file" -> readFileStep(step.text);
                case "compute" -> computeStep(step.text);
                case "note" -> "(structuring note — no external data needed)\n\n" + step.text;
                default -> researchCache.getOrCompute(step.text,
                        () -> researchTool.research(step.text));
            };
        } catch (Exception e) {
            return "(step failed: " + e.getMessage() + ")";
        }
    }

    /** Read up to 12k chars from the file path mentioned in the step. */
    private String readFileStep(String stepText) {
        // Pull the first plausible path out of the step text.
        java.nio.file.Path p = extractPath(stepText);
        if (p == null) {
            return "(read-file: no path found in step text — falling back to research)\n\n"
                    + researchTool.research(stepText);
        }
        if (!java.nio.file.Files.isRegularFile(p)) {
            return "(read-file: file does not exist: " + p + ")";
        }
        try {
            String content = java.nio.file.Files.readString(p, StandardCharsets.UTF_8);
            if (content.length() > 12000) {
                content = content.substring(0, 12000) + "\n... (truncated, " + content.length() + " chars total)";
            }
            return "FILE: " + p.toAbsolutePath() + "\n\n" + content;
        } catch (Exception e) {
            return "(read-file failed: " + e.getMessage() + ")";
        }
    }

    /**
     * Compute step. Two-stage: (1) LLM translates the natural-language ask into
     * a strict numeric expression (no math performed by the LLM), (2) we hand
     * the expression to {@link com.minsbot.agent.tools.CalculatorTools#calculate}
     * — a deterministic recursive-descent evaluator — and return its verified
     * result alongside the LLM's explanation.
     *
     * <p>Why: the LLM is unreliable at percentages, compound rates, and chained
     * arithmetic. The translator role it's still good at; the math role goes
     * to a real evaluator.
     */
    private String computeStep(String stepText) {
        // Stage 1 — NL → expression. Strict format: a single line starting
        // with "EXPR:" plus a unit/notes line. Anything else is dropped.
        String sys = "You translate a natural-language calculation request into a strict numeric "
                + "expression. DO NOT compute the answer yourself — only build the expression. "
                + "Output EXACTLY two lines:\n"
                + "  EXPR: <expression using only digits, + - * / ( ) ^ and decimal points>\n"
                + "  UNIT: <units of the result, e.g. 'USD/month' or 'km'>\n"
                + "Convert words to numbers (150M → 150000000, $0.072 per 1M → 0.072/1000000). "
                + "If the request needs values you don't have, output:\n"
                + "  EXPR: ?\n"
                + "  UNIT: missing: <what's needed>";
        String reply = call(sys, stepText, executeModel);
        if (reply == null || reply.isBlank()) return "(compute returned empty)";

        // Parse EXPR + UNIT lines.
        String expr = null, unit = "";
        for (String line : reply.split("\n")) {
            String t = line.trim();
            if (t.regionMatches(true, 0, "EXPR:", 0, 5)) {
                expr = t.substring(5).trim();
            } else if (t.regionMatches(true, 0, "UNIT:", 0, 5)) {
                unit = t.substring(5).trim();
            }
        }
        if (expr == null || expr.isBlank() || expr.equals("?")) {
            return "(compute: cannot evaluate — " + (unit.isEmpty() ? "expression missing" : unit) + ")";
        }

        // Stage 2 — deterministic evaluation.
        if (calculatorTools == null) {
            // Fallback: no evaluator wired; return the expression as-is so the
            // synthesizer at least knows what was attempted.
            return "Compute: " + stepText + "\nExpression: " + expr
                    + "\n(Calculator unavailable; expression returned without evaluation.)";
        }
        String calcResult = calculatorTools.calculate(expr);
        return "Compute: " + stepText + "\nExpression: " + expr + "\n" + calcResult
                + (unit.isEmpty() ? "" : "\nUnit: " + unit);
    }

    /** Extract a file path from free-form text. Looks for Windows (C:\...) or
     *  POSIX (/...) paths and strips trailing punctuation. */
    private static java.nio.file.Path extractPath(String text) {
        if (text == null) return null;
        // Windows: drive letter + colon + slash
        java.util.regex.Matcher win = java.util.regex.Pattern
                .compile("[A-Za-z]:[\\\\/][^\\s\"'<>|*?]+").matcher(text);
        if (win.find()) {
            String raw = win.group().replaceAll("[).,;:]+$", "");
            try { return java.nio.file.Paths.get(raw); } catch (Exception ignored) {}
        }
        // POSIX or quoted
        java.util.regex.Matcher posix = java.util.regex.Pattern
                .compile("(?:^|\\s)(/[^\\s\"'<>|*?]+)").matcher(text);
        if (posix.find()) {
            String raw = posix.group(1).replaceAll("[).,;:]+$", "");
            try { return java.nio.file.Paths.get(raw); } catch (Exception ignored) {}
        }
        return null;
    }

    private String synthesize(String goal, String format, String scratchpad) {
        notifier.notify("🧠 synthesizing draft…");
        String sys = "You are a senior executive assistant drafting a " + format + " for a busy "
                + "decision-maker who has 90 seconds of attention. Write like a person with "
                + "editorial judgment, not a research dump. "
                + formatGuidance(format) + " "
                + "REQUIRED STRUCTURE (every deliverable, in this order):\n"
                + "1. **Executive Summary** — open with a single short paragraph (3-5 sentences) "
                + "stating the bottom line: what was researched, the headline finding, and the "
                + "single most important takeaway. No bullets. Read like a memo opener.\n"
                + "2. **Body sections** — one per item / topic. EACH item gets its OWN ## heading. "
                + "DO NOT cram multiple items under one heading using **1. Name**, **2. Name** "
                + "bold-numbered inline labels — each item is a SEPARATE H2 section so the "
                + "writer can lay it out with its own image. Example for a 'top 5 laptops' "
                + "report: five ## sections, one per laptop, NOT one ## section with five "
                + "**N. Name** items inside.\n"
                + "   EACH ## section MUST have:\n"
                + "   - A clear heading (## Item Name) — NOT inline bold numbering\n"
                + "   - 2-3 sentences of prose framing why this item matters / what's distinctive\n"
                + "   - At MOST 4 bullets of concrete facts (specs, prices, links, dates). "
                + "Resist the urge to dump 8 bullets — the boss won't read past 4. Pick the most "
                + "decision-relevant ones.\n"
                + "   - One ![](...) image line if a verified image URL exists in the scratchpad.\n"
                + "3. **Recommendation** — close with a `## Recommendation` section: 2-3 sentences "
                + "naming the single best option (or the answer to the question), why, and what "
                + "the reader should do next. Be opinionated. A recommendation that hedges is no "
                + "recommendation.\n"
                + "URLs — STRICT RULE: every http/https URL you write MUST appear character-for-"
                + "character in the SCRATCHPAD below. Do not paraphrase, shorten, normalize, "
                + "guess, or extrapolate URLs. If the scratchpad doesn't have a URL for a claim, "
                + "OMIT the URL — write the claim without a citation rather than invent a source. "
                + "URLs you make up will be stripped before the file is written.\n"
                + "Cite sources inline with [1], [2] format when the scratchpad shows them. "
                + "Do not invent facts, prices, dates, version numbers, or product names that "
                + "are not present in the scratchpad. If the scratchpad is thin on a topic, say "
                + "so rather than padding. "
                + "IMAGES — DO NOT INCLUDE ANY IMAGE URLS OR `![]()` MARKDOWN.\n"
                + "The formatter sources images automatically via Google Images search keyed on "
                + "section headings. Any URL you write will be discarded. Do not waste tokens "
                + "generating URLs, image references, or `_Image reference:_` lines. Just write "
                + "the prose and bullets. Section headings should be descriptive (the product "
                + "name, the place name, the topic) — that's what drives the image search. "
                + "Markdown output.";
        String user = "Goal:\n" + goal + "\n\n=== SCRATCHPAD ===\n"
                + truncate(scratchpad, 18000);
        String draft = call(sys, user, synthesizeModel);
        // Hard guard against URL hallucinations: collect every URL that
        // actually appeared in the scratchpad (these came from real web
        // research), and strip any URL in the synthesized draft that isn't
        // in that allowlist. The synthesizer is told this rule above; this
        // is the enforcement.
        return stripUntrustedUrls(draft, scratchpad);
    }

    /** URL pattern — captures http/https URLs up to whitespace, quotes, brackets. */
    private static final java.util.regex.Pattern ANY_URL =
            java.util.regex.Pattern.compile("https?://[^\\s\"')\\]<>]+");

    /**
     * Drop any URL in {@code draft} that didn't appear verbatim in
     * {@code scratchpad}. Scratchpad URLs come from real web search results,
     * so they're trusted. URLs the LLM "remembered" or constructed are not.
     *
     * <p>For markdown link form {@code [text](url)} where the URL is untrusted,
     * we strip just the link wrapper and keep the text. For bare URLs in
     * prose, we delete them. For footnote-style {@code [1]: <url>} lines
     * with an untrusted URL, we drop the whole line.
     */
    static String stripUntrustedUrls(String draft, String scratchpad) {
        if (draft == null || draft.isBlank()) return draft;
        // 1. Build trusted-URL set from scratchpad. Normalize trailing
        //    punctuation so "...page.html." matches "...page.html".
        java.util.Set<String> trusted = new java.util.HashSet<>();
        if (scratchpad != null) {
            java.util.regex.Matcher sm = ANY_URL.matcher(scratchpad);
            while (sm.find()) {
                trusted.add(stripTrailingPunct(sm.group()));
            }
        }
        if (trusted.isEmpty()) {
            // No trusted URLs at all — strip every URL the LLM wrote.
            log.info("[Deliverable] stripUntrustedUrls: scratchpad had no URLs; removing all draft URLs");
        } else {
            log.info("[Deliverable] stripUntrustedUrls: {} trusted URLs from scratchpad", trusted.size());
        }

        // 2. Pass over draft. First pass: drop entire footnote lines whose
        //    URL is untrusted ("[1]: https://..." or "[1] https://...").
        java.util.regex.Pattern footnote = java.util.regex.Pattern.compile(
                "^\\s*\\[\\d+\\]\\s*:?\\s*(https?://\\S+).*$");
        StringBuilder filtered = new StringBuilder(draft.length());
        int droppedFootnotes = 0;
        for (String line : draft.split("\n", -1)) {
            java.util.regex.Matcher fm = footnote.matcher(line);
            if (fm.matches()) {
                String url = stripTrailingPunct(fm.group(1));
                if (!trusted.contains(url)) {
                    droppedFootnotes++;
                    continue; // drop the whole footnote line
                }
            }
            filtered.append(line).append('\n');
        }
        if (filtered.length() > 0 && filtered.charAt(filtered.length() - 1) == '\n') {
            filtered.setLength(filtered.length() - 1);
        }
        String text = filtered.toString();

        // 3. Strip untrusted markdown links: [text](url) → text, when url is untrusted.
        java.util.regex.Pattern mdLink =
                java.util.regex.Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)");
        java.util.regex.Matcher lm = mdLink.matcher(text);
        StringBuffer mdScrubbed = new StringBuffer();
        int strippedMdLinks = 0;
        while (lm.find()) {
            String url = stripTrailingPunct(lm.group(2));
            if (trusted.contains(url)) {
                lm.appendReplacement(mdScrubbed, java.util.regex.Matcher.quoteReplacement(lm.group()));
            } else {
                // Replace the whole [text](url) with just the text.
                lm.appendReplacement(mdScrubbed, java.util.regex.Matcher.quoteReplacement(lm.group(1)));
                strippedMdLinks++;
            }
        }
        lm.appendTail(mdScrubbed);
        text = mdScrubbed.toString();

        // 4. Delete any remaining bare URL that isn't trusted.
        java.util.regex.Matcher bm = ANY_URL.matcher(text);
        StringBuffer bareScrubbed = new StringBuffer();
        int strippedBare = 0;
        while (bm.find()) {
            String url = stripTrailingPunct(bm.group());
            if (trusted.contains(url)) {
                bm.appendReplacement(bareScrubbed, java.util.regex.Matcher.quoteReplacement(bm.group()));
            } else {
                bm.appendReplacement(bareScrubbed, "");
                strippedBare++;
            }
        }
        bm.appendTail(bareScrubbed);
        text = bareScrubbed.toString();

        if (droppedFootnotes + strippedMdLinks + strippedBare > 0) {
            log.info("[Deliverable] stripUntrustedUrls: removed {} footnote lines, {} markdown links, {} bare URLs not in scratchpad",
                    droppedFootnotes, strippedMdLinks, strippedBare);
        }
        return text;
    }

    private static String stripTrailingPunct(String url) {
        return url == null ? null : url.replaceAll("[.,;:!?\\])>]+$", "");
    }

    private Critique critique(String goal, String format, String draft) {
        String sys = "You are a strict editor reviewing a " + format + " against the user's goal. "
                + "Score 1-10 on the combination of: completeness vs goal, factual accuracy "
                + "given source citations, clarity, structure, and fitness-for-format. "
                + "List CONCRETE gaps that must be fixed before shipping (missing data, unsupported "
                + "claims, weak structure, factual errors). Be terse. "
                + "Output STRICT JSON: {\"score\": <int 1-10>, \"gaps\": [\"gap 1\", \"gap 2\", ...]}. "
                + "Empty gap list is allowed and means ship-ready. No prose.";
        String user = "Goal:\n" + goal + "\n\n=== DRAFT ===\n" + truncate(draft, 14000);
        String reply = call(sys, user, critiqueModel);
        try {
            JsonNode root = JSON.readTree(extractJson(reply));
            int score = root.path("score").asInt(5);
            List<String> gaps = new ArrayList<>();
            JsonNode arr = root.path("gaps");
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String g = n.asText("").trim();
                    if (!g.isEmpty()) gaps.add(g);
                }
            }
            return new Critique(score, gaps);
        } catch (Exception e) {
            log.warn("[Deliverable] critique parse failed: {}", e.getMessage());
            return new Critique(5, List.of("(critique parser failed; treating as borderline)"));
        }
    }

    private String refine(String goal, String format, String draft, Critique c) {
        String sys = "You are a senior writer revising a " + format + ". A strict editor scored "
                + "the draft " + c.score + "/10 and flagged the listed gaps. Address EVERY gap "
                + "concretely. Preserve what works; don't rewrite paragraphs that aren't flagged. "
                + "Markdown output, complete deliverable (not a diff).";
        String gapList = c.gaps.isEmpty() ? "(general polish)" : "- " + String.join("\n- ", c.gaps);
        String user = "Goal:\n" + goal + "\n\n=== GAPS TO FIX ===\n" + gapList
                + "\n\n=== CURRENT DRAFT ===\n" + truncate(draft, 14000);
        return call(sys, user, refineModel);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /** Per-phase model entry. {@code modelId} starting with {@code claude-}
     *  routes to the Anthropic Messages API via raw HTTP (skipping Spring
     *  AI, since this codebase doesn't pull in spring-ai-anthropic). Anything
     *  else goes through the existing OpenAI {@link ChatClient}. {@code null}
     *  or blank inherits the ChatClient's default model. */
    private String call(String system, String user, String modelId) {
        TaskState st = TASK.get();
        if (st != null) {
            int calls = st.callCount.get();
            long chars = st.totalChars.get();
            if (calls >= MAX_LLM_CALLS) {
                throw new BudgetExceeded("call cap reached: " + calls + "/" + MAX_LLM_CALLS);
            }
            if (chars >= MAX_TOTAL_CHARS) {
                throw new BudgetExceeded("char cap reached: " + chars + "/" + MAX_TOTAL_CHARS);
            }
            st.callCount.incrementAndGet();
            long sent = (system == null ? 0L : system.length())
                      + (user   == null ? 0L : user.length());
            st.totalChars.addAndGet(sent);
        }
        boolean anthropic = modelId != null && modelId.toLowerCase().startsWith("claude");
        // Retry once on transient network failures.
        String reply = "";
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                if (anthropic) {
                    reply = anthropicCall(modelId, system, user);
                } else if (modelId != null && !modelId.isBlank()) {
                    // Per-call OpenAI model override.
                    reply = chatClient.prompt()
                            .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                                    .model(modelId).build())
                            .system(system).user(user).call().content();
                } else {
                    reply = chatClient.prompt().system(system).user(user).call().content();
                }
                if (reply != null && !reply.isBlank()) break;
                lastErr = new RuntimeException("empty LLM reply");
            } catch (Exception e) {
                lastErr = e;
                log.warn("[Deliverable] LLM call attempt {} (model={}) failed: {}",
                        attempt, modelId == null ? "default" : modelId, e.getMessage());
            }
            if (attempt == 1) {
                try { Thread.sleep(750); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (reply == null) reply = "";
        if (reply.isBlank() && lastErr != null) {
            log.warn("[Deliverable] LLM call gave up after retries: {}", lastErr.getMessage());
        }
        if (st != null) {
            st.totalChars.addAndGet(reply.length());
        }
        return reply;
    }

    /** Direct call to Anthropic's Messages API. Mirrors the request shape
     *  used by {@code ClaudeVisionService} and {@code ToolClassifierService}.
     *  60s timeout — synthesize/critique on long scratchpads can run >30s. */
    private String anthropicCall(String model, String system, String user) throws Exception {
        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            throw new RuntimeException("Anthropic key not configured (set ANTHROPIC_API_KEY or "
                    + "app.claude.api-key) — required for model '" + model + "'");
        }
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 4096);
        // temperature is REJECTED by Claude Opus 4.7 ("temperature is deprecated
        // for this model"). Sonnet/Haiku still accept it, but omitting it across
        // the board uses each model's default and removes a per-model branch.
        if (system != null && !system.isBlank()) body.put("system", system);
        body.put("messages", java.util.List.of(
                java.util.Map.of("role", "user",
                        "content", user == null ? "" : user)));
        String json = anthropicJson.writeValueAsString(body);
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(ANTHROPIC_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", anthropicApiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .timeout(java.time.Duration.ofSeconds(60))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .build();
        java.net.http.HttpResponse<String> resp = anthropicHttp.send(
                req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Anthropic HTTP " + resp.statusCode() + ": "
                    + resp.body().substring(0, Math.min(400, resp.body().length())));
        }
        com.fasterxml.jackson.databind.JsonNode root = anthropicJson.readTree(resp.body());
        com.fasterxml.jackson.databind.JsonNode arr = root.path("content");
        if (arr.isArray() && arr.size() > 0) {
            return arr.get(0).path("text").asText("");
        }
        return "";
    }

    private static String formatGuidance(String fmt) {
        return switch (fmt) {
            case "ppt", "slides", "deck", "slide-deck" ->
                "Slide outline: 8–14 slides. Slide 1 = Executive Summary (the single key finding). "
                + "Then one slide per item (## title + 2-3 sentences of prose + max 4 bullets + image). "
                + "Last slide = Recommendation.";
            case "memo" ->
                "1–2 page memo. BLUF in the Executive Summary paragraph. Then 2–4 tightly-edited "
                + "sections, each with a one-line heading + a short prose paragraph + max 3 bullets. "
                + "Recommendation section closes — opinionated, specific, ≤3 sentences.";
            case "brief" ->
                "Half-page brief. Executive Summary opens (2-3 sentences). One Body section. "
                + "Recommendation closes (1-2 sentences). No long bullet lists.";
            case "report" ->
                "Full report. Executive Summary at top (3-5 sentences, no bullets). 4-8 body "
                + "sections, each with a 2-3 sentence prose intro + max 4 bullets + image where "
                + "available. Recommendation closes the report — opinionated, specific. "
                + "Cite every numeric claim with [1], [2] etc.";
            default -> "Clean markdown: Executive Summary at top, body sections with prose+bullets, Recommendation at end.";
        };
    }

    private static String numberedList(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append(i + 1).append(". ").append(items.get(i)).append("\n");
        }
        return sb.toString();
    }

    private static void writeFile(Path p, String content) {
        try {
            Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        // Single-line ellipsis. The previous "\n... (truncated)" suffix put a
        // newline into status notifications, which the chat status feed
        // renders as a wrapped multi-line entry. Status lines must stay single-
        // line; the FULL untruncated text already lives in plan.md / scratchpad.md
        // inside the task workfolder for audit.
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String slug(String s) {
        String t = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return t.length() > 55 ? t.substring(0, 55) : (t.isEmpty() ? "task" : t);
    }

    /**
     * Resolve the user's Desktop directory. Tries (in order) the OneDrive-
     * redirected Desktop (common on Windows when the user signs into a
     * Microsoft account), the standard Desktop, the {@code USERPROFILE}
     * Desktop, and finally {@code user.home} as a last resort.
     */
    /**
     * Best-effort prune of task folders under {@code parent} whose last-modified
     * time is older than {@code days} days. Runs once per task on entry. Silent
     * on failure — pruning is hygiene, never load-bearing.
     */
    private static void purgeOldWorkfolders(Path parent, int days) {
        if (parent == null || !Files.isDirectory(parent) || days <= 0) return;
        long cutoff = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
        try (var stream = Files.list(parent)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                try {
                    long mtime = Files.getLastModifiedTime(dir).toMillis();
                    if (mtime < cutoff) {
                        deleteRecursive(dir);
                        log.info("[Deliverable] purged old workfolder: {}", dir.getFileName());
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private static void deleteRecursive(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    /**
     * Copy the final deliverable out of the temp workfolder and into the user-
     * visible destination ({@code <Desktop>/MinsBot Deliverables/<task-id>.<ext>}).
     * The workfolder still holds the full audit trail (plan, drafts, critiques,
     * images/) until the 30-day prune sweeps it. Returns the published path,
     * or {@code null} if the copy failed (caller falls back to the workfolder
     * path so the user can still find the file).
     */
    private static Path publishFinal(Path source, String taskId) {
        if (source == null || !Files.isRegularFile(source)) return null;
        try {
            Path destDir = resolveDesktopFolder().resolve("MinsBot Deliverables");
            Files.createDirectories(destDir);
            String fname = source.getFileName().toString();
            int dot = fname.lastIndexOf('.');
            String ext = (dot > 0) ? fname.substring(dot) : "";
            Path dest = destDir.resolve(taskId + ext);
            Files.copy(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("[Deliverable] published {} → {}", source.getFileName(), dest);
            return dest;
        } catch (Exception e) {
            log.warn("[Deliverable] publishFinal failed: {}", e.getMessage());
            return null;
        }
    }

    private static Path resolveDesktopFolder() {
        String home = System.getProperty("user.home");
        String userProfile = System.getenv("USERPROFILE");
        String[] candidates = {
                home != null ? home + java.io.File.separator + "OneDrive" + java.io.File.separator + "Desktop" : null,
                userProfile != null ? userProfile + java.io.File.separator + "OneDrive" + java.io.File.separator + "Desktop" : null,
                home != null ? home + java.io.File.separator + "Desktop" : null,
                userProfile != null ? userProfile + java.io.File.separator + "Desktop" : null,
        };
        for (String c : candidates) {
            if (c == null) continue;
            Path p = Paths.get(c);
            if (Files.isDirectory(p)) return p;
        }
        // Last resort: user.home (we'll createDirectories the subfolder anyway)
        return Paths.get(home != null ? home : ".");
    }

    /** Extract a JSON object/array from a possibly fenced or prose-wrapped reply. */
    private static String extractJson(String s) {
        if (s == null) return "{}";
        String t = s.trim();
        // Strip markdown fences
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            int closeFence = t.lastIndexOf("```");
            if (closeFence > 0) t = t.substring(0, closeFence);
        }
        // Find the first { or [ and the matching close
        int start = -1;
        char openCh = '{', closeCh = '}';
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '{' || ch == '[') {
                start = i;
                openCh = ch;
                closeCh = (ch == '{') ? '}' : ']';
                break;
            }
        }
        if (start < 0) return t;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
            } else {
                if (ch == '"') inStr = true;
                else if (ch == openCh) depth++;
                else if (ch == closeCh) {
                    depth--;
                    if (depth == 0) return t.substring(start, i + 1);
                }
            }
        }
        return t.substring(start);
    }

    // ─── Result type ─────────────────────────────────────────────────────

    /** A planned step with its execution kind. */
    public record Step(String kind, String text) {}

    public record Critique(int score, List<String> gaps) {}

    /**
     * @param path     published file (the user-visible copy on Desktop)
     * @param workDir  scratch folder inside mins_workfolder — full audit trail
     *                 (plan.md / scratchpad.md / drafts / critiques / images/).
     *                 The Java executor owns this path; skill prompts should
     *                 read it from this Result, not hardcode the convention.
     */
    public record Result(boolean ok, Path path, Path workDir, int score, int cycles, String message) {
        public static Result ok(Path p, Path work, int score, int cycles, String msg) {
            return new Result(true, p, work, score, cycles, msg);
        }
        public static Result fail(String msg) {
            return new Result(false, null, null, 0, 0, msg);
        }
    }
}
