package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs code-generation jobs on a background executor. The REST controller
 * creates jobs here and returns a job ID immediately; the UI then subscribes
 * to the job's SSE stream for live progress.
 */
@Service
public class CodeGenJobService {

    private static final Logger log = LoggerFactory.getLogger(CodeGenJobService.class);
    private static final int MAX_STORED_JOBS = 50;

    private final ClaudeCodeTools claudeCodeTools;
    private final SpecialCodeGenerator specialCodeGenerator;
    private final LocalCodeGenerator localCodeGenerator;
    private final ProjectHistoryService history;

    private final Map<String, CodeGenJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "code-gen-worker");
        t.setDaemon(true);
        return t;
    });

    public CodeGenJobService(ClaudeCodeTools claudeCodeTools,
                             SpecialCodeGenerator specialCodeGenerator,
                             LocalCodeGenerator localCodeGenerator,
                             ProjectHistoryService history) {
        this.claudeCodeTools = claudeCodeTools;
        this.specialCodeGenerator = specialCodeGenerator;
        this.localCodeGenerator = localCodeGenerator;
        this.history = history;
    }

    public CodeGenJob start(String mode, String task, String workingDir, String model,
                            boolean createGithub, boolean isPrivate) {
        CodeGenJob job = new CodeGenJob(mode, task, workingDir);
        jobs.put(job.id, job);
        prune();
        workers.submit(() -> runJob(job, model, createGithub, isPrivate));
        log.info("[CodeGenJob] queued {} mode={} dir={}", job.id, mode, workingDir);
        return job;
    }

    public CodeGenJob get(String id) { return jobs.get(id); }

    public boolean cancel(String id) {
        CodeGenJob j = jobs.get(id);
        if (j == null) return false;
        j.cancel();
        return true;
    }

    // ─── Worker ───

    private void runJob(CodeGenJob job, String model, boolean createGithub, boolean isPrivate) {
        try {
            job.status("running");
            job.log("[Job " + job.id + "] mode=" + job.mode + " dir=" + job.workingDir);
            String result;
            switch (job.mode.toLowerCase()) {
                case "primary" -> result = claudeCodeTools.runWithSink(
                        job.task, job.workingDir, createGithub, isPrivate, job);
                case "special" -> result = specialCodeGenerator.runWithSink(
                        job.task, job.workingDir, createGithub, isPrivate, job);
                case "local"   -> result = localCodeGenerator.runWithSink(
                        job.task, job.workingDir, model, createGithub, isPrivate, job);
                case "all"     -> result = runAll(job, model, createGithub, isPrivate);
                default        -> { result = "Unknown mode: " + job.mode; job.log(result); }
            }
            boolean ok = !job.isCancelled()
                    && result != null
                    && !result.toLowerCase().startsWith("error:");
            job.complete(result, ok);
            history.record(job.id, job.mode, job.task, job.workingDir,
                    ok ? "done" : (job.isCancelled() ? "cancelled" : "failed"), result);
        } catch (Exception e) {
            log.warn("[CodeGenJob] {} failed", job.id, e);
            job.log("FATAL: " + e.getMessage());
            job.complete("Error: " + e.getMessage(), false);
            history.record(job.id, job.mode, job.task, job.workingDir, "failed", e.getMessage());
        }
    }

    private String runAll(CodeGenJob job, String model, boolean createGithub, boolean isPrivate) {
        Path base = Path.of(job.workingDir);
        StringBuilder combined = new StringBuilder();
        runSub(combined, "Primary · Claude Code CLI",
                s -> claudeCodeTools.runWithSink(
                        job.task, base.resolve("claude-cli").toString(), createGithub, isPrivate, s),
                job);
        if (job.isCancelled()) return combined.toString();
        runSub(combined, "Special · Anthropic SDK",
                s -> specialCodeGenerator.runWithSink(
                        job.task, base.resolve("special").toString(), createGithub, isPrivate, s),
                job);
        if (job.isCancelled()) return combined.toString();
        runSub(combined, "Local · Ollama",
                s -> localCodeGenerator.runWithSink(
                        job.task, base.resolve("local").toString(), model, createGithub, isPrivate, s),
                job);
        return combined.toString();
    }

    private interface SubRunner { String run(CodeGenSink sink); }

    private void runSub(StringBuilder combined, String label, SubRunner r, CodeGenJob parent) {
        parent.log("──── " + label + " ────");
        String out = r.run(parent);
        combined.append("──── ").append(label).append(" ────\n").append(out).append("\n\n");
    }

    private void prune() {
        if (jobs.size() <= MAX_STORED_JOBS) return;
        jobs.values().stream()
                .filter(j -> j.completedAt != null)
                .sorted((a, b) -> a.completedAt.compareTo(b.completedAt))
                .limit(Math.max(0, jobs.size() - MAX_STORED_JOBS))
                .forEach(j -> jobs.remove(j.id));
    }

    /** Callback helper used by generators for per-line log routing. */
    public static Consumer<String> logRouter(CodeGenSink sink) {
        return sink::log;
    }
}
