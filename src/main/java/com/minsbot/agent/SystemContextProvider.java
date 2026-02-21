package com.minsbot.agent;

import com.minsbot.agent.tools.DirectivesTools;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Provides system context (username, OS, time, etc.) for the AI system message.
 * Personal details from ~/mins_bot_data/personal_config.md and system/preferences from
 * ~/mins_bot_data/system_config.md and scheduled checks from ~/mins_bot_data/cron_config.md are injected when present.
 */
@Component
public class SystemContextProvider {

    /** Whether the directive reminder has been shown at least once this session. */
    private boolean directiveReminderShownOnce = false;

    private static final String PERSONAL_CONFIG_FILENAME = "personal_config.md";
    private static final String SYSTEM_CONFIG_FILENAME = "system_config.md";
    private static final String CRON_CONFIG_FILENAME = "cron_config.md";
    private static final String MINSBOT_CONFIG_FILENAME = "minsbot_config.txt";
    private static final String DEFAULT_PERSONAL_CONFIG = """
            # Personal config
            Use this for personalized responses. Fill in and keep updated.

            ## Name
            -

            ## Birthdate
            -

            ## Kids
            -

            ## Partner / spouse
            -

            ## Work
            -
            """;

    private static final String DEFAULT_SYSTEM_CONFIG = """
            # System config
            Machine-specific and preference details. Use for paths, default apps, network, etc.

            ## Default browser
            -

            ## Preferred apps
            -

            ## Important paths
            -

            ## Network / VPN
            -
            """;

    private static final String DEFAULT_MINSBOT_CONFIG = """
            # Mins Bot Config
            Bot behavior and processing settings. Scanned every 15 seconds for live changes.

            ## Bot name
            - name:

            ## Sound
            - enabled: true
            - volume: 0.01
            - min_switch_ms: 1500

            ## Planning
            - enabled: true

            ## Config scan
            - interval_seconds: 15

            ## Idle detection
            - enabled: true
            - idle_seconds: 300

            ## Screen memory
            - enabled: true
            - interval_seconds: 60

            ## Audio memory
            - enabled: false
            - interval_seconds: 60
            - clip_seconds: 15
            - keep_wav: false

            ## Download
            - confirm_threshold: 1000

            ## Directives
            - reminder_interval: 5
            """;

    private static final String DEFAULT_CRON_CONFIG = """
            # Cron / scheduled checks
            Recurring checks and reminders the user wants to track.

            ## Daily checks
            -

            ## Weekly checks
            -

            ## Reminders
            -

            ## Other schedule
            -
            """;

