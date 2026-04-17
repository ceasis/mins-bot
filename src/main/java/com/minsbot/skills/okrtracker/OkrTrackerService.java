package com.minsbot.skills.okrtracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

@Service
public class OkrTrackerService {

    private final OkrTrackerConfig.OkrTrackerProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public OkrTrackerService(OkrTrackerConfig.OkrTrackerProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> create(String objective, List<Map<String, Object>> keyResults, String owner) throws IOException {
        if (objective == null || objective.isBlank()) throw new IllegalArgumentException("objective required");
        String id = Long.toString(System.currentTimeMillis()) + "-" + Integer.toHexString(new Random().nextInt(0xFFFF));
        Map<String, Object> okr = new LinkedHashMap<>();
        okr.put("id", id);
        okr.put("objective", objective);
        okr.put("owner", owner == null ? "" : owner);
        okr.put("keyResults", normalizeKrs(keyResults));
        okr.put("createdAt", Instant.now().toString());
        okr.put("updatedAt", Instant.now().toString());
        okr.put("progress", computeProgress(normalizeKrs(keyResults)));
        write(id, okr);
        return okr;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> update(String id, Map<String, Object> patch) throws IOException {
        Map<String, Object> okr = get(id);
        if (patch.containsKey("objective")) okr.put("objective", patch.get("objective"));
        if (patch.containsKey("owner")) okr.put("owner", patch.get("owner"));
        if (patch.containsKey("keyResults")) {
            List<Map<String, Object>> krs = normalizeKrs((List<Map<String, Object>>) patch.get("keyResults"));
            okr.put("keyResults", krs);
            okr.put("progress", computeProgress(krs));
        }
        okr.put("updatedAt", Instant.now().toString());
        write(id, okr);
        return okr;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String id) throws IOException {
        Path p = path(id);
        if (!Files.exists(p)) throw new IllegalArgumentException("OKR not found: " + id);
        return mapper.readValue(Files.readString(p), Map.class);
    }

    public void delete(String id) throws IOException {
        Path p = path(id);
        if (!Files.exists(p)) throw new IllegalArgumentException("OKR not found: " + id);
        Files.delete(p);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list() throws IOException {
        Path dir = dir();
        if (!Files.isDirectory(dir)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                try { out.add(mapper.readValue(Files.readString(p), Map.class)); } catch (Exception ignored) {}
            }
        }
        out.sort((a, b) -> String.valueOf(b.get("updatedAt")).compareTo(String.valueOf(a.get("updatedAt"))));
        return out;
    }

    private static List<Map<String, Object>> normalizeKrs(List<Map<String, Object>> krs) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (krs == null) return out;
        for (Map<String, Object> kr : krs) {
            double target = ((Number) kr.getOrDefault("target", 100)).doubleValue();
            double current = ((Number) kr.getOrDefault("current", 0)).doubleValue();
            double pct = target == 0 ? 0 : Math.min(100, current / target * 100);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("name", kr.getOrDefault("name", ""));
            n.put("target", target);
            n.put("current", current);
            n.put("progressPct", Math.round(pct * 100.0) / 100.0);
            out.add(n);
        }
        return out;
    }

    private static double computeProgress(List<Map<String, Object>> krs) {
        if (krs.isEmpty()) return 0;
        double sum = 0;
        for (Map<String, Object> kr : krs) sum += ((Number) kr.get("progressPct")).doubleValue();
        return Math.round((sum / krs.size()) * 100.0) / 100.0;
    }

    private void write(String id, Map<String, Object> okr) throws IOException {
        Path dir = dir();
        Files.createDirectories(dir);
        Files.writeString(path(id), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(okr));
    }

    private Path path(String id) {
        if (!id.matches("[a-zA-Z0-9._-]+")) throw new IllegalArgumentException("Invalid id");
        return dir().resolve(id + ".json");
    }

    private Path dir() { return Paths.get(properties.getStorageDir()).toAbsolutePath().normalize(); }
}
