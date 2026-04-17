package com.minsbot.skills.slacalc;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SlaCalcService {

    public Map<String, Object> uptimeToDowntime(double uptimePct) {
        if (uptimePct < 0 || uptimePct > 100) throw new IllegalArgumentException("uptimePct must be 0-100");
        double downFraction = (100 - uptimePct) / 100.0;

        long yearSec = 31_536_000L;
        long monthSec = 2_592_000L; // 30-day month
        long weekSec = 604_800L;
        long daySec = 86_400L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uptimePct", uptimePct);
        out.put("downtimeAllowed", Map.of(
                "perYear", humanize((long) (yearSec * downFraction)),
                "perMonth30d", humanize((long) (monthSec * downFraction)),
                "perWeek", humanize((long) (weekSec * downFraction)),
                "perDay", humanize((long) (daySec * downFraction))
        ));
        return out;
    }

    public Map<String, Object> downtimeToUptime(long downtimeSeconds, String period) {
        long periodSec = periodSeconds(period);
        double uptimePct = (1 - (double) downtimeSeconds / periodSec) * 100.0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("downtimeSeconds", downtimeSeconds);
        out.put("downtime", humanize(downtimeSeconds));
        out.put("period", period);
        out.put("uptimePct", Math.round(uptimePct * 1_000_000.0) / 1_000_000.0);
        return out;
    }

    public Map<String, Object> compositeSla(List<Double> componentUptimes) {
        if (componentUptimes == null || componentUptimes.isEmpty()) throw new IllegalArgumentException("components required");
        double product = 1.0;
        for (double u : componentUptimes) {
            if (u < 0 || u > 100) throw new IllegalArgumentException("each uptime must be 0-100");
            product *= u / 100.0;
        }
        double compositePct = product * 100.0;
        return Map.of("components", componentUptimes, "compositeUptimePct", Math.round(compositePct * 1_000_000.0) / 1_000_000.0);
    }

    private static long periodSeconds(String period) {
        return switch (period.toLowerCase()) {
            case "year" -> 31_536_000L;
            case "month" -> 2_592_000L;
            case "week" -> 604_800L;
            case "day" -> 86_400L;
            case "hour" -> 3600L;
            default -> throw new IllegalArgumentException("period must be year/month/week/day/hour");
        };
    }

    private static String humanize(long totalSec) {
        long days = totalSec / 86_400; totalSec %= 86_400;
        long hours = totalSec / 3600; totalSec %= 3600;
        long mins = totalSec / 60; long secs = totalSec % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || sb.length() > 0) sb.append(hours).append("h ");
        if (mins > 0 || sb.length() > 0) sb.append(mins).append("m ");
        sb.append(secs).append("s");
        return sb.toString().trim();
    }
}
