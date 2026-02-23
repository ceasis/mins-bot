package com.minsbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Pattern LINE_PATTERN =
            Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})] ([^:]+): (.+)$");

    @PostConstruct
    public void init() throws IOException {
        historyDir = Paths.get(System.getProperty("user.home"), "mins_bot_data", "mins_bot_history");
        Files.createDirectories(historyDir);
        log.info("Chat history directory: {}", historyDir);
        loadLatestHistory();
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

    /** Clear the in-memory chat history ring buffer (does NOT delete history files). */
    public void clearHistory() {
        synchronized (recentMemory) {
            recentMemory.clear();
        }
        log.info("[Transcript] In-memory history cleared.");
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
                    // Extract just HH:mm for display
                    String shortTime = time.length() >= 16 ? time.substring(11, 16) : time;
                    result.add(Map.of("speaker", speaker, "text", text, "time", shortTime, "isUser", isUser));
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
            String line = "[" + now.format(TIME_FMT) + "] " + speaker + ": " + text.trim();
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            synchronized (recentMemory) {
                recentMemory.addLast(line);
                while (recentMemory.size() > MEMORY_SIZE) {
                    recentMemory.removeFirst();
                }
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
