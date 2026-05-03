package com.minsbot.skills.outreachtracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Logs outreach attempts (email/DM/etc) and tracks reply status.
 * One JSON file per record under memory/outreach/.
 */
@Service
public class OutreachTrackerService {

    private final OutreachTrackerConfig.OutreachTrackerProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private Path dir;

    public OutreachTrackerService(OutreachTrackerConfig.OutreachTrackerProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() throws IOException {
        dir = Paths.get(props.getStorageDir());
        Files.createDirectories(dir);
    }

    public Map<String, Object> log(String recipient, String channel, String subject,
                                   String snippet, String campaign) throws IOException {
        String id = "ot-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", id);
        rec.put("recipient", recipient);
        rec.put("channel", channel);
        rec.put("subject", subject);
        rec.put("snippet", snippet);
        rec.put("campaign", campaign);
        rec.put("sentAt", Instant.now().toString());
        rec.put("status", "sent");
        rec.put("repliedAt", null);
        Files.writeString(dir.resolve(id + ".json"), mapper.writeValueAsString(rec));
        return rec;
    }

    public Map<String, Object> markReplied(String id, String reply) throws IOException {
        Path f = dir.resolve(id + ".json");
        if (!Files.exists(f)) throw new IllegalArgumentException("no such record: " + id);
        Map<String, Object> rec = mapper.readValue(Files.readString(f), Map.class);
        rec.put("status", "replied");
        rec.put("repliedAt", Instant.now().toString());
        if (reply != null) rec.put("reply", reply);
        Files.writeString(f, mapper.writeValueAsString(rec));
        return rec;
    }

    public List<Map<String, Object>> list(String campaign) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                if (!p.toString().endsWith(".json")) continue;
                Map<String, Object> r = mapper.readValue(Files.readString(p), Map.class);
                if (campaign == null || campaign.equals(r.get("campaign"))) out.add(r);
            }
        }
        out.sort((a, b) -> ((String) b.get("sentAt")).compareTo((String) a.get("sentAt")));
        return out;
    }

    public Map<String, Object> stats(String campaign) throws IOException {
        List<Map<String, Object>> all = list(campaign);
        long total = all.size();
        long replied = all.stream().filter(r -> "replied".equals(r.get("status"))).count();
        Map<String, Long> byChannel = new HashMap<>();
        for (Map<String, Object> r : all) {
            byChannel.merge((String) r.get("channel"), 1L, Long::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("campaign", campaign == null ? "all" : campaign);
        result.put("totalSent", total);
        result.put("replied", replied);
        result.put("replyRate", total == 0 ? 0 : Math.round(((double) replied / total) * 1000) / 10.0);
        result.put("byChannel", byChannel);
        return result;
    }
}
