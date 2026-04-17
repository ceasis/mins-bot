package com.minsbot.skills.cronvalidator;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
public class CronValidatorService {

    public Map<String, Object> validate(String cron) {
        try {
            CronExpression.parse(cron);
            return Map.of("cron", cron, "valid", true);
        } catch (IllegalArgumentException e) {
            return Map.of("cron", cron, "valid", false, "error", e.getMessage());
        }
    }

    public Map<String, Object> nextRuns(String cron, int count, String timezone) {
        if (count <= 0) throw new IllegalArgumentException("count > 0");
        CronExpression expr;
        try { expr = CronExpression.parse(cron); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("invalid cron: " + e.getMessage()); }

        ZoneId zone = (timezone == null || timezone.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(timezone);
        LocalDateTime cursor = LocalDateTime.now(zone);
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDateTime next = expr.next(cursor);
            if (next == null) break;
            runs.add(next.atZone(zone).toString());
            cursor = next;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cron", cron);
        out.put("timezone", zone.toString());
        out.put("valid", true);
        out.put("nextRuns", runs);
        out.put("description", describe(cron));
        return out;
    }

    private static String describe(String cron) {
        String[] parts = cron.trim().split("\\s+");
        if (parts.length == 6) {
            return String.format("at second=%s minute=%s hour=%s day-of-month=%s month=%s day-of-week=%s",
                    parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]);
        } else if (parts.length == 5) {
            return String.format("at minute=%s hour=%s day-of-month=%s month=%s day-of-week=%s",
                    parts[0], parts[1], parts[2], parts[3], parts[4]);
        }
        return "Spring 6-field cron format expected";
    }
}
