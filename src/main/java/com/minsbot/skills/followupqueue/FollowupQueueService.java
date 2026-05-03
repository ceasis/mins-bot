package com.minsbot.skills.followupqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Tracks leads + last-contact date, computes due follow-ups based on a
 * configured cadence (default: day 3, 7, 14, 30).
 */
@Service
public class FollowupQueueService {

    private final FollowupQueueConfig.FollowupQueueProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private Path dir;

    public FollowupQueueService(FollowupQueueConfig.FollowupQueueProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() throws IOException {
        dir = Paths.get(props.getStorageDir());
        Files.createDirectories(dir);
    }

    public Map<String, Object> add(String leadName, String contact, String channel,
                                   String firstContacted, String notes) throws IOException {
        String id = "fu-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", id);
        rec.put("leadName", leadName);
        rec.put("contact", contact);
        rec.put("channel", channel);
        rec.put("firstContacted", firstContacted == null ? LocalDate.now().toString() : firstContacted);
        rec.put("lastContacted", rec.get("firstContacted"));
        rec.put("notes", notes);
        rec.put("status", "active");
        rec.put("touchCount", 1);
        Files.writeString(dir.resolve(id + ".json"), mapper.writeValueAsString(rec));
        return rec;
    }

    public Map<String, Object> touch(String id) throws IOException {
        Path f = dir.resolve(id + ".json");
        if (!Files.exists(f)) throw new IllegalArgumentException("no such record: " + id);
        Map<String, Object> rec = mapper.readValue(Files.readString(f), Map.class);
        rec.put("lastContacted", LocalDate.now().toString());
        rec.put("touchCount", ((Number) rec.getOrDefault("touchCount", 0)).intValue() + 1);
        Files.writeString(f, mapper.writeValueAsString(rec));
        return rec;
    }

    public Map<String, Object> close(String id, String outcome) throws IOException {
        Path f = dir.resolve(id + ".json");
        if (!Files.exists(f)) throw new IllegalArgumentException("no such record: " + id);
        Map<String, Object> rec = mapper.readValue(Files.readString(f), Map.class);
        rec.put("status", "closed");
        rec.put("outcome", outcome);
        rec.put("closedAt", LocalDate.now().toString());
        Files.writeString(f, mapper.writeValueAsString(rec));
        return rec;
    }

    public List<Map<String, Object>> due() throws IOException {
        LocalDate today = LocalDate.now();
        int[] cadence = props.getCadenceDays();
        List<Map<String, Object>> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                if (!p.toString().endsWith(".json")) continue;
                Map<String, Object> r = mapper.readValue(Files.readString(p), Map.class);
                if (!"active".equals(r.get("status"))) continue;
                LocalDate first = LocalDate.parse((String) r.get("firstContacted"));
                LocalDate last = LocalDate.parse((String) r.get("lastContacted"));
                long daysSinceFirst = today.toEpochDay() - first.toEpochDay();
                long daysSinceLast = today.toEpochDay() - last.toEpochDay();
                int touches = ((Number) r.getOrDefault("touchCount", 1)).intValue();
                int nextStep = touches - 1;
                if (nextStep < cadence.length && daysSinceFirst >= cadence[nextStep] && daysSinceLast >= 1) {
                    r.put("daysSinceLast", daysSinceLast);
                    r.put("nextTouch", "touch #" + (touches + 1) + " (cadence day " + cadence[nextStep] + ")");
                    r.put("template", suggestTemplate(touches));
                    out.add(r);
                }
            }
        }
        out.sort((a, b) -> Long.compare(((Number) b.get("daysSinceLast")).longValue(),
                ((Number) a.get("daysSinceLast")).longValue()));
        return out;
    }

    private static String suggestTemplate(int touchNum) {
        return switch (touchNum) {
            case 1 -> "Bumping this — thought it might've gotten buried. Worth a 15-min chat?";
            case 2 -> "Last nudge from me — if it's not a fit just say 'no' and I'll stop. Otherwise happy to send a quick scope.";
            case 3 -> "Closing the file on this one. If you ever want to revisit, you know where to find me.";
            default -> "Quick check-in — anything new on your end?";
        };
    }
}
