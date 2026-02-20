package com.botsfer.memory;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final Pattern SAFE_KEY = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private final MemoryConfig.MemoryProperties properties;
    private Path memoryDir;

    public MemoryService(MemoryConfig.MemoryProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @PostConstruct
    public void init() throws IOException {
        if (!properties.isEnabled()) return;
        this.memoryDir = Paths.get(properties.getBasePath()).toAbsolutePath();
        Files.createDirectories(memoryDir);
        log.info("Memory directory initialized: {}", memoryDir);
    }

    public void save(String key, String value) throws IOException {
        Path file = resolveKey(key);
        Files.writeString(file, value != null ? value : "", StandardCharsets.UTF_8);
    }

    public Optional<String> load(String key) throws IOException {
        Path file = resolveKey(key);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    }

    public boolean delete(String key) throws IOException {
        Path file = resolveKey(key);
        return Files.deleteIfExists(file);
    }

    public List<String> listKeys() throws IOException {
        if (memoryDir == null || !Files.isDirectory(memoryDir)) return Collections.emptyList();
        try (Stream<Path> stream = Files.list(memoryDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.equals(".gitkeep"))
                    .sorted()
                    .toList();
        }
    }

    public boolean exists(String key) {
        try {
            return Files.exists(resolveKey(key));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key must not be blank");
        }
        if (!SAFE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Key must contain only alphanumeric characters, dots, dashes, or underscores");
        }
    }

    private Path resolveKey(String key) {
        if (memoryDir == null) {
            throw new IllegalStateException("Memory is disabled (app.memory.enabled=false)");
        }
        validateKey(key);
        Path resolved = memoryDir.resolve(key).normalize();
        if (!resolved.startsWith(memoryDir)) {
            throw new IllegalArgumentException("Invalid key: path traversal detected");
        }
        return resolved;
    }
}
