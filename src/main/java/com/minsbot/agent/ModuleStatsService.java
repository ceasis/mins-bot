package com.minsbot.agent;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks call counts and active model names for vision and audio modules.
 * Used by the status bar in the UI to show which engines are active.
 */
@Service
public class ModuleStatsService {

    // ── Vision stats ──
    private final ConcurrentHashMap<String, AtomicLong> visionCounts = new ConcurrentHashMap<>();
    private volatile String lastVisionModel = "—";

    // ── Chat/LLM stats ──
    private final ConcurrentHashMap<String, AtomicLong> chatCounts = new ConcurrentHashMap<>();
    private volatile String lastChatModel = "—";

    // ── Audio stats ──
    private final ConcurrentHashMap<String, AtomicLong> audioCounts = new ConcurrentHashMap<>();
    private volatile String lastAudioModel = "—";

    /** Record a vision API call. */
    public void recordVisionCall(String model) {
        if (model == null || model.isBlank()) return;
        lastVisionModel = model;
        visionCounts.computeIfAbsent(model, k -> new AtomicLong()).incrementAndGet();
    }

    /** Record a chat/LLM API call. */
    public void recordChatCall(String model) {
        if (model == null || model.isBlank()) return;
        lastChatModel = model;
        chatCounts.computeIfAbsent(model, k -> new AtomicLong()).incrementAndGet();
    }

    /** Record an audio API call (transcription, translation, TTS send). */
    public void recordAudioCall(String model) {
        if (model == null || model.isBlank()) return;
        lastAudioModel = model;
        audioCounts.computeIfAbsent(model, k -> new AtomicLong()).incrementAndGet();
    }

    /** Get stats for the status bar. */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // Chat/LLM
        long totalChat = chatCounts.values().stream().mapToLong(AtomicLong::get).sum();
        stats.put("chatModel", lastChatModel);
        stats.put("chatTotal", totalChat);

        // Vision — show the most-used model and its count
        long totalVision = visionCounts.values().stream().mapToLong(AtomicLong::get).sum();
        stats.put("visionModel", lastVisionModel);
        stats.put("visionTotal", totalVision);
        Map<String, Long> visionBreakdown = new LinkedHashMap<>();
        visionCounts.forEach((k, v) -> visionBreakdown.put(k, v.get()));
        stats.put("visionBreakdown", visionBreakdown);

        // Audio
        long totalAudio = audioCounts.values().stream().mapToLong(AtomicLong::get).sum();
        stats.put("audioModel", lastAudioModel);
        stats.put("audioTotal", totalAudio);
        Map<String, Long> audioBreakdown = new LinkedHashMap<>();
        audioCounts.forEach((k, v) -> audioBreakdown.put(k, v.get()));
        stats.put("audioBreakdown", audioBreakdown);

        return stats;
    }
}
