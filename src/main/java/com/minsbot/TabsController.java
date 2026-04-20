package com.minsbot;

import com.minsbot.agent.tools.CronConfigTools;
import com.minsbot.agent.tools.DirectivesTools;
import org.springframework.web.bind.annotation.*;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST controller for the Schedules, Todo, and Directives tabs in the web UI.
 * Reads data from the same ~/mins_bot_data/ files that the AI tools use.
 */
@RestController
@RequestMapping("/api/tabs")
public class TabsController {

    private final CronConfigTools cronConfigTools;
    private final DirectivesTools directivesTools;

    private static final Path TODO_FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "todolist.txt");

    public TabsController(CronConfigTools cronConfigTools, DirectivesTools directivesTools) {
        this.cronConfigTools = cronConfigTools;
        this.directivesTools = directivesTools;
    }

    // ─── Schedules ───────────────────────────────────────────────────────

    @GetMapping("/schedules")
    public List<Map<String, Object>> getSchedules() {
        String raw = cronConfigTools.getCronConfig();
        return parseCronSections(raw);
    }

    private List<Map<String, Object>> parseCronSections(String raw) {
        List<Map<String, Object>> sections = new ArrayList<>();
        if (raw == null || raw.isBlank() || raw.startsWith("No cron") || raw.startsWith("Failed")) {
            return sections;
        }

        String currentSection = null;
        List<String> currentEntries = new ArrayList<>();

        for (String line : raw.split("\n")) {
            if (line.startsWith("## ")) {
                if (currentSection != null) {
                    addSection(sections, currentSection, currentEntries);
                }
                currentSection = line.substring(3).trim();
                currentEntries = new ArrayList<>();
            } else if (line.startsWith("- ") && !line.trim().equals("-")) {
                currentEntries.add(line.substring(2).trim());
            }
        }
        if (currentSection != null) {
            addSection(sections, currentSection, currentEntries);
        }
        return sections;
    }

    @PostMapping("/schedules")
    public Map<String, String> addScheduleEntry(@RequestBody Map<String, String> body) {
        String section = body.get("section");
        String entry = body.get("entry");
        if (section == null || section.isBlank()) return Map.of("error", "Section is required");
        if (entry == null || entry.isBlank()) return Map.of("error", "Entry is required");
        String result = cronConfigTools.appendCronEntry(section.trim(), entry.trim());
        return Map.of("result", result);
    }

    @PutMapping("/schedules")
    public Map<String, String> updateScheduleEntry(@RequestBody Map<String, String> body) {
        String section = body.get("section");
        String oldEntry = body.get("oldEntry");
        String newEntry = body.get("newEntry");
        if (section == null || oldEntry == null || newEntry == null) {
            return Map.of("error", "section, oldEntry, and newEntry are required");
        }
        // Remove old, add new
        cronConfigTools.removeCronEntry(section.trim(), oldEntry.trim());
        String result = cronConfigTools.appendCronEntry(section.trim(), newEntry.trim());
        return Map.of("result", result);
    }

    @DeleteMapping("/schedules")
    public Map<String, String> deleteScheduleEntry(@RequestBody Map<String, String> body) {
        String section = body.get("section");
        String entry = body.get("entry");
        if (section == null || entry == null) return Map.of("error", "section and entry are required");
        String result = cronConfigTools.removeCronEntry(section.trim(), entry.trim());
        return Map.of("result", result);
    }

    private void addSection(List<Map<String, Object>> sections, String name, List<String> entries) {
        if (entries.isEmpty()) return;
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("section", name);
        section.put("entries", entries);
        sections.add(section);
    }

    // ─── Todo List ───────────────────────────────────────────────────────

    private static final Pattern TASK_HEADER = Pattern.compile(
            "---\\s*Task:\\s*\"(.+?)\"\\s*\\|\\s*(.+?)\\s*---");
    private static final Pattern STEP_LINE = Pattern.compile(
            "\\[(PENDING|DONE)]\\s*(\\d+)\\.\\s*(.+)");

    @GetMapping("/todos")
    public List<Map<String, Object>> getTodos() {
        List<Map<String, Object>> tasks = new ArrayList<>();
        try {
            if (!Files.exists(TODO_FILE)) return tasks;
            List<String> lines = Files.readAllLines(TODO_FILE, StandardCharsets.UTF_8);

            Map<String, Object> currentTask = null;
            List<Map<String, Object>> currentSteps = null;

            for (String line : lines) {
                Matcher taskMatch = TASK_HEADER.matcher(line);
                if (taskMatch.find()) {
                    if (currentTask != null) {
                        currentTask.put("steps", currentSteps);
                        tasks.add(currentTask);
                    }
                    currentTask = new LinkedHashMap<>();
                    currentTask.put("title", taskMatch.group(1));
                    currentTask.put("timestamp", taskMatch.group(2));
                    currentSteps = new ArrayList<>();
                    continue;
                }

                Matcher stepMatch = STEP_LINE.matcher(line);
                if (stepMatch.find() && currentSteps != null) {
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("status", stepMatch.group(1));
                    step.put("num", Integer.parseInt(stepMatch.group(2)));
                    step.put("text", stepMatch.group(3).trim());
                    currentSteps.add(step);
                }
            }
            if (currentTask != null) {
                currentTask.put("steps", currentSteps);
                tasks.add(currentTask);
            }
        } catch (Exception ignored) {}

        // Reverse: latest first
        Collections.reverse(tasks);
        return tasks;
    }

    /** Parses todolist.txt into an ordered list of raw task blocks (title, timestamp, body lines). */
    private List<TaskBlock> readTaskBlocks() {
        List<TaskBlock> blocks = new ArrayList<>();
        if (!Files.exists(TODO_FILE)) return blocks;
        try {
            List<String> lines = Files.readAllLines(TODO_FILE, StandardCharsets.UTF_8);
            TaskBlock current = null;
            for (String line : lines) {
                Matcher m = TASK_HEADER.matcher(line);
                if (m.find()) {
                    if (current != null) blocks.add(current);
                    current = new TaskBlock(m.group(1), m.group(2), new ArrayList<>());
                    current.bodyLines.add(line);
                } else if (current != null) {
                    current.bodyLines.add(line);
                }
            }
            if (current != null) blocks.add(current);
        } catch (Exception ignored) {}
        return blocks;
    }

    /** Resolves a UI index (latest-first) to its slot in the forward-ordered task list. */
    private int forwardIndex(int uiIndex, int total) {
        return total - 1 - uiIndex;
    }

    @DeleteMapping("/todos/{index}")
    public Map<String, String> deleteTodo(@PathVariable int index) {
        List<TaskBlock> blocks = readTaskBlocks();
        int fwd = forwardIndex(index, blocks.size());
        if (fwd < 0 || fwd >= blocks.size()) return Map.of("error", "Index out of range");
        blocks.remove(fwd);
        return writeBlocks(blocks);
    }

    @DeleteMapping("/todos")
    public Map<String, String> clearAllTodos() {
        try {
            if (Files.exists(TODO_FILE)) Files.writeString(TODO_FILE, "", StandardCharsets.UTF_8);
            return Map.of("result", "Cleared all tasks");
        } catch (Exception e) {
            return Map.of("error", "Failed to clear: " + e.getMessage());
        }
    }

    @GetMapping("/todos/{index}/download")
    public ResponseEntity<byte[]> downloadTodo(@PathVariable int index) {
        List<TaskBlock> blocks = readTaskBlocks();
        int fwd = forwardIndex(index, blocks.size());
        if (fwd < 0 || fwd >= blocks.size()) return ResponseEntity.notFound().build();
        TaskBlock block = blocks.get(fwd);
        String content = String.join("\n", block.bodyLines);
        String filename = safeFileName(block.title) + ".txt";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(content.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/todos/download")
    public ResponseEntity<byte[]> downloadAllTodos() {
        byte[] bytes;
        try {
            bytes = Files.exists(TODO_FILE) ? Files.readAllBytes(TODO_FILE) : new byte[0];
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"todolist.txt\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(bytes);
    }

    @PostMapping("/todos/{index}/as-skill")
    public Map<String, String> saveTodoAsSkill(@PathVariable int index,
                                               @RequestBody(required = false) Map<String, String> body) {
        List<TaskBlock> blocks = readTaskBlocks();
        int fwd = forwardIndex(index, blocks.size());
        if (fwd < 0 || fwd >= blocks.size()) return Map.of("error", "Index out of range");
        TaskBlock block = blocks.get(fwd);

        String name = body != null ? body.getOrDefault("name", "") : "";
        if (name.isBlank()) name = block.title;
        String fileName = safeFileName(name);
        if (fileName.isBlank()) return Map.of("error", "Invalid skill name");

        Path skillsDir = Paths.get(System.getProperty("user.home"), "mins_bot_data", "skills");
        Path skillFile = skillsDir.resolve(fileName + ".md");

        StringBuilder md = new StringBuilder();
        md.append("# ").append(block.title).append("\n\n");
        String description = body != null ? body.getOrDefault("description", "") : "";
        if (description.isBlank()) {
            description = "Skill generated from todo task on " + block.timestamp + ".";
        }
        md.append(description).append("\n\n");
        md.append("## Steps\n");
        int n = 1;
        for (String line : block.bodyLines) {
            Matcher sm = STEP_LINE.matcher(line);
            if (sm.find()) {
                md.append(n++).append(". ").append(sm.group(3).trim()).append("\n");
            }
        }

        try {
            Files.createDirectories(skillsDir);
            Files.writeString(skillFile, md.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return Map.of("result", "Saved skill: " + skillFile.getFileName(),
                          "path", skillFile.toString());
        } catch (Exception e) {
            return Map.of("error", "Failed to save: " + e.getMessage());
        }
    }

    private Map<String, String> writeBlocks(List<TaskBlock> blocks) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < blocks.size(); i++) {
                if (i > 0) sb.append("\n");
                for (String line : blocks.get(i).bodyLines) {
                    sb.append(line).append("\n");
                }
            }
            Files.writeString(TODO_FILE, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return Map.of("result", "OK");
        } catch (Exception e) {
            return Map.of("error", "Failed to write: " + e.getMessage());
        }
    }

    private String safeFileName(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase().trim();
        String clean = lower.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (clean.length() > 60) clean = clean.substring(0, 60);
        return clean;
    }

    private record TaskBlock(String title, String timestamp, List<String> bodyLines) {}

    // ─── Directives ──────────────────────────────────────────────────────

    @GetMapping("/directives")
    public List<String> getDirectives() {
        String raw = directivesTools.getDirectives();
        if (raw == null || raw.isBlank() || raw.startsWith("No directives") || raw.startsWith("Failed")) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (!line.isBlank()) result.add(line);
        }
        return result;
    }

    @PostMapping("/directives")
    public Map<String, String> addDirective(@RequestBody Map<String, String> body) {
        String directive = body.get("directive");
        if (directive == null || directive.isBlank()) {
            return Map.of("error", "Directive text is required");
        }
        String result = directivesTools.appendDirective(directive.trim());
        return Map.of("result", result);
    }

    @DeleteMapping("/directives/{position}")
    public Map<String, String> removeDirective(@PathVariable int position) {
        String result = directivesTools.removeDirective(position);
        return Map.of("result", result);
    }
}
