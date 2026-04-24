package com.minsbot.agent.tools;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory record of one code-generation job. Holds status, collected logs,
 * list of files written, and a set of SSE subscribers that receive live events.
 */
public class CodeGenJob implements CodeGenSink {

    public final String id = UUID.randomUUID().toString().substring(0, 8);
    public final String mode;          // "primary" | "special" | "local" | "all"
    public final String task;
    public final String workingDir;
    public final Instant startedAt = Instant.now();

    public volatile String status = "queued"; // queued | running | writing | committing | pushing | done | failed | cancelled
    public volatile Instant completedAt;
    public volatile String finalResult = "";

    private final List<String> logs = new ArrayList<>();
    private final Set<String> files = new LinkedHashSet<>();
    private final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    /** The subprocess, if the job spawned one. Held so {@link #cancel()} can kill it. */
    private volatile Process process;
    private volatile boolean cancelled;

    public CodeGenJob(String mode, String task, String workingDir) {
        this.mode = mode;
        this.task = task;
        this.workingDir = workingDir;
    }

    // ─── CodeGenSink implementation ───

    @Override
    public synchronized void log(String line) {
        if (line == null) return;
        logs.add(line);
        broadcast("log", line);
    }

    @Override
    public synchronized void file(String relativePath) {
        if (relativePath == null) return;
        if (files.add(relativePath)) broadcast("file", relativePath);
    }

    @Override
    public void status(String s) {
        this.status = s;
        broadcast("status", s);
    }

    @Override
    public void setProcess(Process p) { this.process = p; }

    // ─── Lifecycle ───

    public boolean isCancelled() { return cancelled; }

    public synchronized void cancel() {
        cancelled = true;
        status("cancelled");
        Process p = this.process;
        if (p != null && p.isAlive()) p.destroyForcibly();
    }

    public synchronized void complete(String finalResult, boolean ok) {
        this.finalResult = finalResult == null ? "" : finalResult;
        this.completedAt = Instant.now();
        status(ok ? "done" : "failed");
        broadcast("result", this.finalResult);
        for (SseEmitter e : subscribers) {
            try { e.complete(); } catch (Exception ignored) {}
        }
        subscribers.clear();
    }

    // ─── SSE ───

    public void subscribe(SseEmitter emitter) {
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        // Replay state so late subscribers catch up.
        try {
            emitter.send(SseEmitter.event().name("status").data(status));
            synchronized (this) {
                for (String l : logs) emitter.send(SseEmitter.event().name("log").data(l));
                for (String f : files) emitter.send(SseEmitter.event().name("file").data(f));
                if (completedAt != null) {
                    emitter.send(SseEmitter.event().name("result").data(finalResult));
                    emitter.complete();
                }
            }
        } catch (Exception e) {
            subscribers.remove(emitter);
        }
    }

    private void broadcast(String name, String data) {
        for (SseEmitter e : subscribers) {
            try {
                e.send(SseEmitter.event().name(name).data(data));
            } catch (Exception ex) {
                subscribers.remove(e);
            }
        }
    }

    public synchronized List<String> snapshotLogs() { return new ArrayList<>(logs); }
    public synchronized Set<String> snapshotFiles() { return new LinkedHashSet<>(files); }
}
