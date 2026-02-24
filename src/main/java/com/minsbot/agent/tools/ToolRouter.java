package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Dynamic tool selection router. Keeps each API call under OpenAI's 128-tool
 * limit by using AI classification (gpt-4o-mini) to match user messages to
 * relevant tool categories, with a hard tool-count cap enforced via reflection.
 *
 * <p>At startup, counts the actual @Tool methods on each bean. During selection,
 * tracks the running total and stops adding categories before exceeding the limit.
 * This scales to any number of tools/categories — even 1000+.</p>
 */
@Component
public class ToolRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

    /** OpenAI's maximum tools per API call. */
    private static final int MAX_TOOLS = 128;
    /** Leave headroom for safety (internal Spring AI wrappers, etc.). */
    private static final int TOOL_BUDGET = MAX_TOOLS - 5; // 123

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
    private final PlaylistTools playlistTools;
    private final GlobalHotkeyService globalHotkeyService;
    private final PluginLoaderService pluginLoaderService;
    private final SystemTrayService systemTrayService;

    @Autowired(required = false)
    private ToolClassifierService classifier;

    // ─── Registries ───

    /** Bean → number of @Tool methods (computed once via reflection). */
    private final Map<Object, Integer> toolCounts = new IdentityHashMap<>();

    private final List<Object> coreTools;
    private final Map<String, List<Object>> categories;
    private final List<Object> defaultTools;

    private final List<Object> autonomousCoreTools;
    private final Map<String, List<Object>> autonomousCategories;
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
            PlaylistTools playlistTools,
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
        this.playlistTools = playlistTools;
        this.globalHotkeyService = globalHotkeyService;
        this.pluginLoaderService = pluginLoaderService;
        this.systemTrayService = systemTrayService;

        // Count @Tool methods on every bean (once, via reflection)
        countToolsOnAllBeans();

        this.coreTools = buildCoreTools();
        this.categories = buildCategories();
        this.defaultTools = buildDefaultTools();

        this.autonomousCoreTools = buildAutonomousCoreTools();
        this.autonomousCategories = buildAutonomousCategories();
        this.autonomousDefaultTools = buildAutonomousDefaultTools();

        int coreCount = countTools(coreTools);
        int totalRegistered = toolCounts.values().stream().mapToInt(Integer::intValue).sum();
        log.info("[ToolRouter] {} total @Tool methods across {} beans. Core uses {} tools. Budget per request: {}",
                totalRegistered, toolCounts.size(), coreCount, TOOL_BUDGET);
    }

    // ═══ Public API ═══

    public Object[] selectTools(String message) {
        return doSelect(message, coreTools, categories, defaultTools);
    }

    public Object[] selectToolsForAutonomous(String message) {
        return doSelect(message, autonomousCoreTools, autonomousCategories, autonomousDefaultTools);
    }

    public boolean hasSpecificMatch(String message) {
        if (classifier == null || !classifier.isAvailable()) return false;
        List<String> matched = classifier.classify(message);
        return matched != null && !matched.isEmpty();
    }

    // ═══ Selection logic (with tool count enforcement) ═══

    private Object[] doSelect(String message,
                              List<Object> core,
                              Map<String, List<Object>> catMap,
                              List<Object> defaults) {
        Set<Object> selected = new LinkedHashSet<>(core);
        int used = countTools(selected);

        boolean anyMatch = false;

        // AI classification: ask gpt-4o-mini which categories this message needs
        if (classifier != null && classifier.isAvailable()) {
            List<String> aiCategories = classifier.classify(message);
            for (String catName : aiCategories) {
                List<Object> catTools = catMap.get(catName);
                if (catTools == null) continue;

                // Check if adding this category would exceed the budget
                int catCost = countToolsExcluding(catTools, selected);
                if (used + catCost > TOOL_BUDGET) {
                    log.debug("[ToolRouter] Skipping '{}' ({} tools) — would exceed budget ({}/{})",
                            catName, catCost, used, TOOL_BUDGET);
                    continue;
                }

                selected.addAll(catTools);
                used += catCost;
                anyMatch = true;
                log.debug("[ToolRouter] AI matched '{}' (+{} tools, total {})", catName, catCost, used);
            }
        }

        // Fallback: if AI unavailable or matched nothing
        if (!anyMatch) {
            for (Object bean : defaults) {
                int cost = selected.contains(bean) ? 0 : toolCounts.getOrDefault(bean, 0);
                if (used + cost > TOOL_BUDGET) break;
                if (selected.add(bean)) used += cost;
            }
        }

        Object[] result = selected.toArray();
        log.info("[ToolRouter] Selected {} bean(s) ({} tools) for: {}",
                result.length, used, truncate(message, 60));
        return result;
    }

    // ═══ Tool counting ═══

    /** Count @Tool methods on a bean via reflection. Cached in toolCounts map. */
    private void countToolsOnAllBeans() {
        Object[] allBeans = {
                directivesTools, directiveDataTools, memoryTools,
                chatHistoryTool, taskStatusTool, clipboardTools,
                playwrightTools, downloadTools, webScraperTools, browserTools,
                fileTools, fileSystemTools, systemTools,
                imageTools, pdfTools, ttsTools,
                localModelTools, huggingFaceImageTool, summarizationTools, modelSwitchTools,
                emailTools, weatherTools,
                scheduledTaskTools, timerTools, notificationTools,
                calculatorTools, qrTools, hashTools, unitConversionTools,
                exportTools, sitesConfigTools, cronConfigTools,
                screenMemoryTools, audioMemoryTools, playlistTools,
                globalHotkeyService, pluginLoaderService, systemTrayService
        };
        for (Object bean : allBeans) {
            if (bean != null && !toolCounts.containsKey(bean)) {
                // Unwrap CGLIB proxy to get the original class — proxy methods lose @Tool annotations
                Class<?> targetClass = AopUtils.isAopProxy(bean)
                        ? AopUtils.getTargetClass(bean) : bean.getClass();
                int count = 0;
                for (Method m : targetClass.getMethods()) {
                    if (m.isAnnotationPresent(Tool.class)) count++;
                }
                toolCounts.put(bean, count);
                if (count > 0) {
                    log.debug("[ToolRouter] {} → {} @Tool methods", targetClass.getSimpleName(), count);
                }
            }
        }
    }

    /** Count total @Tool methods across a set of beans (deduplicates). */
    private int countTools(Collection<Object> beans) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int total = 0;
        for (Object bean : beans) {
            if (seen.add(bean)) {
                total += toolCounts.getOrDefault(bean, 0);
            }
        }
        return total;
    }

    /** Count how many NEW tools these beans would add (excluding already-selected ones). */
    private int countToolsExcluding(List<Object> beans, Set<Object> alreadySelected) {
        int cost = 0;
        for (Object bean : beans) {
            if (!alreadySelected.contains(bean)) {
                cost += toolCounts.getOrDefault(bean, 0);
            }
        }
        return cost;
    }

    // ═══ Category definitions ═══

    private List<Object> buildCoreTools() {
        return List.of(
                directivesTools, directiveDataTools, memoryTools,
                chatHistoryTool, taskStatusTool, clipboardTools);
    }

    private Map<String, List<Object>> buildCategories() {
        Map<String, List<Object>> map = new LinkedHashMap<>();

        map.put("chat_browser", List.of(playwrightTools, downloadTools, sitesConfigTools));
        map.put("browser",      List.of(playwrightTools, downloadTools, sitesConfigTools, systemTools));
        map.put("sites",        List.of(sitesConfigTools));
        map.put("files",        List.of(fileTools, fileSystemTools, systemTools));
        map.put("system",       List.of(systemTools));
        map.put("media",        List.of(imageTools, pdfTools, ttsTools));
        map.put("ai_model",     List.of(localModelTools, huggingFaceImageTool, summarizationTools, modelSwitchTools));
        map.put("communication", List.of(emailTools, weatherTools));
        map.put("scheduling",   List.of(scheduledTaskTools, timerTools, notificationTools, cronConfigTools));
        map.put("utility",      List.of(calculatorTools, qrTools, hashTools, unitConversionTools));
        map.put("export",       List.of(exportTools));
        map.put("plugins",      List.of(pluginLoaderService));
        map.put("hotkeys",      List.of(globalHotkeyService));
        map.put("tray",         List.of(systemTrayService));
        map.put("screen_memory", List.of(screenMemoryTools));
        map.put("audio_memory", List.of(audioMemoryTools, playlistTools));
        map.put("playlist",     List.of(playlistTools));

        return Collections.unmodifiableMap(map);
    }

    private List<Object> buildDefaultTools() {
        return List.of(
                systemTools, fileTools,
                screenMemoryTools, audioMemoryTools,
                scheduledTaskTools, notificationTools,
                weatherTools);
    }

    // ─── Autonomous mode ───

    private List<Object> buildAutonomousCoreTools() {
        return List.of(
                directivesTools, directiveDataTools,
                chatHistoryTool, taskStatusTool, clipboardTools);
    }

    private Map<String, List<Object>> buildAutonomousCategories() {
        Map<String, List<Object>> map = new LinkedHashMap<>();

        map.put("browser",       List.of(playwrightTools));
        map.put("files",         List.of(fileTools, fileSystemTools));
        map.put("system",        List.of(systemTools));
        map.put("media",         List.of(imageTools));
        map.put("ai_model",      List.of(localModelTools, summarizationTools));
        map.put("communication", List.of(emailTools));
        map.put("scheduling",    List.of(scheduledTaskTools, cronConfigTools));
        map.put("export",        List.of(exportTools));

        return Collections.unmodifiableMap(map);
    }

    private List<Object> buildAutonomousDefaultTools() {
        return List.of(
                systemTools, fileTools, fileSystemTools,
                playwrightTools, imageTools, emailTools,
                scheduledTaskTools, summarizationTools,
                exportTools, localModelTools);
    }

    // ═══ Helpers ═══

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
