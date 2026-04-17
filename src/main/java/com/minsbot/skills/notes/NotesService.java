package com.minsbot.skills.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

@Service
public class NotesService {

    private final NotesConfig.NotesProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public NotesService(NotesConfig.NotesProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> create(String title, String body, List<String> tags) throws IOException {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
        String id = Long.toString(System.currentTimeMillis()) + "-" + Integer.toHexString(new Random().nextInt(0xFFFF));
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", id);
        note.put("title", title);
        note.put("body", body == null ? "" : body);
        note.put("tags", tags == null ? List.of() : tags);
        note.put("createdAt", Instant.now().toString());
        note.put("updatedAt", Instant.now().toString());
        writeNote(id, note);
        return note;
    }

    public Map<String, Object> get(String id) throws IOException {
        Path p = notePath(id);
        if (!Files.exists(p)) throw new IllegalArgumentException("Note not found: " + id);
        return mapper.readValue(Files.readString(p), Map.class);
    }

    public Map<String, Object> update(String id, String title, String body, List<String> tags) throws IOException {
        Map<String, Object> note = get(id);
        if (title != null) note.put("title", title);
        if (body != null) note.put("body", body);
        if (tags != null) note.put("tags", tags);
        note.put("updatedAt", Instant.now().toString());
        writeNote(id, note);
        return note;
    }

    public void delete(String id) throws IOException {
        Path p = notePath(id);
        if (!Files.exists(p)) throw new IllegalArgumentException("Note not found: " + id);
        Files.delete(p);
    }

    public List<Map<String, Object>> list() throws IOException {
        Path dir = storageDir();
        if (!Files.isDirectory(dir)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                try {
                    Map<String, Object> note = mapper.readValue(Files.readString(p), Map.class);
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("id", note.get("id"));
                    summary.put("title", note.get("title"));
                    summary.put("tags", note.get("tags"));
                    summary.put("updatedAt", note.get("updatedAt"));
                    out.add(summary);
                } catch (Exception ignored) {}
            }
        }
        out.sort((a, b) -> String.valueOf(b.get("updatedAt")).compareTo(String.valueOf(a.get("updatedAt"))));
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(String query, String tag) throws IOException {
        Path dir = storageDir();
        if (!Files.isDirectory(dir)) return List.of();
        String q = query == null ? null : query.toLowerCase();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                try {
                    Map<String, Object> note = mapper.readValue(Files.readString(p), Map.class);
                    boolean matchQ = q == null
                            || String.valueOf(note.get("title")).toLowerCase().contains(q)
                            || String.valueOf(note.get("body")).toLowerCase().contains(q);
                    boolean matchT = true;
                    if (tag != null && !tag.isBlank()) {
                        Object tags = note.get("tags");
                        matchT = tags instanceof List<?> list && list.stream().anyMatch(t -> tag.equalsIgnoreCase(String.valueOf(t)));
                    }
                    if (matchQ && matchT) out.add(note);
                } catch (Exception ignored) {}
            }
        }
        out.sort((a, b) -> String.valueOf(b.get("updatedAt")).compareTo(String.valueOf(a.get("updatedAt"))));
        return out;
    }

    private void writeNote(String id, Map<String, Object> note) throws IOException {
        Path dir = storageDir();
        Files.createDirectories(dir);
        Files.writeString(notePath(id), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(note));
    }

    private Path notePath(String id) {
        if (!id.matches("[a-zA-Z0-9._-]+")) throw new IllegalArgumentException("Invalid id");
        return storageDir().resolve(id + ".json");
    }

    private Path storageDir() {
        return Paths.get(properties.getStorageDir()).toAbsolutePath().normalize();
    }
}
