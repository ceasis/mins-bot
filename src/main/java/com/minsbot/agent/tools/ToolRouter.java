package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Dynamic tool selection router. Keeps each API call under OpenAI's 128-tool
 * limit by matching user messages to relevant tool categories via keyword regex.
 *
 * <p>Always includes "core" tools. Adds category-specific tools when keywords
 * match. Falls back to a broad default set for generic messages.</p>
 *
 * <p>To add a new tool:
 * <ol>
 *   <li>Inject it in the constructor</li>
 *   <li>Add it to the appropriate category in {@link #buildCategories()}</li>
 *   <li>Or create a new category with keywords</li>
 * </ol></p>
 */
@Component
public class ToolRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

    // ─── All tool beans ───

    private final DirectivesTools directivesTools;
    private final DirectiveDataTools directiveDataTools;
    private final MemoryTools memoryTools;
    private final ChatHistoryTool chatHistoryTool;
    private final TaskStatusTool taskStatusTool;
    private final ClipboardTools clipboardTools;

    private final PlaywrightTools playwrightTools;
    private final DownloadTools downloadTools;
    private final WebScraperTools webScraperTools;
    private final BrowserTools browserTools;

    private final FileTools fileTools;
    private final FileSystemTools fileSystemTools;

    private final SystemTools systemTools;

    private final ImageTools imageTools;
    private final PdfTools pdfTools;
    private final TtsTools ttsTools;

    private final LocalModelTools localModelTools;
    private final HuggingFaceImageTool huggingFaceImageTool;
    private final SummarizationTools summarizationTools;
    private final ModelSwitchTools modelSwitchTools;

    private final EmailTools emailTools;
    private final WeatherTools weatherTools;

    private final ScheduledTaskTools scheduledTaskTools;
    private final TimerTools timerTools;
    private final NotificationTools notificationTools;

    private final CalculatorTools calculatorTools;
    private final QrTools qrTools;
    private final HashTools hashTools;
    private final UnitConversionTools unitConversionTools;

    private final ExportTools exportTools;
    private final SitesConfigTools sitesConfigTools;
    private final CronConfigTools cronConfigTools;
    private final ScreenMemoryTools screenMemoryTools;
    private final AudioMemoryTools audioMemoryTools;
    private final GlobalHotkeyService globalHotkeyService;
    private final PluginLoaderService pluginLoaderService;
    private final SystemTrayService systemTrayService;

    @Autowired(required = false)
    private ToolClassifierService classifier;

    // ─── Registries (built once at startup) ───

    private final List<Object> coreTools;
    private final List<Category> categories;
    private final List<Object> defaultTools;

    private final List<Object> autonomousCoreTools;
    private final List<Category> autonomousCategories;
    private final List<Object> autonomousDefaultTools;

    public ToolRouter(
            DirectivesTools directivesTools,
            DirectiveDataTools directiveDataTools,
            MemoryTools memoryTools,
            ChatHistoryTool chatHistoryTool,
            TaskStatusTool taskStatusTool,
            ClipboardTools clipboardTools,
            PlaywrightTools playwrightTools,
            DownloadTools downloadTools,
            WebScraperTools webScraperTools,
            BrowserTools browserTools,
            FileTools fileTools,
            FileSystemTools fileSystemTools,
            SystemTools systemTools,
            ImageTools imageTools,
            PdfTools pdfTools,
            TtsTools ttsTools,
            LocalModelTools localModelTools,
            HuggingFaceImageTool huggingFaceImageTool,
            SummarizationTools summarizationTools,
            ModelSwitchTools modelSwitchTools,
            EmailTools emailTools,
            WeatherTools weatherTools,
            ScheduledTaskTools scheduledTaskTools,
            TimerTools timerTools,
            NotificationTools notificationTools,
            CalculatorTools calculatorTools,
            QrTools qrTools,
            HashTools hashTools,
            UnitConversionTools unitConversionTools,
            ExportTools exportTools,
            SitesConfigTools sitesConfigTools,
            CronConfigTools cronConfigTools,
            ScreenMemoryTools screenMemoryTools,
            AudioMemoryTools audioMemoryTools,
            GlobalHotkeyService globalHotkeyService,
            PluginLoaderService pluginLoaderService,
            SystemTrayService systemTrayService) {

        this.directivesTools = directivesTools;
        this.directiveDataTools = directiveDataTools;
        this.memoryTools = memoryTools;
        this.chatHistoryTool = chatHistoryTool;
        this.taskStatusTool = taskStatusTool;
        this.clipboardTools = clipboardTools;
        this.playwrightTools = playwrightTools;
        this.downloadTools = downloadTools;
        this.webScraperTools = webScraperTools;
        this.browserTools = browserTools;
        this.fileTools = fileTools;
        this.fileSystemTools = fileSystemTools;
        this.systemTools = systemTools;
        this.imageTools = imageTools;
        this.pdfTools = pdfTools;
        this.ttsTools = ttsTools;
        this.localModelTools = localModelTools;
        this.huggingFaceImageTool = huggingFaceImageTool;
        this.summarizationTools = summarizationTools;
        this.modelSwitchTools = modelSwitchTools;
        this.emailTools = emailTools;
        this.weatherTools = weatherTools;
        this.scheduledTaskTools = scheduledTaskTools;
        this.timerTools = timerTools;
        this.notificationTools = notificationTools;
        this.calculatorTools = calculatorTools;
        this.qrTools = qrTools;
        this.hashTools = hashTools;
        this.unitConversionTools = unitConversionTools;
        this.exportTools = exportTools;
        this.sitesConfigTools = sitesConfigTools;
        this.cronConfigTools = cronConfigTools;
        this.screenMemoryTools = screenMemoryTools;
        this.audioMemoryTools = audioMemoryTools;
        this.globalHotkeyService = globalHotkeyService;
        this.pluginLoaderService = pluginLoaderService;
        this.systemTrayService = systemTrayService;

        this.coreTools = buildCoreTools();
        this.categories = buildCategories();
        this.defaultTools = buildDefaultTools();

        this.autonomousCoreTools = buildAutonomousCoreTools();
        this.autonomousCategories = buildAutonomousCategories();
        this.autonomousDefaultTools = buildAutonomousDefaultTools();
    }

    // ═══ Public API ═══

    /** Select tools for a normal interactive chat request. */
    public Object[] selectTools(String message) {
        return doSelect(message, coreTools, categories, defaultTools);
    }

    /** Select tools for an autonomous-mode step. */
    public Object[] selectToolsForAutonomous(String message) {
        return doSelect(message, autonomousCoreTools, autonomousCategories, autonomousDefaultTools);
    }

    /** Returns true if the message triggers any specific tool category (not just defaults). */
    public boolean hasSpecificMatch(String message) {
        String lower = (message == null) ? "" : message.toLowerCase();
        for (Category cat : categories) {
            if (cat.pattern.matcher(lower).find()) return true;
        }
        return false;
    }

    // ═══ Selection logic ═══

    private Object[] doSelect(String message,
                              List<Object> core,
                              List<Category> cats,
                              List<Object> defaults) {
        Set<Object> selected = new LinkedHashSet<>(core);
        String lower = (message == null) ? "" : message.toLowerCase();

        // Fast path: regex keyword matching (instant)
        boolean anyMatch = false;
        for (Category cat : cats) {
            if (cat.pattern.matcher(lower).find()) {
                selected.addAll(cat.tools);
                anyMatch = true;
                log.debug("[ToolRouter] Regex matched '{}'", cat.name);
            }
        }

        // AI classification fallback: when regex misses
        if (!anyMatch && classifier != null && classifier.isAvailable()) {
            List<String> aiCategories = classifier.classify(message);
            for (String catName : aiCategories) {
                for (Category cat : cats) {
                    if (cat.name.equals(catName)) {
                        selected.addAll(cat.tools);
                        anyMatch = true;
                        log.debug("[ToolRouter] AI matched '{}'", cat.name);
                    }
                }
            }
        }

        if (!anyMatch) {
            selected.addAll(defaults);
        }

        Object[] result = selected.toArray();
        log.info("[ToolRouter] Selected {} tool bean(s) for: {}",
                result.length, truncate(message, 60));
        return result;
    }

    // ═══ Category definitions ═══

    private List<Object> buildCoreTools() {
        return List.of(
                directivesTools, directiveDataTools, memoryTools,
                chatHistoryTool, taskStatusTool, clipboardTools);
    }

    private List<Category> buildCategories() {
        List<Category> cats = new ArrayList<>();

        cats.add(new Category("chat_browser",
                kw("in-browser", "chat browser", "in the chat browser",
                   "in browser tab", "built-in browser", "mins bot browser"),
                List.of(playwrightTools, downloadTools, sitesConfigTools)));

        cats.add(new Category("browser",
                kw("search", "browse", "web", "url", "page", "google", "bing",
                   "link", "website", "look up", "find online", "navigate",
                   "surf", "http", "youtube", "open youtube", "open google",
                   "new tab", "close tab", "switch tab", "next tab", "previous tab",
                   "refresh page", "go back", "go forward", "address bar"),
                List.of(playwrightTools, downloadTools, sitesConfigTools, systemTools)));

        cats.add(new Category("sites",
                kw("login", "log in", "sign in", "credential", "password",
                   "username", "site", "account", "saved site"),
                List.of(sitesConfigTools)));

        cats.add(new Category("files",
                kw("file", "folder", "directory", "drive", "disk", "copy",
                   "move", "delete", "rename", "zip", "extract", "unzip",
                   "create", "path", "list files", "read file", "write file",
                   "documents", "open folder"),
                List.of(fileTools, fileSystemTools)));

        cats.add(new Category("system",
                kw("app", "window", "process", "screenshot", "wallpaper",
                   "powershell", "cmd", "command", "close", "open app",
                   "minimize", "lock", "shutdown", "sleep", "hibernate",
                   "mute", "unmute", "volume", "ip address", "ping",
                   "quit", "exit", "close mins bot",
                   "recent", "keystroke", "system info", "running",
                   "environment", "env var", "focus",
                   "click", "mouse", "drag", "scroll", "double click",
                   "right click", "cursor", "screen size", "coordinates",
                   "open youtube", "open google", "open website",
                   "url", "http", "browse"),
                List.of(systemTools)));

        cats.add(new Category("media",
                kw("image", "photo", "picture", "flip", "rotate", "resize",
                   "grayscale", "black and white", "pdf", "speak",
                   "read aloud", "tts", "voice", "say this", "say ", "say something"),
                List.of(imageTools, pdfTools, ttsTools)));

        cats.add(new Category("ai_model",
                kw("ollama", "model", "switch model", "local model", "openai",
                   "classify", "nsfw", "summarize", "summary", "hugging",
                   "huggingface", "llama", "mistral", "phi", "deepseek"),
                List.of(localModelTools, huggingFaceImageTool,
                        summarizationTools, modelSwitchTools)));

        cats.add(new Category("communication",
                kw("email", "send mail", "inbox", "weather",
                   "temperature", "forecast", "rain", "sunny"),
                List.of(emailTools, weatherTools)));

        cats.add(new Category("scheduling",
                kw("remind", "reminder", "timer", "schedule", "alarm",
                   "recurring", "notify", "notification", "alert",
                   "every \\d+ minute", "every \\d+ second", "every \\d+ hour",
                   "every minute", "every hour", "every day", "cron"),
                List.of(scheduledTaskTools, timerTools, notificationTools, cronConfigTools)));

        cats.add(new Category("utility",
                kw("calculate", "math", "qr", "hash", "sha", "md5",
                   "convert", "unit", "miles", "km", "celsius",
                   "fahrenheit", "encode", "decode",
                   "\\d+\\s*[+*x/\\-]\\s*\\d+"),
                List.of(calculatorTools, qrTools, hashTools,
                        unitConversionTools)));

        cats.add(new Category("export",
                kw("export", "save chat", "chat history to file"),
                List.of(exportTools)));

        cats.add(new Category("plugins",
                kw("plugin", "jar", "load plugin", "unload plugin"),
                List.of(pluginLoaderService)));

        cats.add(new Category("hotkeys",
                kw("hotkey", "shortcut", "keyboard shortcut", "global key"),
                List.of(globalHotkeyService)));

        cats.add(new Category("tray",
                kw("tray", "system tray", "tray icon"),
                List.of(systemTrayService)));

        cats.add(new Category("screen_memory",
                kw("screen memory", "what happened", "what was i doing",
                   "what am i watching", "what i'm watching", "what im watching",
                   "what(.*)watching", "what's on screen", "what is on screen",
                   "what am i looking at", "what i'm looking at", "what do i see",
                   "what is on my screen", "on my screen", "looking at right now",
                   "see right now", "what do i see right now", "how about now",
                   "what do i watch right now",
                   "remember.*screen", "last monday", "last tuesday",
                   "last wednesday", "last thursday", "last friday",
                   "last saturday", "last sunday", "yesterday.*do",
                   "ocr", "capture.*remember", "video playback", "playback information"),
                List.of(screenMemoryTools)));

        cats.add(new Category("audio_memory",
                kw("audio memory", "what was playing", "what was i listening",
                   "what am i listening", "what i'm listening", "what im listening",
                   "system audio", "speaker audio", "what song",
                   "music playing", "capture audio", "what did i hear",
                   "audio yesterday", "audio last", "listening to",
                   "what.*listening", "what.*hear", "can you hear", "do you hear",
                   "are you hearing", "hear.*(it|audio|music)", "playback",
                   "record it", "record audio", "record.*(it|audio|music)",
                   "start to capture", "start capture", "start capturing", "begin.*captur",
                   "start recording", "start recording audio", "recording audio",
                   "list audio devices", "audio capture devices", "what capture device", "no audio captured"),
                List.of(audioMemoryTools)));

        return Collections.unmodifiableList(cats);
    }

    private List<Object> buildDefaultTools() {
        return List.of(
                systemTools, fileTools, fileSystemTools,
                playwrightTools, downloadTools, imageTools,
                weatherTools, notificationTools, calculatorTools,
                scheduledTaskTools, summarizationTools, emailTools);
    }

    // ─── Autonomous mode (reduced palette) ───

    private List<Object> buildAutonomousCoreTools() {
        return List.of(
                directivesTools, directiveDataTools,
                chatHistoryTool, taskStatusTool, clipboardTools);
    }

    private List<Category> buildAutonomousCategories() {
        List<Category> cats = new ArrayList<>();

        cats.add(new Category("browser",
                kw("search", "browse", "web", "url", "page", "google",
                   "link", "website", "look up", "find online", "navigate", "http"),
                List.of(playwrightTools)));

        cats.add(new Category("files",
                kw("file", "folder", "directory", "drive", "disk",
                   "copy", "move", "delete", "rename", "zip", "create", "path"),
                List.of(fileTools, fileSystemTools)));

        cats.add(new Category("system",
                kw("app", "window", "screenshot", "powershell", "cmd",
                   "close", "open", "minimize", "system"),
                List.of(systemTools)));

        cats.add(new Category("media",
                kw("image", "photo", "picture", "resize"),
                List.of(imageTools)));

        cats.add(new Category("ai_model",
                kw("ollama", "local model", "summarize", "summary", "classify"),
                List.of(localModelTools, summarizationTools)));

        cats.add(new Category("communication",
                kw("email", "send mail"),
                List.of(emailTools)));

        cats.add(new Category("scheduling",
                kw("schedule", "reminder", "recurring", "every minute", "every hour", "cron"),
                List.of(scheduledTaskTools, cronConfigTools)));

        cats.add(new Category("export",
                kw("export", "save chat"),
                List.of(exportTools)));

        return Collections.unmodifiableList(cats);
    }

    private List<Object> buildAutonomousDefaultTools() {
        return List.of(
                systemTools, fileTools, fileSystemTools,
                playwrightTools, imageTools, emailTools,
                scheduledTaskTools, summarizationTools,
                exportTools, localModelTools);
    }

    // ═══ Helpers ═══

    /** Build a compiled regex from keyword strings. Word-boundary aware. */
    private static Pattern kw(String... words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append("|");
            String w = words[i];
            if (w.contains("\\") || w.contains("[") || w.contains("(")) {
                sb.append("(?:").append(w).append(")");
            } else {
                sb.append("\\b").append(Pattern.quote(w)).append("\\b");
            }
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record Category(String name, Pattern pattern, List<Object> tools) {}
}
