package com.minsbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TranscriptService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptService.class);
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter YEAR_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy_MMM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("d");
    private static final int MEMORY_SIZE = 500;

    private Path historyDir;

    /** In-memory ring buffer of the last 100 messages. */
    private final LinkedList<String> recentMemory = new LinkedList<>();

    /** Lazy to avoid circular resolution if the publisher later consumes transcript APIs. */
    @Autowired(required = false)
    @Lazy
    private ChatEventPublisher chatEventPublisher;

    /** Optional — lets us trigger the taskbar icon "talking" animation when the bot speaks. */
    @Autowired(required = false)
    @Lazy
    private IconAnimator iconAnimator;

    private static final Pattern LINE_PATTERN =
            Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})] ([^:]+): (.+)$");

    @PostConstruct
    public void init() throws IOException {
        historyDir = Paths.get(System.getProperty("user.home"), "mins_bot_data", "mins_bot_history");
        Files.createDirectories(historyDir);
        log.info("Chat history directory: {}", historyDir);
        loadLatestHistory();
        archivePreviousSessionOnStartup();
    }

    /**
     * Each app launch archives whatever was in the recent in-memory transcript
     * (loaded from the most recent daily file above) under a timestamped name,
     * then clears it so this session starts with a clean chat. Skipped when
     * there was nothing to archive.
     */
    private void archivePreviousSessionOnStartup() {
        int loaded;
        synchronized (recentMemory) { loaded = recentMemory.size(); }
        if (loaded == 0) return;
        String name = "session-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"));
        Path archived = archiveHistory(name);
        if (archived != null) {
            log.info("[Transcript] Auto-archived previous session ({} messages) on startup → {}",
                    loaded, archived.getFileName());
        }
    }

    /** Loads the most recent history file into the in-memory ring buffer on startup. */
    private void loadLatestHistory() {
        try (Stream<Path> walk = Files.walk(historyDir)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".txt")).sorted(Comparator.reverseOrder()).toList();
            if (files.isEmpty()) return;
            Path latest = files.get(0);
            List<String> lines = Files.readAllLines(latest, StandardCharsets.UTF_8);
            synchronized (recentMemory) {
                for (String line : lines) {
                    if (!line.isBlank()) {
                        recentMemory.addLast(line);
                        while (recentMemory.size() > MEMORY_SIZE) {
                            recentMemory.removeFirst();
                        }
                    }
                }
            }
            log.info("Loaded {} history lines from {}", recentMemory.size(), latest.getFileName());
        } catch (IOException e) {
            log.warn("Could not load chat history on startup: {}", e.getMessage());
        }
    }

    /**
     * Save the current in-memory transcript to a named archive file under
     * {@code ~/mins_bot_data/mins_bot_history/archives/<slug>_<timestamp>.txt} and clear
     * the in-memory buffer. Daily history files are left untouched.
     *
     * @param name user-supplied name (sanitized to a safe filename)
     * @return the archived file path, or null if there was nothing to archive
     */
    public Path archiveHistory(String name) {
        List<String> snapshot;
        synchronized (recentMemory) {
            if (recentMemory.isEmpty()) return null;
            snapshot = new ArrayList<>(recentMemory);
        }

        String safe = (name == null ? "untitled" : name).trim();
        safe = safe.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (safe.isEmpty()) safe = "untitled";
        if (safe.length() > 60) safe = safe.substring(0, 60).trim();
        safe = safe.replace(' ', '_');

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path archiveDir = historyDir.resolve("archives");
        Path target = archiveDir.resolve(safe + "_" + ts + ".txt");
        try {
            Files.createDirectories(archiveDir);
            StringBuilder body = new StringBuilder();
            body.append("# ").append(name == null ? "Untitled" : name.trim()).append("\n");
            body.append("# archived: ").append(LocalDateTime.now().format(TIME_FMT)).append("\n");
            body.append("# messages: ").append(snapshot.size()).append("\n\n");
            for (String line : snapshot) body.append(line).append("\n");
            Files.writeString(target, body.toString(), StandardCharsets.UTF_8);
            log.info("[Transcript] Archived {} messages → {}", snapshot.size(), target.getFileName());
        } catch (IOException e) {
            log.warn("[Transcript] archiveHistory write failed: {}", e.getMessage());
            return null;
        }

        synchronized (recentMemory) {
            recentMemory.clear();
        }
        if (chatEventPublisher != null) {
            try { chatEventPublisher.publishCleared(); } catch (Exception ignored) {}
        }
        return target;
    }

    /** List archived conversations (name + path + size + timestamp), newest first. */
    public List<Map<String, Object>> listArchives() {
        List<Map<String, Object>> out = new ArrayList<>();
        Path archiveDir = historyDir.resolve("archives");
        if (!Files.isDirectory(archiveDir)) return out;
        try (Stream<Path> walk = Files.list(archiveDir)) {
            walk.filter(p -> p.toString().endsWith(".txt"))
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    String fn = p.getFileName().toString();
                    entry.put("file", fn);
                    entry.put("path", p.toString());
                    try { entry.put("size", Files.size(p)); } catch (IOException ignored) { entry.put("size", 0); }
                    try { entry.put("modified", Files.getLastModifiedTime(p).toInstant().toString()); } catch (IOException ignored) {}
                    // Try to extract the human name from the first "# Name" line in the file
                    try {
                        List<String> first = Files.readAllLines(p, StandardCharsets.UTF_8).stream().limit(1).toList();
                        if (!first.isEmpty() && first.get(0).startsWith("# ")) {
                            entry.put("name", first.get(0).substring(2).trim());
                        } else {
                            entry.put("name", fn.replaceAll("_\\d{8}_\\d{6}\\.txt$", "").replace('_', ' '));
                        }
                    } catch (IOException ignored) {
                        entry.put("name", fn);
                    }
                    out.add(entry);
                });
        } catch (IOException e) {
            log.warn("[Transcript] listArchives failed: {}", e.getMessage());
        }
        return out;
    }

    /** Clear the in-memory chat history ring buffer (does NOT delete history files). */
    public void clearHistory() {
        synchronized (recentMemory) {
            recentMemory.clear();
        }
        log.info("[Transcript] In-memory history cleared.");
        if (chatEventPublisher != null) chatEventPublisher.publishCleared();
    }

    /**
     * Returns recent history as structured maps for the REST API.
     * Each entry: {speaker, text, time, isUser}.
     */
    public List<Map<String, Object>> getStructuredHistory() {
        List<Map<String, Object>> result = new ArrayList<>();
        synchronized (recentMemory) {
            for (String line : recentMemory) {
                Matcher m = LINE_PATTERN.matcher(line);
                if (m.matches()) {
                    String time = m.group(1);
                    String speaker = m.group(2).trim();
                    String text = m.group(3);
                    boolean isUser = speaker.startsWith("USER");
                    // Extract just HH:mm for display; fullTime is the unique-per-message
                    // timestamp clients use for dedup when syncing across windows.
                    String shortTime = time.length() >= 16 ? time.substring(11, 16) : time;
                    result.add(Map.of(
                            "speaker", speaker,
                            "text", text,
                            "time", shortTime,
                            "fullTime", time,
                            "isUser", isUser));
                }
            }
        }
        return result;
    }

    /**
     * Saves a chat entry to file and in-memory buffer.
     * One file per day: chat_history_yyyymmdd.txt, appended.
     */
    public void save(String speaker, String text) {
        if (text == null || text.isBlank()) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            Path monthDir = historyDir.resolve(now.format(YEAR_MONTH_FMT));
            Files.createDirectories(monthDir);
            Path file = monthDir.resolve(now.format(DAY_FMT) + ".txt");
            String fullTime = now.format(TIME_FMT);
            String trimmed = text.trim();
            String line = "[" + fullTime + "] " + speaker + ": " + trimmed;
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            synchronized (recentMemory) {
                recentMemory.addLast(line);
                while (recentMemory.size() > MEMORY_SIZE) {
                    recentMemory.removeFirst();
                }
            }

            String trimmedSpeaker = speaker != null ? speaker.trim() : "";
            boolean isUser = trimmedSpeaker.startsWith("USER");
            if (chatEventPublisher != null) {
                String shortTime = fullTime.length() >= 16 ? fullTime.substring(11, 16) : fullTime;
                chatEventPublisher.publishMessage(Map.of(
                        "speaker", trimmedSpeaker,
                        "text", trimmed,
                        "time", shortTime,
                        "fullTime", fullTime,
                        "isUser", isUser));
            }
            // Animate the taskbar icon's mouth when the bot speaks (resets boredom too)
            if (!isUser && iconAnimator != null) {
                try { iconAnimator.onBotMessage(trimmed); } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            log.error("Failed to save chat history", e);
        }
    }

    /** Returns a snapshot of the last 100 messages from memory. */
    public List<String> getRecentMemory() {
        synchronized (recentMemory) {
            return new ArrayList<>(recentMemory);
        }
    }

    /** Searches in-memory buffer for lines containing the query (case-insensitive). */
    public List<String> searchMemory(String query) {
        String lower = query.toLowerCase();
        synchronized (recentMemory) {
            return recentMemory.stream()
                    .filter(line -> line.toLowerCase().contains(lower))
                    .collect(Collectors.toList());
        }
    }

    /** Searches all chat history files for lines matching the query. Returns up to maxResults lines. */
    public List<String> searchHistoryFiles(String query, int maxResults) {
        List<String> results = new ArrayList<>();
        String lower = query.toLowerCase();
        try (Stream<Path> walk = Files.walk(historyDir)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".txt"))
                    .sorted(Comparator.reverseOrder()).toList();

            for (Path file : files) {
                try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                    lines.filter(line -> line.toLowerCase().contains(lower))
                            .forEach(line -> {
                                if (results.size() < maxResults) results.add(line);
                            });
                }
                if (results.size() >= maxResults) break;
            }
        } catch (IOException e) {
            log.error("Failed to search history files", e);
        }
        return results;
    }

    /** Returns the history directory path. */
    public Path getHistoryDir() {
        return historyDir;
    }
}
