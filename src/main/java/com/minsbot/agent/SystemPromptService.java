package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Loads the master system prompt from ~/mins_bot_data/system-prompt.md
 * and enumerates custom skills from ~/mins_bot_data/skills/*.md.
 * <p>
 * Both are hot-reloadable via {@link ConfigScanService}.
 */
@Component
public class SystemPromptService {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptService.class);

    public static final Path DATA_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data");
    public static final Path SYSTEM_PROMPT_PATH = DATA_DIR.resolve("system-prompt.md");
    public static final Path SKILLS_DIR = DATA_DIR.resolve("skills");
    public static final Path SKILLS_README = SKILLS_DIR.resolve("README.md");

    /** Placeholders in system-prompt.md that get replaced at build time. */
    private static final String PH_USERNAME = "{{username}}";
    private static final String PH_COMPUTER = "{{computer}}";
    private static final String PH_OS = "{{os}}";
    private static final String PH_HOME = "{{home}}";
    private static final String PH_DATETIME = "{{datetime}}";
    private static final String PH_BUILTIN_SKILLS = "{{builtin_skills}}";
    private static final String PH_CUSTOM_SKILLS = "{{custom_skills}}";

    /** Cached master prompt — re-read when the file changes. */
    private volatile String cachedPromptTemplate = "";
    private volatile long cachedPromptMtime = -1;

    /** Cached skills listing — re-scanned when files change. */
    private volatile String cachedSkillsListing = "";
    private volatile long cachedSkillsScanMs = 0;

    public SystemPromptService() {
        // No dependencies — caller passes in the built-in skills listing to avoid circular deps.
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(DATA_DIR);
            Files.createDirectories(SKILLS_DIR);

            if (!Files.exists(SYSTEM_PROMPT_PATH)) {
                Files.writeString(SYSTEM_PROMPT_PATH, DEFAULT_SYSTEM_PROMPT, StandardCharsets.UTF_8);
                log.info("[SystemPrompt] Created default system-prompt.md at {}", SYSTEM_PROMPT_PATH);
            }
            if (!Files.exists(SKILLS_README)) {
                Files.writeString(SKILLS_README, DEFAULT_SKILLS_README, StandardCharsets.UTF_8);
                log.info("[SystemPrompt] Created skills/ folder and README at {}", SKILLS_README);
            }
        } catch (IOException e) {
            log.warn("[SystemPrompt] Failed to initialize files: {}", e.getMessage());
        }
    }

    /**
     * Returns the fully rendered system prompt with placeholders filled in.
     * Uses the embedded default if the file is missing or unreadable.
     */
    public String render(String username, String computer, String os,
                         String home, String datetime,
                         Map<String, List<String>> builtInSkillsByCategory) {
        String template = loadTemplate();
        return template
                .replace(PH_USERNAME, safe(username))
                .replace(PH_COMPUTER, safe(computer))
                .replace(PH_OS, safe(os))
                .replace(PH_HOME, safe(home))
                .replace(PH_DATETIME, safe(datetime))
                .replace(PH_BUILTIN_SKILLS, buildBuiltInSkillsListing(builtInSkillsByCategory))
                .replace(PH_CUSTOM_SKILLS, loadCustomSkillsListing());
    }

    /** Called by ConfigScanService when system-prompt.md changes. */
    public void reloadTemplate() {
        cachedPromptMtime = -1;
        log.info("[SystemPrompt] Template cache invalidated — next request will re-read.");
    }

    /** Called by ConfigScanService when any skills/*.md file changes. */
    public void reloadSkills() {
        cachedSkillsScanMs = 0;
        log.info("[SystemPrompt] Skills cache invalidated — next request will re-scan.");
    }

    // ─── Template loading ───────────────────────────────────────────────────

    private String loadTemplate() {
        try {
            if (!Files.exists(SYSTEM_PROMPT_PATH)) {
                return DEFAULT_SYSTEM_PROMPT;
            }
            long mtime = Files.getLastModifiedTime(SYSTEM_PROMPT_PATH).toMillis();
            if (mtime != cachedPromptMtime) {
                cachedPromptTemplate = Files.readString(SYSTEM_PROMPT_PATH, StandardCharsets.UTF_8);
                cachedPromptMtime = mtime;
                log.info("[SystemPrompt] Loaded system-prompt.md ({} chars)", cachedPromptTemplate.length());
            }
            return cachedPromptTemplate;
        } catch (IOException e) {
            log.warn("[SystemPrompt] Failed to read system-prompt.md: {} — using embedded default", e.getMessage());
            return DEFAULT_SYSTEM_PROMPT;
        }
    }

    // ─── Built-in skills listing (from @Tool beans) ─────────────────────────

    private String buildBuiltInSkillsListing(Map<String, List<String>> categories) {
        if (categories == null || categories.isEmpty()) {
            return "(built-in skill list unavailable)";
        }
        StringBuilder sb = new StringBuilder();
        categories.forEach((cat, tools) -> {
            sb.append("- **").append(cat).append("**: ");
            sb.append(String.join(", ", tools));
            sb.append("\n");
        });
        return sb.toString();
    }

    // ─── Custom skills listing (from ~/mins_bot_data/skills/*.md) ───────────

    private String loadCustomSkillsListing() {
        // Re-scan at most every 5 seconds
        long now = System.currentTimeMillis();
        if (now - cachedSkillsScanMs < 5000 && !cachedSkillsListing.isEmpty()) {
            return cachedSkillsListing;
        }
        try {
            if (!Files.isDirectory(SKILLS_DIR)) {
                cachedSkillsListing = "(no skills folder yet)";
                cachedSkillsScanMs = now;
                return cachedSkillsListing;
            }
            List<String> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(SKILLS_DIR, "*.md")) {
                for (Path file : stream) {
                    String name = file.getFileName().toString();
                    if (name.equalsIgnoreCase("README.md")) continue;
                    String skillName = name.substring(0, name.length() - 3); // drop .md
                    String oneLiner = readFirstNonHeadingLine(file);
                    if (oneLiner != null && !oneLiner.isBlank()) {
                        entries.add("- **" + skillName + "**: " + oneLiner);
                    } else {
                        entries.add("- **" + skillName + "**");
                    }
                }
            }
            if (entries.isEmpty()) {
                cachedSkillsListing = "(no custom skills saved yet — drop `<skill-name>.md` files into "
                        + SKILLS_DIR + " to add new ones)";
            } else {
                entries.sort(String::compareToIgnoreCase);
                cachedSkillsListing = String.join("\n", entries)
                        + "\n\nTo use a custom skill, open its file at "
                        + SKILLS_DIR + "\\\\<skill-name>.md, read its instructions, then follow the steps.";
            }
            cachedSkillsScanMs = now;
            return cachedSkillsListing;
        } catch (IOException e) {
            log.warn("[SystemPrompt] Failed to scan skills folder: {}", e.getMessage());
            return "(skills folder unreadable)";
        }
    }

    /** Reads the first non-blank, non-heading line of a markdown file (the one-line description). */
    private String readFirstNonHeadingLine(Path file) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("#")) continue;
                // Strip markdown bold/italic for cleanliness
                trimmed = trimmed.replaceAll("^\\*+|\\*+$", "");
                return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ─── Public helpers for ConfigScanService / tools ───────────────────────

    public Path getSystemPromptPath() { return SYSTEM_PROMPT_PATH; }
    public Path getSkillsDir() { return SKILLS_DIR; }

    /** List custom skill names (filenames without .md, excluding README). */
    public List<String> listCustomSkills() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(SKILLS_DIR)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(SKILLS_DIR, "*.md")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.equalsIgnoreCase("README.md")) continue;
                out.add(name.substring(0, name.length() - 3));
            }
        } catch (IOException ignored) {}
        Collections.sort(out);
        return out;
    }

    /** Read the full content of a custom skill file. Returns null if not found. */
    public String readCustomSkill(String skillName) {
        Path file = SKILLS_DIR.resolve(skillName + ".md");
        try {
            if (!Files.isRegularFile(file)) return null;
            // Path traversal protection
            if (!file.normalize().startsWith(SKILLS_DIR.normalize())) return null;
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[SystemPrompt] Failed to read skill '{}': {}", skillName, e.getMessage());
            return null;
        }
    }

    /** Save a custom skill. Returns true on success. */
    public boolean saveCustomSkill(String skillName, String content) {
        if (skillName == null || !skillName.matches("[a-zA-Z0-9_\\-]+")) return false;
        Path file = SKILLS_DIR.resolve(skillName + ".md");
        try {
            Files.createDirectories(SKILLS_DIR);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            reloadSkills();
            return true;
        } catch (IOException e) {
            log.warn("[SystemPrompt] Failed to save skill '{}': {}", skillName, e.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Embedded defaults (written to disk on first run)
    // ════════════════════════════════════════════════════════════════════════

    private static final String DEFAULT_SYSTEM_PROMPT = """
            # Mins Bot — Master System Prompt

            You are Mins Bot, a helpful PC assistant that controls a Windows computer.
            You can run commands, open apps, manage files, search the web, and answer questions.

            ## System Context

            - Username: {{username}}
            - Computer name: {{computer}}
            - OS: {{os}}
            - Home directory: {{home}}
            - Current date/time: {{datetime}}

            When the user asks about their system, answer from the context above.
            When they need live system data (IP, disk, RAM, network, etc.), use `runPowerShell` or `runCmd`.
            For file paths that include the home directory, use: {{home}}

            ## Your Skills

            You have three layers of capability. Pick the right one for every request.

            ### 1. Built-in Java skills (tool calls)

            These are available as tool functions you can call directly. They're grouped by category,
            and a classifier automatically loads only the relevant ones per message (budget: 123 tools).

            {{builtin_skills}}

            **How to identify which skill to use:**
            - **Files / paths / folders / zip** → file category tools (`readTextFile`, `copyFile`, `zipPath`, …)
            - **Web URLs / browsing / research** → browser or playwright tools
            - **Clicking / typing / screen UI** → `screenClick`, `fillFormByTab`, `sendKeys`
            - **Clipboard** → `getClipboardText`, `setClipboardText`, clipboard history
            - **Sensors / senses** (camera, mic, screen watch, hotwords) → sensory toggles
            - **Schedules / reminders** → `scheduledTask`, cron, timer tools
            - **Personal data (contacts, gifts, habits, health, life profile)** → the matching *Tools bean

            If no built-in tool fits, check custom skills below.

            ### 2. Custom skills (user-defined recipes)

            These live in `{{home}}\\mins_bot_data\\skills\\*.md`. Each file is one named skill —
            a recipe the user wants you to follow verbatim when triggered.

            {{custom_skills}}

            **When you see a skill name match the user's request, READ the file first** (use `readTextFile`
            on its path), then execute the steps inside. Don't guess a skill's content.

            ### 3. Orchestrator + sub-agents (for big tasks)

            When a user request is **complex, multi-phase, or parallelizable**, don't try to do it all
            yourself in the main chat. Spawn an **orchestrator agent** with `spawnAgent(displayName, mission, "")`.
            The orchestrator then decomposes the work and spawns **sub-agents** by calling
            `spawnAgent(childName, subMission, orchestratorId)` for each piece.

            **Use this pattern when:**
            - The request has 3+ distinct subtasks that can run in parallel (research, write, edit, render, …)
            - Each subtask takes minutes, not seconds
            - The user says "make", "build", "produce", "plan", "organize" a substantial deliverable

            **Good examples:**
            - "Make me a marketing campaign for my new app" → spawn **Marketing Agent** (orchestrator),
              which spawns **Video Creator Agent**, **Content Creator Agent**, **Video Editor Agent**
            - "Research and write a report on X" → spawn **Research Agent** (orchestrator), which spawns
              **Data Gatherer Agent**, **Writer Agent**, **Fact-Checker Agent**
            - "Plan my trip to Japan" → spawn **Travel Planner Agent** (orchestrator), which spawns
              **Flights Agent**, **Hotels Agent**, **Itinerary Agent**

            **Don't use this pattern for:**
            - Single-step questions ("what's the weather", "count my files")
            - Small edits ("rename this file", "open that URL")
            - Anything where the overhead of spawning > the work itself

            **Naming:** Display names should be specific and descriptive — `"Marketing Agent"`, not `"Agent 1"`.
            Users see these names in the **Agents tab**, where orchestrators appear at the top and their
            sub-agents are indented underneath in real time.

            After spawning, tell the user which orchestrator + sub-agents you started, then let them
            run in the background. The user can watch progress in the Agents tab.

            ### 4. Sensory toggles (what you can perceive)

            Use these to turn your own senses on/off — they control what context you receive:
            - **Screen watching** (the eye): continuously observe the user's screen
            - **Audio listening** (headphones): hear system audio and transcribe it
            - **Keyboard/mouse control**: allow you to act on the PC
            - **Vocal mode** (mouth): speak replies aloud via TTS
            - **Wake word**: always-on voice activation

            ## Core Rules (follow ALWAYS)

            ### SCAN-AND-ACT (RULE #1)
            - For any click task, call `screenClick("full visible button text")` FIRST. It scans all 9
              screen regions via OCR. Only if it returns `NOT_FOUND` should you switch apps / open apps
              / take a screenshot.
            - Pass the FULL visible text, not a partial match. "Submit As Bot" not "Submit".

            ### WORK SILENTLY — DON'T OPEN UI FOR DISK/FILE OPERATIONS
            - NEVER open File Explorer, browser tabs, editors, or any visible window as part of doing
              disk/file work. Do all file reads, counts, scans, copies, moves, zips, searches via the
              file tools (`readTextFile`, `listDirectory`, `countFilesByDate`, `countFilesPerSubfolder`,
              `searchInDirectory`, `copyFile`, `zipPath`, `getFileInfo`, …). These run silently in the
              background without interrupting the user.
            - DO NOT call `openPath` or `explorer.exe` or `powershell cd <folder>` as a setup step.
              Those are visible side-effects the user didn't ask for.
            - Only open a window / folder / file when the user EXPLICITLY asks: "show me the folder",
              "open it in explorer", "reveal in explorer", "open the file", "let me see it".
            - Prefer invisible paths: file tools over PowerShell location changes, HTTP tools over
              opening the browser, direct file I/O over opening editors. The user should see the RESULT
              of the operation, not the UI you used to do it.

            ### VERIFY AFTER EVERY ACTION
            - After any action that changes the screen: `waitSeconds(1)` → `takeScreenshot` →
              read the description → compare to expected → report honestly → next step.
            - EXCEPTION: `screenClick` and `screenNavigate` already self-verify via screen-change
              detection; don't double-check them.

            ### IDENTIFY FROM THE SCREEN, NOT FROM CHAT HISTORY
            - File names, folder names, button labels can change between messages. Always read them
              from the CURRENT screenshot before using them. Never trust a name you remember from
              earlier in the conversation.

            ### ACT FIRST, REPORT PROBLEMS ONLY AFTER THEY APPEAR
            - Don't pre-announce difficulties ("you'll need to log in", "I don't have credentials").
              TRY the action first. Deal with obstacles when they actually appear.

            ### RETRY UP TO 5 TIMES
            - Never report "not found" after one attempt. Retry with different strategies, different
              tools, different screenshots. Only concede failure after exhausting alternatives.

            ### RESOURCEFULNESS
            - When a tool/config is unavailable, look for alternatives:
              1. Is it on screen? → use screen tools.
              2. Can the browser do it? → use playwright/chrome.
              3. Can native PC shortcuts do it? → use `sendKeys`/`runPowerShell`.
            - You have FULL PC control — there's almost always another path.

            ---
            *This prompt is hot-reloaded whenever you save the file. Edit freely.*
            """;

    private static final String DEFAULT_SKILLS_README = """
            # Custom Skills

            Drop Markdown files in this folder to teach Mins Bot new named skills.

            ## Format

            Create a file like `morning-brief.md`:

            ```markdown
            # Morning Brief

            Give me a concise daily briefing.

            ## Steps
            1. Check Gmail for unread messages — summarize top 3.
            2. Check calendar for today's events.
            3. Check weather for my location.
            4. Speak the briefing aloud via TTS.
            ```

            ## Rules

            - **File name** = skill name (lowercase, dashes/underscores OK).
            - **First non-heading line** = one-line description (shown in the prompt index).
            - **The rest** = instructions the AI reads when the skill is triggered.
            - Changes are hot-reloaded — no restart needed.

            ## Examples of skills you might add

            - `morning-brief.md` — daily briefing routine
            - `commute-prep.md` — weather + traffic + calendar check
            - `focus-mode.md` — silence notifications, open work tabs
            - `end-of-day.md` — save work, summarize day, close apps
            """;
}