    public String buildSystemMessage() {
        String username = System.getProperty("user.name", "unknown");
        String osName = System.getProperty("os.name", "unknown");
        String osVersion = System.getProperty("os.version", "");
        String osArch = System.getProperty("os.arch", "");
        String userHome = System.getProperty("user.home", "");
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (EEEE)"));

        String computerName = "unknown";
        try {
            computerName = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            String env = System.getenv("COMPUTERNAME");
            if (env != null && !env.isBlank()) computerName = env;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are Mins Bot, a helpful PC assistant that controls a Windows computer.
                You can run commands, open apps, manage files, search the web, and answer questions.

                SYSTEM CONTEXT:
                - Username: %s
                - Computer name: %s
                - OS: %s %s (%s)
                - Home directory: %s
                - Current date/time: %s

                When the user asks about their system, answer from the context above.
                When they need live system data (IP, disk, RAM, network, etc.), use the runPowerShell or runCmd tools.
                For file paths that include the home directory, use: %s
                Be concise and helpful. Use the available tools to fulfill user requests.
                """.formatted(username, computerName, osName, osVersion, osArch, userHome, now, userHome));

        // Personal context from ~/mins_bot_data/personal_config.md (created with template if missing)
        String personalConfig = loadPersonalConfig();
        if (personalConfig != null && !personalConfig.isBlank()) {
            sb.append("\nPERSONAL CONTEXT (use this to personalize responses — name, family, work, etc.):\n");
            sb.append(personalConfig);
            if (!personalConfig.endsWith("\n")) sb.append("\n");
        }

        // System config from ~/mins_bot_data/system_config.md (created with template if missing)
        String systemConfig = loadSystemConfig();
        if (systemConfig != null && !systemConfig.isBlank()) {
            sb.append("\nSYSTEM CONFIG (machine preferences, default apps, paths, network — use for system-related answers):\n");
            sb.append(systemConfig);
            if (!systemConfig.endsWith("\n")) sb.append("\n");
        }

        // Scheduled checks from ~/mins_bot_data/cron_config.md (created with template if missing)
        String cronConfig = loadCronConfig();
        if (cronConfig != null && !cronConfig.isBlank()) {
            sb.append("\nSCHEDULED CHECKS (daily/weekly/reminders — use when discussing recurring tasks or setting up checks):\n");
            sb.append(cronConfig);
            if (!cronConfig.endsWith("\n")) sb.append("\n");
        }

        // Bot config from ~/mins_bot_data/minsbot_config.txt (created with template if missing)
        String minsbotConfig = loadMinsbotConfig();
        if (minsbotConfig != null && !minsbotConfig.isBlank()) {
            sb.append("\nBOT CONFIG (sound, planning, and other bot behavior settings):\n");
            sb.append(minsbotConfig);
            if (!minsbotConfig.endsWith("\n")) sb.append("\n");
        }

        sb.append("""

                BROWSER RULES:
                - By default, when the user says "open youtube", "open google", "open [website]", or "go to [site]", \
                use the openUrl tool to open it in their PC's default browser (Chrome, Edge, Firefox, etc.).
                - ONLY use the built-in chat browser tools (openInBrowserTab, searchInBrowser, searchImagesInBrowser, \
                collectImagesFromBrowser, readBrowserPage, downloadImagesFromBrowser) when the user explicitly says \
                "in-browser", "chat browser", "in the chat browser", or similar phrases indicating the Mins Bot built-in browser.
                - For research/information gathering that doesn't need the user to see it, use the headless browsePage tool.

                QUIT RULE:
                - When the user says "quit" (or "exit", "close mins bot"), reply only with "Quit Mins Bot?" and do nothing else. Do NOT call quitMinsBot yet.
                - When the user then replies "yes" or "y" (and they are clearly confirming quit), call the quitMinsBot tool.
                - If they reply with anything else (no, nope, cancel, etc.), do nothing — no need to say anything or take any action.

                TASK COMPLETION RULE:
                - When the user requests a specific count (e.g. "download 24 images"), you MUST complete the EXACT count \
                without stopping to ask for confirmation. Do NOT stop partway and ask "should I continue?" — just keep going.
                - If a tool returns fewer results than needed, call the tool again until the target is met.
                - Check the target folder to see how many files already exist and only download the remaining amount.
                - Only ask for confirmation if the requested count exceeds the download confirm_threshold in the Bot Config \
                (default 1000). Below that threshold, just do it.

                TTS / VOICE RULE:
                - When the user asks you to "say" something, "speak", "read aloud", or "say something": you MUST call the speak tool with the text to be spoken so they hear audio. Do not just reply with text — call speak(...) with that text (or a short phrase). Examples: "say hello" → call speak("Hello!"); "say something" → call speak("Here's something for you!"); "read this aloud" → call speak with the content.
                """);

        // Load HIERARCHY.md for tool execution prioritization
        String hierarchy = loadHierarchy();
        if (hierarchy != null) {
            sb.append("\nDEVELOPMENT HIERARCHY (refer to this when evaluating tool execution and task priorities):\n");
            sb.append(hierarchy);
            sb.append("\n");
        }

        String directives = DirectivesTools.loadDirectivesForPrompt();
        if (directives != null) {
            sb.append("\nUSER DIRECTIVES (follow these at all times):\n");
            sb.append(directives);
            sb.append("\n");
        }

        // Count non-empty directive lines
        int directiveCount = 0;
        if (directives != null) {
            for (String line : directives.split("\n")) {
                if (!line.trim().isEmpty()) directiveCount++;
            }
        }

        // Directive nudge: show ONCE on first message of session, then never again.
        // The user explicitly said repeated reminders are irritating.
        if (!directiveReminderShownOnce && directiveCount == 0) {
            sb.append("""

                NO DIRECTIVES SET:
                The user has no directives yet. Briefly and playfully mention that they can give you \
                a directive so you know how to help. Keep it to ONE short sentence at the end of \
                your response — do NOT make it the main topic. If the user gives you an instruction, \
                offer to save it as a directive. Do NOT repeat this nudge in future messages.
                """);
            directiveReminderShownOnce = true;
        }

        return sb.toString();
    }

    /**
     * Read the bot name from ~/mins_bot_data/minsbot_config.txt (## Bot name → - name: ...).
     * Returns null if not set or empty.
     */
    public static String loadBotName() {
        Path path = Paths.get(System.getProperty("user.home"), "mins_bot_data", MINSBOT_CONFIG_FILENAME);
        try {
            if (!Files.exists(path)) return null;
            String content = Files.readString(path);
            boolean inSection = false;
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("## ")) {
                    inSection = trimmed.equalsIgnoreCase("## Bot name");
                    continue;
                }
                if (inSection && trimmed.startsWith("- name:")) {
                    String val = trimmed.substring("- name:".length()).trim();
                    return val.isEmpty() ? null : val;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /**
     * Load HIERARCHY.md from the project root for tool execution prioritization.
     */
    private String loadHierarchy() {
        try {
            Path path = Paths.get(System.getProperty("user.dir"), "HIERARCHY.md");
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * Load personal context from ~/mins_bot_data/personal_config.md.
     * If the file does not exist, creates mins_bot_data and writes a default template.
     */
    private String loadPersonalConfig() {
        Path dataDir = Paths.get(System.getProperty("user.home"), "mins_bot_data");
        Path path = dataDir.resolve(PERSONAL_CONFIG_FILENAME);
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(dataDir);
                Files.writeString(path, DEFAULT_PERSONAL_CONFIG);
                return null;
            }
            String content = Files.readString(path).trim();
            return content.isEmpty() ? null : content;
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Load system config from ~/mins_bot_data/system_config.md.
     * If the file does not exist, creates mins_bot_data and writes a default template.
     */
    private String loadSystemConfig() {
        Path dataDir = Paths.get(System.getProperty("user.home"), "mins_bot_data");
        Path path = dataDir.resolve(SYSTEM_CONFIG_FILENAME);
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(dataDir);
                Files.writeString(path, DEFAULT_SYSTEM_CONFIG);
                return null;
            }
            String content = Files.readString(path).trim();
            return content.isEmpty() ? null : content;
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Load bot config from ~/mins_bot_data/minsbot_config.txt.
     * If the file does not exist, creates mins_bot_data and writes a default template.
     */
    private String loadMinsbotConfig() {
        Path dataDir = Paths.get(System.getProperty("user.home"), "mins_bot_data");
        Path path = dataDir.resolve(MINSBOT_CONFIG_FILENAME);
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(dataDir);
                Files.writeString(path, DEFAULT_MINSBOT_CONFIG);
                return null;
            }
            String content = Files.readString(path).trim();
            return content.isEmpty() ? null : content;
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Load scheduled checks from ~/mins_bot_data/cron_config.md.
     * If the file does not exist, creates mins_bot_data and writes a default template.
     */
    private String loadCronConfig() {
        Path dataDir = Paths.get(System.getProperty("user.home"), "mins_bot_data");
        Path path = dataDir.resolve(CRON_CONFIG_FILENAME);
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(dataDir);
                Files.writeString(path, DEFAULT_CRON_CONFIG);
                return null;
            }
            String content = Files.readString(path).trim();
            return content.isEmpty() ? null : content;
        } catch (IOException ignored) {
            return null;
        }
    }
}
