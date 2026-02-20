package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes site credentials from ~/mins_bot_data/sites_config.txt.
 *
 * <p>File format — each entry is 4 lines followed by a blank line:
 * <pre>
 * description
 * url
 * username
 * password
 *
 * </pre>
 */
@Service
public class SitesConfigService {

    private static final Logger log = LoggerFactory.getLogger(SitesConfigService.class);

    private Path configFile;

    public record SiteEntry(String description, String url, String username, String password) {}

    @PostConstruct
    public void init() throws IOException {
        Path dataDir = Paths.get(System.getProperty("user.home"), "mins_bot_data");
        Files.createDirectories(dataDir);
        configFile = dataDir.resolve("sites_config.txt");
        if (!Files.exists(configFile)) {
            Files.writeString(configFile, "", StandardCharsets.UTF_8);
            log.info("Created empty sites config: {}", configFile);
        } else {
            log.info("Sites config: {} ({} entries)", configFile, loadAll().size());
        }
    }

    /** Load all site entries from the file. */
    public List<SiteEntry> loadAll() {
        List<SiteEntry> entries = new ArrayList<>();
        try {
            if (!Files.exists(configFile)) return entries;
            List<String> lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);
            int i = 0;
            while (i < lines.size()) {
                // Skip blank lines between entries
                while (i < lines.size() && lines.get(i).isBlank()) i++;
                if (i + 3 >= lines.size()) break;
                String desc = lines.get(i).trim();
                String url = lines.get(i + 1).trim();
                String user = lines.get(i + 2).trim();
                String pass = lines.get(i + 3).trim();
                if (!desc.isEmpty()) {
                    entries.add(new SiteEntry(desc, url, user, pass));
                }
                i += 4;
            }
        } catch (IOException e) {
            log.error("Failed to read sites config: {}", e.getMessage());
        }
        return entries;
    }

    /** Find entries matching a query (searches description and url, case-insensitive). */
    public List<SiteEntry> find(String query) {
        String lower = query.toLowerCase();
        return loadAll().stream()
                .filter(e -> e.description().toLowerCase().contains(lower)
                          || e.url().toLowerCase().contains(lower))
                .toList();
    }

    /** Add a new site entry. */
    public String add(String description, String url, String username, String password) {
        try {
            String entry = description + "\n" + url + "\n" + username + "\n" + password + "\n\n";
            Files.writeString(configFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return "Added site: " + description;
        } catch (IOException e) {
            return "Failed to add site: " + e.getMessage();
        }
    }

    /** Remove a site entry by description (case-insensitive match). */
    public String remove(String description) {
        List<SiteEntry> all = loadAll();
        List<SiteEntry> remaining = all.stream()
                .filter(e -> !e.description().equalsIgnoreCase(description.trim()))
                .toList();
        if (remaining.size() == all.size()) {
            return "No site found with description: " + description;
        }
        return rewriteAll(remaining);
    }

    /** Rewrite the entire file with the given entries. */
    private String rewriteAll(List<SiteEntry> entries) {
        try {
            StringBuilder sb = new StringBuilder();
            for (SiteEntry e : entries) {
                sb.append(e.description()).append("\n");
                sb.append(e.url()).append("\n");
                sb.append(e.username()).append("\n");
                sb.append(e.password()).append("\n");
                sb.append("\n");
            }
            Files.writeString(configFile, sb.toString(), StandardCharsets.UTF_8);
            return "OK";
        } catch (IOException e) {
            return "Failed to write sites config: " + e.getMessage();
        }
    }

    public Path getConfigFile() {
        return configFile;
    }
}
