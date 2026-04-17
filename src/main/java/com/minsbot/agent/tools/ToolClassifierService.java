package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Lightweight AI-based tool category classifier.
 * Uses gpt-4o-mini via raw HTTP (not Spring AI ChatClient) to avoid
 * polluting conversation memory or interfering with the main model config.
 *
 * <p>Called by {@link ToolRouter} as a fallback when regex keyword matching
 * doesn't find any category match — handles natural language variations
 * that rigid keywords miss.</p>
 */
@Component
public class ToolClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ToolClassifierService.class);

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${app.tool-classifier.model:gpt-4o-mini}")
    private String model;

    private HttpClient httpClient;

    /** All valid category names (must match ToolRouter category names exactly). */
    private static final Set<String> VALID_CATEGORIES = Set.of(
            "chat_browser", "browser", "cdp", "sites", "files", "system", "media",
            "ai_model", "communication", "scheduling", "utility", "export",
            "plugins", "hotkeys", "tray", "screen_memory", "audio_memory",
            "playlist", "software", "network", "printing", "excel", "screen_watching",
            "travel", "research", "knowledge", "briefing", "calendar", "gmail",
            "web_monitor",
            "code_audit",
            "backup",
            "music",
            "health_monitor",
            "video_download",
            "clipboard_history",
            "wake_word",
            "autopilot",
            "proactive_action",
            "gift_ideas",
            "episodic_memory",
            "proactive",
            "health_tracker",
            "finance_tracker",
            "habits",
            "feedback",
            "auto_skills",
            "window_manager",
            "social_monitor",
            "intelligence",
            "video_creation",
            "trend_scout",
            "github",
            "bot_window",
            "code_runner",
            "file_watcher",
            "app_usage",
            "custom_skills"
    );

    private static final String SYSTEM_PROMPT = """
            You are a tool router. Given a user message, return which tool categories are needed.
            Categories:
            - chat_browser: browsing in the built-in chat browser
            - browser: web search, URLs, opening websites, tabs, browse internet, navigate website, research online, find information on website, YouTube, extract data from web, scrape page, type into search box, browser search, search for flights, search for hotels, look up prices, find products, research topic, compare options, find reviews, search the web
            - cdp: type into browser search box, fill browser form field, click browser button via DOM, list chrome tabs, read page text from chrome tab, browser automation via CDP
            - sites: login credentials, saved sites, passwords
            - files: programmatic file/folder operations, disk, zip, read/write files, file paths
            - system: apps, windows, processes, screenshots, mouse/keyboard control, volume, shutdown, restart, click on element, find button, click button, drag files, move files on screen, move to folder, put files in folder, organize desktop, sort files into folders, visual file operations, navigate website by clicking, scroll web page, interact with browser page, click on links/buttons on website, screen recording, switch to app, alt tab, switch window, go to app, open app, focus window
            - media: images, photos, PDF, text-to-speech, voice
            - ai_model: ollama, model switching, summarization, huggingface
            - communication: email, weather
            - scheduling: reminders, timers, alarms, cron, recurring tasks
            - utility: calculator, math, QR codes, hashing, unit conversion
            - export: export chat history
            - plugins: load/unload jar plugins
            - hotkeys: keyboard shortcuts
            - tray: system tray
            - screen_memory: what's on screen, what was I doing/watching, OCR, screen capture
            - audio_memory: what's playing, what do you hear, system audio, music, record audio, capture audio
            - playlist: detected songs, music playlist, what songs have I listened to, add/remove song from playlist
            - software: install software, uninstall app, search for programs, winget, system updates, upgrade software
            - network: wifi, connect to wifi, disconnect wifi, network info, IP address, DNS, VPN, internet connection
            - printing: print file, print document, list printers, send to printer, default printer
            - excel: Excel spreadsheet, write cells, format cells, add sheet, read spreadsheet, workbook, xlsx, create spreadsheet
            - screen_watching: watch screen, observe, guess drawing, guess what I'm drawing, monitor screen, continuous observation, watch me, drawing game, watch my screen, help me play, assist me with game, play game with me, listen to audio, hear what I'm listening, listen to my meeting, turn on ears, what song is this, listen mode, listen to what I'm hearing, listen to my speakers, jarvis mode, jarvis commentary, toggle jarvis, comment on my screen
            - travel: flights, hotels, travel, trip, vacation, book flight, find hotel, airfare, accommodation, search flights, search hotels, travel research, plan trip
            - research: research and create report, compare options and make spreadsheet, research pricing and create Excel/PDF, multi-step research with output files, analyze and summarize into document, create comparison report, gather data from web and save to file
            - knowledge: knowledge base, uploaded documents, reference documents
            - briefing: morning briefing, daily briefing, prepare my briefing, summarize my day, daily summary, what do I have today, brief me, start of day summary, morning routine
            - calendar: calendar, events today, what's on my calendar, meetings today, schedule, upcoming events, this week's events, Google Calendar, my appointments
            - gmail: check gmail, unread emails, new emails, check my email, inbox, gmail messages, any new mail, email summary
            - web_monitor: monitor website, watch for changes, alert me if website changes, check URL every N minutes, track website updates, website monitoring, watch this page, notify me when site changes
            - code_audit: clone repo, git clone, code audit, scan code, security scan, vulnerability scan, SQL injection, hardcoded secrets, unused imports, code review, audit repository, scan for vulnerabilities, check for secrets
            - backup: backup config files, backup configs, backup .env, backup properties, backup important configs, backup settings, backup yml files, backup configuration files, config backup, settings backup
            - music: play music, pause music, next song, previous song, skip track, volume up, volume down, mute, play on spotify, what song is playing, search spotify, play/pause, stop music, current track, open spotify, louder, quieter
            - health_monitor: system health, CPU usage, RAM usage, disk space, monitor system, system alerts, process watchdog, watch process, restart if crashes, keep running, kill process, top processes, system performance, PC health, memory usage, disk alert
            - video_download: download video, download from youtube, save video, yt-dlp, download audio, save as mp3, extract audio, download playlist, video info, download tiktok, download twitter video, download instagram video, download reddit video
            - clipboard_history: clipboard history, what did I copy, recent copies, search clipboard, find in clipboard, restore clipboard, clipboard log, past copies, clipboard stats, what was on my clipboard
            - wake_word: wake word, hey jarvis, voice activation, always listen, voice trigger, listen for my voice, voice command, hey computer, ok bot, enable wake word, voice wake, hands free, activate voice
            - autopilot: auto-pilot, autopilot, watch my screen and help, be proactive, help me as I work, proactive mode, smart assistant, context-aware help, auto pilot on, auto pilot off, stop auto-pilot, screen assistant
            - proactive_action: proactive action, proactive action mode, act on my behalf, take action automatically, be my jarvis, jarvis mode, proactive action on, proactive action off, stop proactive actions, act automatically, fill forms automatically, auto-click, auto-fill, continuous action mode, hands-free mode
            - gift_ideas: gift ideas, what should I get, birthday gift, Christmas gift, present for, gift for mom, gift for dad, gift for friend, gift for partner, save contact interests, gift contacts, gift suggestions, gift budget, anniversary gift, wish list, gift history
            - episodic_memory: remember this, do you remember, recall, what happened, what do you know about, memory, memories, remember when, forget this, memory stats, what do you remember about, life events, episodes, journal
            - proactive: proactive engine, proactive notifications, proactive mode, proactive status, proactive rules, enable proactive, disable proactive, quiet hours, break reminders, hydration reminders, morning briefing, proactive check
            - health_tracker: log water, log meal, log exercise, log weight, log mood, log sleep, log medication, health summary, health trend, health goals, how much water, what did I eat, calories today, weight trend, mood tracker, sleep quality, medication reminder, fitness log, workout log, daily health
            - finance_tracker: log expense, log income, budget, spending, how much did I spend, monthly report, bills, upcoming bills, debt, savings goal, financial goal, expense category, track spending, money, income vs expenses, finance summary, set budget, add bill, debt overview
            - habits: my habits, what are my patterns, what do I usually do, habit detection, behavior patterns, routine, daily routine, when do I usually, habit stats, track my habits, log habit
            - feedback: rate suggestion, good suggestion, bad suggestion, feedback stats, how are your suggestions, are you learning, was that helpful, don't suggest that, feedback loop
            - auto_skills: create skill, save as workflow, automate this, I do this every week, create workflow, my workflows, run skill, list skills, repeated actions, detect patterns, skill auto-create
            - window_manager: side by side, split screen, snap window, snap left, snap right, arrange windows, tile windows, cascade windows, open and arrange, put these side by side, window layout, quadrants, move window, focus only
            - social_monitor: social media, track mentions, birthdays, add contact, social contacts, who mentioned me, social update, track posts, important posts, facebook, twitter, instagram, linkedin, social profile, birthday reminders, social report, close contacts
            - intelligence: daily briefing, morning briefing, brief me, start my day, should I buy this, can I afford this, purchase decision, calendar conflicts, overlapping meetings, schedule conflict, plan a trip, travel plan, trip planner, plan my vacation, decision helper, conflict detector
            - video_creation: video, remotion, create video, render video, animation, slideshow, text video, video composition, make a video, generate video, animated text, video from images, programmatic video, react video, video project, setup remotion
            - trend_scout: what's new, any updates, trending, what's happening with, latest news about, YouTube updates, track interest, my interests, scout topics, what should I know about, new release, what's trending, any news about, follow updates, tech news, product launch
            - github: github, repository, repo, pull request, PR, issue, commit, branch, gist, CI, workflow, actions, git, github notifications, github activity, merge, fork, stars, github search
            - bot_window: move yourself, move to the left, move to the right, move to the corner, minimize yourself, hide yourself, go away, come back, show yourself, restore yourself, make yourself bigger, make yourself smaller, resize yourself, get out of the way, move the bot window, bot window position, where are you
            - code_runner: run python, execute python, run code, run script, execute code, python snippet, node.js code, run javascript, powershell script, bash script, shell command, run this code, evaluate code, test code, execute snippet, run this function, code sandbox, python output, node output, script output, run and show result
            - file_watcher: watch folder, watch directory, notify me when file appears, alert on new file, monitor folder for changes, watch downloads folder, watch inbox folder, file watcher, notify on new file, file monitoring, folder monitoring, watch this folder, alert when file added, detect new file, track folder changes
            - app_usage: what apps am I using, how long on computer, app time, screen time, productivity stats, which apps do I use most, app usage report, time tracking, what was I working on, app focus time, productivity breakdown, work time, app usage today, app usage this week, focus time, what have I been doing
            - custom_skills: list my skills, what skills do you have, custom skills, my saved skills, save this as a skill, create a skill, remember this routine, teach you a skill, run the X skill, my routines, saved recipes, save routine, morning brief skill, skill file

            Return at most 4 of the most relevant category names, comma-separated. If none match, return: none""";

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        if (apiKey == null || apiKey.isBlank()) {
            log.info("[ToolClassifier] No API key — AI classification disabled");
        } else {
            log.info("[ToolClassifier] Ready (model={}, baseUrl={})", model, baseUrl);
        }
    }

    /** True if this service can make classification calls. */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && httpClient != null;
    }

    /**
     * Classify a user message into tool category names.
     *
     * @return list of category name strings, or empty list on failure/timeout
     */
    public List<String> classify(String userMessage) {
        if (!isAvailable() || userMessage == null || userMessage.isBlank()) {
            return Collections.emptyList();
        }

        try {
            String requestBody = buildRequestJson(userMessage);

            String url = baseUrl.endsWith("/")
                    ? baseUrl + "v1/chat/completions"
                    : baseUrl + "/v1/chat/completions";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.debug("[ToolClassifier] API returned HTTP {}", response.statusCode());
                return Collections.emptyList();
            }

            String content = extractContent(response.body());
            List<String> categories = parseCategories(content);
            log.debug("[ToolClassifier] '{}' → {}", truncate(userMessage, 40), categories);
            return categories;

        } catch (Exception e) {
            log.debug("[ToolClassifier] Classification failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ═══ JSON helpers (manual to avoid extra dependencies) ═══

    private String buildRequestJson(String userMessage) {
        return """
                {"model":"%s","temperature":0,"max_tokens":60,"messages":[{"role":"system","content":"%s"},{"role":"user","content":"%s"}]}"""
                .formatted(
                        escapeJson(model),
                        escapeJson(SYSTEM_PROMPT),
                        escapeJson(userMessage)
                );
    }

    /** Extract the assistant content from the OpenAI chat completions response. */
    private static String extractContent(String json) {
        // Find "content":"..." in the response — simple pattern for this predictable JSON
        int idx = json.indexOf("\"content\":");
        // Skip past system/user messages to find the assistant's content
        // The response has choices[0].message.content
        int choicesIdx = json.indexOf("\"choices\"");
        if (choicesIdx < 0) return "";
        int contentIdx = json.indexOf("\"content\":", choicesIdx);
        if (contentIdx < 0) return "";

        int start = json.indexOf('"', contentIdx + 10);
        if (start < 0) return "";
        start++; // skip opening quote

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                i++;
                char next = json.charAt(i);
                if (next == 'n') sb.append('\n');
                else if (next == 't') sb.append('\t');
                else sb.append(next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** Parse comma-separated category names, filtering to valid ones only. Max 3 results. */
    private static final int MAX_CATEGORIES = 3;

    private static List<String> parseCategories(String content) {
        if (content == null || content.isBlank() || content.equalsIgnoreCase("none")) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String part : content.split("[,\\s]+")) {
            String name = part.trim().toLowerCase();
            if (VALID_CATEGORIES.contains(name)) {
                result.add(name);
                if (result.size() >= MAX_CATEGORIES) break;
            }
        }
        return result;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String truncate(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) + "..." : s;
    }
}
