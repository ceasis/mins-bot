package com.minsbot.agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persists a JSON log of completed code-generation jobs to
 * {@code <user.home>/mins_bot_data/code-gen-history.json}.
 *
 * <p>Gives the agent a memory of past projects across bot restarts, so
 * "continue rich-app" can resolve to the folder and GitHub repo from an
 * earlier session.</p>
 */
@Service
public class ProjectHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ProjectHistoryService.class);
    private static final int MAX_RECORDS = 200;
    private static final Pattern GH_URL = Pattern.compile("https://github\\.com/[\\w.-]+/[\\w.-]+");

    private final Path file = Path.of(System.getProperty("user.home"), "mins_bot_data", "code-gen-history.json");
    private final ObjectMapper mapper = new ObjectMapper();
    private final CopyOnWriteArrayList<ProjectRecord> records = new CopyOnWriteArrayList<>();

    @PostConstruct
    void load() {
        try {
            if (!Files.exists(file)) return;
            ProjectRecord[] arr = mapper.readValue(Files.readAllBytes(file), ProjectRecord[].class);
            records.addAll(List.of(arr));
            log.info("[ProjectHistory] Loaded {} record(s) from {}", records.size(), file);
        } catch (Exception e) {
            log.warn("[ProjectHistory] Failed to load {}: {}", file, e.getMessage());
        }
    }

    /** Called by the job service after a run completes (success or fail). */
    public synchronized void record(String jobId, String mode, String task, String workingDir,
                                    String status, String result) {
        try {
            if (workingDir == null || workingDir.isBlank()) return;
            Path dir = Path.of(workingDir);
            String name = dir.getFileName() == null ? workingDir : dir.getFileName().toString();
            String github = null;
            if (result != null) {
                Matcher m = GH_URL.matcher(result);
                if (m.find()) github = m.group();
            }
            ProjectRecord rec = new ProjectRecord();
            rec.jobId = jobId;
            rec.projectName = name;
            rec.workingDir = workingDir;
            rec.mode = mode;
            rec.task = task == null ? "" : (task.length() > 500 ? task.substring(0, 500) + "..." : task);
            rec.githubUrl = github;
            rec.status = status;
            rec.completedAt = Instant.now().toString();

            // De-duplicate by workingDir — keep the latest only.
            records.removeIf(r -> Objects.equals(r.workingDir, rec.workingDir));
            records.add(rec);
            if (records.size() > MAX_RECORDS) {
                records.sort(Comparator.comparing(r -> r.completedAt == null ? "" : r.completedAt));
                while (records.size() > MAX_RECORDS) records.remove(0);
            }
            persist();
        } catch (Exception e) {
            log.warn("[ProjectHistory] record() failed", e);
        }
    }

    /** Most recent first. */
    public List<ProjectRecord> list() {
        List<ProjectRecord> copy = new ArrayList<>(records);
        copy.sort(Comparator.comparing((ProjectRecord r) -> r.completedAt == null ? "" : r.completedAt).reversed());
        return copy;
    }

    /** Fuzzy lookup: exact name match wins; otherwise case-insensitive contains. */
    public ProjectRecord findByName(String name) {
        if (name == null || name.isBlank()) return null;
        String needle = name.trim().toLowerCase(Locale.ROOT);
        ProjectRecord best = null;
        String bestLower = "";
        for (ProjectRecord r : list()) {
            if (r.projectName == null) continue;
            String pn = r.projectName.toLowerCase(Locale.ROOT);
            if (pn.equals(needle)) return r;
            if (pn.contains(needle) && (best == null || pn.length() < bestLower.length())) {
                best = r;
                bestLower = pn;
            }
        }
        return best;
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            List<ProjectRecord> sorted = list();
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(sorted).getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
        } catch (Exception e) {
            log.warn("[ProjectHistory] persist() failed", e);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectRecord {
        public String jobId;
        public String projectName;
        public String workingDir;
        public String mode;
        public String task;
        public String githubUrl;
        public String status;
        public String completedAt;
    }
}
