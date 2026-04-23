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
            "travel", "research", "knowledge", "briefing", "calendar", "gmail", "drive",
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
            "custom_skills",
            "barge_in",
            "restart",
            "orchestrator",
            "reminders",
            "youtube",
            "dev_skills",
            "productivity_skills",
            "seo_marketing_skills",
            "security_skills",
            "profession_skills",
            "data_skills_extra",
            "calc_skills",
            "extras_skills",
            "integrations",
            "maps",
            "upcoming",
            "recurring_task",
            "windows_settings",
            "journal",
            "screen_watcher",
            "file_search",
            "meeting_mode",
            "heygen",
            "veo",
            "pdf_advanced",
            "pdf_password",
            "web_to_pdf",
            "youtube_transcript",
            "skill_packs"
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
            - media: images, photos, PDF, text-to-speech, voice, GENERATE an image (create/draw/make/render/paint a picture, illustration, scene, portrait, logo) via local ComfyUI
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
            - gmail: check gmail, unread emails, new emails, check my email, inbox, gmail messages, any new mail, email summary, check my work email, check all my gmails, unread across accounts
            - drive: google drive, my drive, search drive, find file in drive, read drive file, list drive files, docs, sheets, slides, what's in my drive
            - web_monitor: monitor website, watch for changes, alert me if website changes, check URL every N minutes, track website updates, website monitoring, watch this page, notify me when site changes, monitor for stock, alert when back in stock, watch for restock, notify me when X is available, watch for a price drop, check price change, drop alert, tell me when Nike/Adidas/PS5/anything restocks, poll this URL, keep checking this URL, background-check this page. PREFER THIS CATEGORY for ANY request about watching a URL in the background for later changes — restocks, price drops, article updates, availability alerts — instead of refusing. The bot has WebMonitorTools with startMonitor/stopMonitor/listMonitors/checkNow that do exactly this.
            - code_audit: clone repo, git clone, code audit, scan code, security scan, vulnerability scan, SQL injection, hardcoded secrets, unused imports, code review, audit repository, scan for vulnerabilities, check for secrets
            - backup: backup config files, backup configs, backup .env, backup properties, backup important configs, backup settings, backup yml files, backup configuration files, config backup, settings backup
            - music: play music, pause music, play [song title], play [artist name], play [album], play [genre], play [mood], listen to X, put on X, play something, play my liked songs, play my music, next song, previous song, skip track, volume up, volume down, mute, unmute, louder, quieter, set volume to N, set audio to N, audio to N%, volume to N%, max volume, full volume, volume 50, audio 42, volume percent, exact volume, put volume at N, turn volume up to N, play on spotify, what song is playing, search spotify, play/pause, stop music, current track, open spotify. PREFER THIS CATEGORY for any "play X" / "listen to X" request about music/songs/artists/playlists, AND for any request to set volume or audio to a specific number — do NOT route such requests to browser or search.
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
            - barge_in: let me interrupt, stop barge-in, turn on barge-in, can I cut you off, let me interrupt you, barge in, don't interrupt yourself, interrupt TTS, stop talking when I talk, JARVIS-style interrupt
            - restart: restart yourself, reboot, restart the bot, restart mins bot, quit and start again, relaunch, reboot yourself, restart app, reload the bot, apply new prompt and restart
            - orchestrator: complex multi-step task, plan and execute, marketing campaign, research and produce, video production, decompose and parallelize, spawn agents, use agents, delegate subtasks, parallel work, make me a X with multiple parts, coordinate multiple agents, divide and conquer, big project, multi-phase task
            - reminders: remind me to X, remind me every day at, remind me weekly, set a reminder, schedule a reminder, daily reminder, weekly reminder, help me with X (lifestyle goal — weight, sleep, learn, hydration, exercise, habit), help me reduce weight, help me sleep better, help me learn, help me build habit, list my reminders, what reminders do I have, pause reminder, delete reminder, turn off reminder
            - youtube: my youtube channel, my subscribers, my youtube uploads, youtube video stats, video views, video likes, recent uploads, search youtube, find video on youtube, trending on youtube, what's trending, my subscriptions, channels I follow, youtube analytics, video details, watch history, youtube channel info, my youtube, youtuber, channel stats
            - maps: open google maps, open maps to X, show me X on a map, where is X, find X on the map, navigate to X, directions to X, directions from X to Y, how do I get to X, route to X, take me to X, show me on the map, coffee shops near me, restaurants near X, gas stations nearby, find nearby X, address of X, location of X, map coordinates, latitude longitude, pin this address. PREFER THIS CATEGORY for any mention of a specific place/address/landmark/business the user wants to see on a map.
            - upcoming: what's coming up, what's important, anything important, next 3 days, next few days, this week, upcoming things, upcoming events, what do I have this week, what's on my plate, what's ahead, brief me on the week, what should I know about, any birthdays coming up, bills this week, what's next, what's scheduled, things to remember, what's happening this week, anything I should prepare for. PREFER THIS CATEGORY when the user asks an umbrella question about upcoming days — it aggregates calendar, bills, birthdays, reminders, weather in one call.
            - recurring_task: every day at X, every morning at X, every night at X, daily at 8pm, every 8pm, each day at X, at X tell me, at X give me, at X send me, every Monday, every weekday at, at 9am remind me to, every hour tell me, every 30 min share, recurring quote, daily fun fact, daily motivation, daily reminder, nightly check, morning greeting, create a recurring task, schedule a recurring, set up a daily, cron task, list my recurring tasks, delete my recurring task, pause recurring, my scheduled tasks. PREFER THIS CATEGORY for any request to set up, list, or remove a repeating/scheduled AI-driven chat task (vs a one-shot reminder).
            - windows_settings: set brightness, dim screen, brighter, dark mode, light mode, night light, blue filter, mute mic, unmute microphone, switch audio output, change speakers, turn wifi on/off, wifi off, enable bluetooth, disable bluetooth, power plan, performance mode, battery saver, balanced power, lock screen, lock my pc, sleep now, shut down in N minutes, shutdown now, cancel shutdown, restart my pc, reboot, show hidden files, hide hidden files, show file extensions, taskbar to left, center taskbar, empty recycle bin, clear trash, change wallpaper, set background, caps lock on, num lock, clipboard history on/off, disable notifications, open display settings, open sound settings, open bluetooth settings, open settings page. PREFER THIS CATEGORY for any Windows system-level configuration request — brightness, theme, network, power, keyboard locks, taskbar tweaks, wallpaper, etc.
            - journal: write my journal, end of day journal, reflect on today, recap my day, end-of-day reflection, summarize my day into a journal, daily journal entry, today's journal, how was my day. PREFER THIS CATEGORY for journal-writing requests — it auto-pulls app usage, today's memories, and recent chat to compose a reflective entry.
            - screen_watcher: tell me when X appears on my screen, alert me when upload hits 100, ping me when CI turns green, notify me when this page changes, watch this area of my screen, wake me when the status badge changes, watch the progress bar, watch the build finish, monitor this region. PREFER THIS CATEGORY for region-based visual watching (different from web_monitor which is URL-based).
            - file_search: where did I put the X file, find my notes about Y, semantic file search, search my documents for Z, find that doc where I wrote about, search my files by meaning, index my Documents folder, build file index, find by content. PREFER THIS CATEGORY when the user doesn't know the filename but wants files matching a topic.
            - meeting_mode: auto-transcribe my meetings, meeting mode, listen to my zoom calls, teams meeting notes, enable meeting mode, auto-note my calls, record my meetings, meeting transcription, summarize my last meeting. PREFER THIS CATEGORY for auto-detecting video/voice calls and transcribing them.
            - heygen: heygen, ai avatar video, talking head video, generate avatar video, create ai video of me saying, list heygen avatars, list heygen voices, heygen credits, heygen quota, check heygen video status, make a spokesperson video, ai video of me, avatar speaking my text, video generation ai. PREFER THIS CATEGORY for any AI video / avatar generation via HeyGen.
            - veo: veo, google veo, flow video, text to video, cinematic ai video, generate veo video, ai video of a X doing Y, create a video of X, scene generation, generative video, gemini video, veo status, download veo video. PREFER THIS CATEGORY for text-to-video generation via Google Veo (cinematic, no avatar). Use 'heygen' instead when the user wants a talking-head avatar.
            - pdf_advanced: merge these PDFs, combine pdfs, split this pdf, split pdf into chapters, extract pages, keep only pages, rotate pdf, fix scan orientation, pdf info, pdf metadata, page count, is this pdf encrypted, reorder pages. PREFER THIS CATEGORY for PDF manipulation (merge/split/rotate/extract). Different from 'files' which is general file ops.
            - pdf_password: unlock my pdf, forgot pdf password, pdf password recovery, try passwords on pdf, decrypt pdf, brute force pdf, crack pdf password, pdf is password protected, I lost the pdf password. PREFER THIS CATEGORY for encrypted-PDF recovery with a wordlist or hints.
            - web_to_pdf: save this page as pdf, archive this article, save webpage as pdf, print page to pdf, save this recipe as pdf, download page as pdf, convert webpage to pdf, save url as pdf, web to pdf. PREFER THIS CATEGORY to render a URL into a PDF file on disk.
            - youtube_transcript: youtube transcript, get transcript of this youtube video, what does this video say, transcribe youtube, captions of this video, summarize this youtube video, tldr this youtube, bullet points of this video, what is this video about. PREFER THIS CATEGORY for fetching captions / summarizing videos (no API key needed).
            - skill_packs: use the github skill, run the 1password skill, use the notion skill, use the discord skill, use the whisper skill, list available skill packs, what skill packs are installed, invoke the X skill, run skill X, show me what skills are available, use an external CLI skill (gh, op, whisper, etc), run a bash/shell command via a known skill. PREFER THIS CATEGORY whenever the user references an external CLI tool (gh, op, whisper, ffmpeg, yt-dlp, discord, notion, obsidian, slack, etc) OR asks to list/run a named skill pack.
            - dev_skills: encode this, decode base64, hex encode, url encode, hash this string, MD5, SHA-256, SHA-1, pretty print JSON, minify JSON, validate JSON, test regex, regex match, generate UUID, generate password, random number, roll dice, identify hash, what kind of hash, parse cron, next cron runs, convert unit, meters to feet, kg to lb, C to F, GB to MB, bytes conversion
            - productivity_skills: create note, save note, search notes, remind me to, set a reminder, list reminders, start timer, countdown, list timers, clipboard history search, create OKR, list OKRs, what time is it in, convert timezone, what time is in Tokyo, meeting cost, how much does this meeting cost, SLA downtime, uptime calculator, 99.9 uptime, downtime budget
            - seo_marketing_skills: analyze SEO, meta tags, og tags, analyze meta, extract keywords from url, top keywords, check sitemap, analyze sitemap, check robots.txt, parse robots, readability score, flesch score, generate slug, url slug, SEO slug, build UTM url, UTM tracking, UTM builder, email subject score, subject line analysis, character count platforms, twitter char count, AB test significance, ab test calculator, statistical significance, suggest hashtags, hashtag extractor
            - security_skills: password strength, is my password strong, HIBP, have i been pwned, password breach, check password breach, decode JWT, JWT inspector, verify JWT, SSL cert, certificate check, TLS cert, audit HTTP headers, CSP check, security headers, DNS lookup, MX records, TXT records, CVE lookup, vulnerability lookup, scan for secrets, detect API keys in text, leaked credentials, email validation, validate email, check MX
            - profession_skills: compound interest, loan payment, mortgage calc, calculate NPV, IRR, tax calculator, tax brackets, cap rate, cash on cash, 1% rule, stock RSI, stock SMA, stock EMA, MACD indicator, BMI calculator, BMR calculator, TDEE, calorie needs, body fat, lab units, mg/dL to mmol/L, glucose mmol, scale recipe, convert cups, cooking conversion, grade calculator, weighted average grade, GPA calculator, area of circle, volume of cylinder, geometry, format citation, APA MLA Chicago, statistical summary, mean median stdev, analyze writing, passive voice check, reading time, detect language, translate detect, color contrast WCAG, color palette, complementary color, image dimensions, inspect image
            - data_skills_extra: describe CSV, parse CSV, extract column from CSV, filter CSV rows, CSV to JSON, text diff, compare two strings, unified diff, string similarity, validate YAML, YAML to JSON, JSON to YAML, pretty-print SQL, format SQL query, markdown TOC, dockerfile lint, dockerfile best practices, analyze logs, log patterns, error samples, infer regex from examples, generate regex from examples, HTTP test, test API endpoint, curl equivalent, generate fake data, test data, mock records
            - calc_skills: factorial, combinations, permutations, binomial distribution, normal distribution, poisson, matrix multiply, matrix determinant, matrix transpose, physics velocity, kinematic equation, kinetic energy, potential energy, force mass acceleration, ohm's law, power watts, haversine, distance between lat lon, great circle distance, break-even analysis, break-even units, depreciation schedule, straight-line depreciation, declining balance, cash flow forecast, runway months, macro split, protein carbs fat grams, keto macros, running pace, pace per km, pace per mile, marathon time, heart rate zones, max HR, training zones
            - extras_skills: markdown to html, html to markdown, score headline, headline effectiveness, analyze headline, number to words, words to number, roman numerals, redact PII, strip PII, anonymize text, remove EXIF, strip metadata from image, AES encrypt, AES decrypt, encrypt text with password, make flashcards, Anki cards, question answer pairs, pomodoro schedule, pomodoro planner, task schedule
            - integrations: list integrations, show my integrations, which services are connected, configure integration, connect Stripe, connect Notion, connect Jira, connect Shopify, connect Trello, connect Airtable, connect Salesforce, connect HubSpot, connect Mailchimp, connect Figma, connect Dropbox, connect Calendly, call Stripe API, call Notion API, call GitHub API, call Linear API, call Asana API, query Jira, fetch from Notion, create Trello card, post to Slack via API, Stripe customers, Notion database, Linear issues, Jira tickets, Asana tasks, Airtable records, Salesforce contacts, HubSpot deals, integration status, setup integration, third-party API, external API call

            PRIORITY RULE: When a request matches BOTH a "*_skills" category AND a general category \
            (e.g. "dev_skills" vs "utility", "data_skills_extra" vs "research", "extras_skills" vs "media"), \
            ALWAYS include the *_skills category and prefer it. Skills are purpose-built and more reliable \
            than general tools for the same job. Only omit a *_skills category if it clearly doesn't apply.

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
