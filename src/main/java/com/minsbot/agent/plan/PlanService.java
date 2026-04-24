package com.minsbot.agent.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable, file-backed plan storage. The model writes its plan via {@link PlanTool};
 * the UI polls it via {@link PlanController}. One active plan at a time — overwriting
 * is the point (forcing the model to re-state the full list each turn is what keeps
 * the plan honest and current).
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    public enum Status { pending, in_progress, completed }

    public record TodoItem(String content, String activeForm, Status status) {
        public TodoItem {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content is required");
            }
            if (activeForm == null || activeForm.isBlank()) {
                activeForm = content;
            }
            if (status == null) status = Status.pending;
        }
    }

    public record Plan(List<TodoItem> items, Instant updatedAt) {
        public static Plan empty() { return new Plan(List.of(), Instant.EPOCH); }
    }

    private static final Path PLAN_FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "plan.json");

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private volatile Plan current = loadFromDisk();

    public synchronized Plan get() { return current; }

    public synchronized Plan write(List<TodoItem> items) {
        List<TodoItem> copy = new ArrayList<>(items == null ? List.of() : items);
        Plan plan = new Plan(List.copyOf(copy), Instant.now());
        persist(plan);
        this.current = plan;
        return plan;
    }

    public synchronized Plan clear() {
        Plan empty = new Plan(List.of(), Instant.now());
        persist(empty);
        this.current = empty;
        return empty;
    }

    private void persist(Plan plan) {
        try {
            Files.createDirectories(PLAN_FILE.getParent());
            Files.writeString(PLAN_FILE, mapper.writeValueAsString(plan));
        } catch (Exception e) {
            log.warn("Failed to persist plan: {}", e.toString());
        }
    }

    private Plan loadFromDisk() {
        try {
            if (!Files.isRegularFile(PLAN_FILE)) return Plan.empty();
            String json = Files.readString(PLAN_FILE);
            // Tolerant read — tolerate missing fields / older shape.
            var node = mapper.readTree(json);
            List<TodoItem> items = new ArrayList<>();
            if (node.has("items") && node.get("items").isArray()) {
                items = mapper.convertValue(node.get("items"),
                        new TypeReference<List<TodoItem>>() {});
            }
            Instant updated = Instant.EPOCH;
            if (node.has("updatedAt") && !node.get("updatedAt").isNull()) {
                try { updated = Instant.parse(node.get("updatedAt").asText()); }
                catch (Exception ignored) {}
            }
            return new Plan(List.copyOf(items), updated);
        } catch (Exception e) {
            log.warn("Failed to load plan: {} — starting empty", e.toString());
            return Plan.empty();
        }
    }
}
