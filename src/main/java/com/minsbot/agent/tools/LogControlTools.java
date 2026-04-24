package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime log-level control. Lets the user quiet / re-enable noisy loggers
 * (RecurringTasks, Watcher, ScreenMemory, etc.) from chat without restarting.
 *
 * <p>Preferences persist to {@code ~/mins_bot_data/log-levels.json} and are
 * re-applied on startup.</p>
 */
@Component
public class LogControlTools {

    private static final Logger log = LoggerFactory.getLogger(LogControlTools.class);

    /** Friendly short names → fully-qualified logger prefix. First-match wins. */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();
    static {
        ALIASES.put("recurring",      "com.minsbot.agent.RecurringTasksService");
        ALIASES.put("recurring-tasks","com.minsbot.agent.RecurringTasksService");
        ALIASES.put("watcher",        "com.minsbot.skills.watcher.WatcherService");
        ALIASES.put("screen-memory",  "com.minsbot.agent.ScreenMemoryService");
        ALIASES.put("screen-state",   "com.minsbot.agent.ScreenStateService");
        ALIASES.put("vision",         "com.minsbot.agent.VisionService");
        ALIASES.put("textract",       "com.minsbot.agent.TextractService");
        ALIASES.put("webcam",         "com.minsbot.agent.WebcamMemoryService");
        ALIASES.put("tts",            "com.minsbot.agent.tools.TtsTools");
        ALIASES.put("piper",          "com.minsbot.agent.tools.TtsTools");
        ALIASES.put("playwright",     "com.minsbot.agent.tools.PlaywrightService");
        ALIASES.put("claude",         "com.minsbot.agent.tools.ClaudeCodeTools");
        ALIASES.put("claude-code",    "com.minsbot.agent.tools.ClaudeCodeTools");
        ALIASES.put("dev-server",     "com.minsbot.agent.tools.DevServerTools");
        ALIASES.put("bootstrap",      "com.minsbot.agent.tools.ProjectBootstrapService");
        ALIASES.put("tool-router",    "com.minsbot.agent.tools.ToolRouter");
        ALIASES.put("tool-classifier","com.minsbot.agent.tools.ToolClassifierService");
        ALIASES.put("chat",           "com.minsbot.ChatService");
        ALIASES.put("main-loop",      "com.minsbot.ChatService");
        ALIASES.put("config-scan",    "com.minsbot.agent.ConfigScanService");
        ALIASES.put("system-prompt",  "com.minsbot.agent.SystemPromptService");
        ALIASES.put("approval",       "com.minsbot.approval");
        ALIASES.put("audio",          "com.minsbot.agent.AudioPipelineService");
        ALIASES.put("spring",         "org.springframework");
        ALIASES.put("tomcat",         "org.apache.catalina");
    }

