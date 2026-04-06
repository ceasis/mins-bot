package com.minsbot;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory analytics: token usage, response times, tool call counts, cost estimates.
 */
@Service
public class AnalyticsService {

    private final Instant startTime = Instant.now();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalInputTokens = new AtomicLong();
    private final AtomicLong totalOutputTokens = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> toolUsage = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Long> recentResponseTimes = new ConcurrentLinkedDeque<>();

    private static final int MAX_RECENT = 100;
    private double inputCostPer1K = 0.01;
    private double outputCostPer1K = 0.03;

    public void recordRequest(long responseTimeMs, int inputTokens, int outputTokens) {
        totalRequests.incrementAndGet();
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        recentResponseTimes.addLast(responseTimeMs);
        while (recentResponseTimes.size() > MAX_RECENT) recentResponseTimes.pollFirst();
    }

    public void recordToolCall(String toolName) {
        toolUsage.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRequests", totalRequests.get());

        long inTok = totalInputTokens.get();
        long outTok = totalOutputTokens.get();
        stats.put("totalInputTokens", inTok);
        stats.put("totalOutputTokens", outTok);

        double cost = (inTok / 1000.0) * inputCostPer1K + (outTok / 1000.0) * outputCostPer1K;
        stats.put("estimatedCost", cost);

        // Response times
        List<Long> times = new ArrayList<>(recentResponseTimes);
        stats.put("recentResponseTimesMs", times);
        if (!times.isEmpty()) {
            long sum = times.stream().mapToLong(Long::longValue).sum();
            stats.put("avgResponseTimeMs", (double) sum / times.size());
            stats.put("minResponseTimeMs", times.stream().mapToLong(Long::longValue).min().orElse(0));
            stats.put("maxResponseTimeMs", times.stream().mapToLong(Long::longValue).max().orElse(0));
        } else {
            stats.put("avgResponseTimeMs", 0.0);
            stats.put("minResponseTimeMs", 0L);
            stats.put("maxResponseTimeMs", 0L);
        }

        // Tool usage
        Map<String, Long> tools = new LinkedHashMap<>();
        toolUsage.forEach((k, v) -> tools.put(k, v.get()));
        stats.put("toolUsage", tools);

        // Uptime
        Duration uptime = Duration.between(startTime, Instant.now());
        long hrs = uptime.toHours();
        long mins = uptime.toMinutesPart();
        long secs = uptime.toSecondsPart();
        stats.put("uptime", hrs + "h " + mins + "m " + secs + "s");

        return stats;
    }

    public void reset() {
        totalRequests.set(0);
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        toolUsage.clear();
        recentResponseTimes.clear();
    }
}