    private static final Path OVERRIDES_FILE =
            Path.of(System.getProperty("user.home"), "mins_bot_data", "log-levels.json");

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());
    /** In-memory record of user-applied overrides so we can list / persist / restore them. */
    private final Map<String, LogLevel> overrides = new LinkedHashMap<>();

    @PostConstruct
    void restore() {
        try {
            if (!Files.exists(OVERRIDES_FILE)) return;
            String body = Files.readString(OVERRIDES_FILE, StandardCharsets.UTF_8).trim();
            if (body.isEmpty() || body.equals("{}")) return;
            // Tiny hand-rolled JSON parser to avoid pulling ObjectMapper here.
            // Format: {"com.example.X":"WARN", "com.example.Y":"OFF"}
            String inner = body.substring(1, body.length() - 1).trim();
            if (inner.isEmpty()) return;
            for (String kv : inner.split(",")) {
                String[] parts = kv.split(":", 2);
                if (parts.length != 2) continue;
                String k = parts[0].trim().replaceAll("^\"|\"$", "");
                String v = parts[1].trim().replaceAll("^\"|\"$", "");
                LogLevel lvl = parseLevel(v);
                if (lvl == null) continue;
                loggingSystem.setLogLevel(k, lvl);
                overrides.put(k, lvl);
            }
            log.info("[LogControl] Restored {} override(s) from {}", overrides.size(), OVERRIDES_FILE);
        } catch (Exception e) {
            log.warn("[LogControl] Failed to load overrides: {}", e.getMessage());
        }
    }

    @Tool(description = "Change the log verbosity of a specific logger at runtime. "
            + "Use to quiet noisy components (RecurringTasks, Watcher, ScreenState, etc.) without restarting. "
            + "Accepts aliases like 'recurring', 'watcher', 'vision', 'claude', 'playwright', "
            + "or a full class name like 'com.minsbot.agent.RecurringTasksService'. "
            + "Level is one of: TRACE, DEBUG, INFO, WARN, ERROR, OFF. Settings persist across restarts.")
    public String setLogLevel(
            @ToolParam(description = "Logger alias or fully-qualified class name") String nameOrAlias,
            @ToolParam(description = "Level: TRACE | DEBUG | INFO | WARN | ERROR | OFF") String level) {
        String resolved = resolveName(nameOrAlias);
        LogLevel lvl = parseLevel(level);
        if (lvl == null) {
            return "Unknown level '" + level + "'. Use one of: TRACE, DEBUG, INFO, WARN, ERROR, OFF.";
        }
        try {
            loggingSystem.setLogLevel(resolved, lvl);
            overrides.put(resolved, lvl);
            persist();
            log.info("[LogControl] Set {} → {}", resolved, lvl);
            return "Set '" + resolved + "' to " + lvl + ".";
        } catch (Exception e) {
            return "Failed to set log level: " + e.getMessage();
        }
    }

    @Tool(description = "Mute a logger completely (level OFF). "
            + "Shortcut for setLogLevel(name, OFF). Example: muteLogger('recurring') silences RecurringTasks spam.")
    public String muteLogger(
            @ToolParam(description = "Logger alias or fully-qualified class name") String nameOrAlias) {
        return setLogLevel(nameOrAlias, "OFF");
    }

    @Tool(description = "Quiet a logger down to WARN so only warnings and errors show. "
            + "Use for loggers that are informational but chatty (RecurringTasks, ScreenState, etc.).")
    public String quietLogger(
            @ToolParam(description = "Logger alias or fully-qualified class name") String nameOrAlias) {
        return setLogLevel(nameOrAlias, "WARN");
    }

    @Tool(description = "Restore a single logger (or all loggers) to their default INFO level. "
            + "Pass 'all' to clear every override.")
    public String resetLogLevel(
            @ToolParam(description = "Logger alias, class name, or 'all'") String nameOrAlias) {
        if (nameOrAlias != null && nameOrAlias.trim().equalsIgnoreCase("all")) {
            Map<String, LogLevel> copy = new LinkedHashMap<>(overrides);
            overrides.clear();
            copy.keySet().forEach(k -> {
                try { loggingSystem.setLogLevel(k, null); } catch (Exception ignored) {}
            });
            persist();
            return "Cleared " + copy.size() + " override(s); all loggers reset to defaults.";
        }
        String resolved = resolveName(nameOrAlias);
        try {
            loggingSystem.setLogLevel(resolved, null);
            overrides.remove(resolved);
            persist();
            return "Reset '" + resolved + "' to default level.";
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "List the currently active log-level overrides set by the user, "
            + "plus the available shortcut aliases so the user knows what they can target.")
    public String listLogLevels() {
        StringBuilder sb = new StringBuilder();
        if (overrides.isEmpty()) {
            sb.append("No user overrides active (all loggers at default levels).\n");
        } else {
            sb.append("Active overrides:\n");
            overrides.forEach((k, v) -> sb.append("  ").append(k).append(" → ").append(v).append('\n'));
        }
        sb.append("\nShortcuts you can target by name:\n");
        ALIASES.forEach((alias, fqn) -> {
            if (!alias.equals(fqn)) sb.append("  ").append(alias).append(" → ").append(fqn).append('\n');
        });
        // Also show current effective level for interesting loggers so user sees the state.
        sb.append("\nCurrent effective level for common loggers:\n");
        for (String fqn : new java.util.LinkedHashSet<>(ALIASES.values())) {
            LoggerConfiguration cfg = loggingSystem.getLoggerConfiguration(fqn);
            if (cfg != null) {
                LogLevel eff = cfg.getEffectiveLevel();
                sb.append("  ").append(fqn).append(" : ").append(eff == null ? "?" : eff).append('\n');
            }
        }
        return sb.toString();
    }

    // ─── helpers ───

    private static String resolveName(String nameOrAlias) {
        if (nameOrAlias == null) return "";
        String key = nameOrAlias.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        String mapped = ALIASES.get(key);
        return mapped != null ? mapped : nameOrAlias.trim();
    }

    private static LogLevel parseLevel(String level) {
        if (level == null) return null;
        try { return LogLevel.valueOf(level.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }

    private void persist() {
        try {
            Files.createDirectories(OVERRIDES_FILE.getParent());
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, LogLevel> e : overrides.entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append('"').append(escape(e.getKey())).append('"')
                    .append(':')
                    .append('"').append(Objects.toString(e.getValue(), "INFO")).append('"');
            }
            json.append("}");
            Files.writeString(OVERRIDES_FILE, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[LogControl] persist failed: {}", e.getMessage());
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** For programmatic callers / tests. */
    public Map<String, String> activeOverrides() {
        Map<String, String> out = new HashMap<>();
        overrides.forEach((k, v) -> out.put(k, v.name()));
        return out;
    }

    /** Exposed for a future preferences page. */
    public static List<Map.Entry<String, String>> aliases() {
        return List.copyOf(ALIASES.entrySet());
    }
}
