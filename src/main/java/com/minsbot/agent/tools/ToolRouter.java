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

    @Autowired(required = false)
    private com.minsbot.agent.plan.PlanTool planTool;

    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

    /** OpenAI's maximum tools per API call. */
    private static final int MAX_TOOLS = 128;
    /** Leave headroom for safety (internal Spring AI wrappers, etc.). */
    private static final int TOOL_BUDGET = MAX_TOOLS - 5; // 123

    // ─── All tool beans ───

    private final DirectivesTools directivesTools;
    private final DirectiveDataTools directiveDataTools;
    private final ChatHistoryTool chatHistoryTool;
    private final TaskStatusTool taskStatusTool;
    private final ClipboardTools clipboardTools;
    private final TodoListTools todoListTools;
    private final PersonalConfigTools personalConfigTools;
    private final MinsbotConfigTools minsbotConfigTools;

    private final PlaywrightTools playwrightTools;
    private final DownloadTools downloadTools;
    private final WebScraperTools webScraperTools;
    private final WebSearchTools webSearchTools;
    private final BrowserTools browserTools;

    private final FileTools fileTools;
    private final FileSystemTools fileSystemTools;
    private final ExcelTools excelTools;

    private final SystemTools systemTools;

    private final ImageTools imageTools;
    private final LocalImageTools localImageTools;
    private final CapabilitiesTool capabilitiesTool;
    private final MissionTools missionTools;
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
    private final ChromeCdpTools chromeCdpTools;
    private final SoftwareTools softwareTools;
    private final NetworkTools networkTools;
    private final PrinterTools printerTools;
    private final ScreenRecordTools screenRecordTools;
    private final ScreenWatchingTools screenWatchingTools;
    private final AudioListeningTools audioListeningTools;
    private final SensoryToggleTools sensoryToggleTools;
    private final TravelSearchTools travelSearchTools;
    private final WordDocTools wordDocTools;
    private final GlobalHotkeyService globalHotkeyService;
    private final AppSwitchTools appSwitchTools;
    private final PluginLoaderService pluginLoaderService;
    private final SystemTrayService systemTrayService;
    private final ScreenClickTools screenClickTools;
    private final KnowledgeBaseTools knowledgeBaseTools;
    private final CalendarTools calendarTools;
    private final GmailApiTools gmailApiTools;
    private final DriveTools driveTools;
    private final CodeAuditTools codeAuditTools;
    private final BackupConfigTools backupConfigTools;
    private final MusicControlTools musicControlTools;
    private final HealthMonitorTools healthMonitorTools;
    private final VideoDownloadTools videoDownloadTools;
    private final ClipboardHistoryTools clipboardHistoryTools;
    private final WakeWordTools wakeWordTools;
    private final AutoPilotTools autoPilotTools;
    private final ProactiveActionTools proactiveActionTools;
    private final GiftIdeaTools giftIdeaTools;
    private final LifeProfileTools lifeProfileTools;
    private final EpisodicMemoryTools episodicMemoryTools;
    private final ProactiveEngineTools proactiveEngineTools;
    private final HealthTrackerTools healthTrackerTools;
    private final FinanceTrackerTools financeTrackerTools;
    private final HabitDetectionTools habitDetectionTools;
    private final FeedbackLoopTools feedbackLoopTools;
    private final SkillAutoCreateTools skillAutoCreateTools;
    private final WindowManagerTools windowManagerTools;
    private final SocialMonitorTools socialMonitorTools;
    private final IntelligenceTools intelligenceTools;
    private final RemotionVideoTools remotionVideoTools;
    private final TrendScoutTools trendScoutTools;
    private final GitHubTools gitHubTools;
    private final BotWindowTools botWindowTools;
    private final CodeRunnerTools codeRunnerTools;
    private final FileWatcherTools fileWatcherTools;
    private final AppUsageTrackerTools appUsageTrackerTools;
    private final CustomSkillTools customSkillTools;
    private final BargeInTools bargeInTools;
    private final RestartTools restartTools;
    private final OrchestratorTools orchestratorTools;
    private final YouTubeTools youTubeTools;
    private final RemindersTools remindersTools;
    private final com.minsbot.skills.watcher.WatcherTools watcherTools;

    private final SkillDevTools skillDevTools;
    private final SkillProductivityTools skillProductivityTools;
    private final SkillSeoMarketingTools skillSeoMarketingTools;
    private final SkillSecurityTools skillSecurityTools;
    private final SkillProfessionTools skillProfessionTools;
    private final SkillDataToolsExtra skillDataToolsExtra;
    private final SkillCalcTools skillCalcTools;
    private final SkillExtrasTools skillExtrasTools;
    private final com.minsbot.integration.IntegrationCallTools integrationCallTools;
    private final MapsTools mapsTools;
    private final com.minsbot.skills.upcoming.UpcomingTools upcomingTools;
    private final com.minsbot.skills.recurringtask.RecurringTaskTools recurringTaskTools;
    private final WindowsSettingsTools windowsSettingsTools;
    private final com.minsbot.skills.journal.JournalService journalService;
    private final com.minsbot.skills.screenwatcher.ScreenRegionWatcherService screenRegionWatcher;
    private final com.minsbot.skills.filesearch.SemanticFileSearchService semanticFileSearch;
    private final com.minsbot.skills.meetingmode.MeetingModeService meetingMode;
    private final HeyGenTools heyGenTools;
    private final VeoVideoTools veoVideoTools;
    private final PdfAdvancedTools pdfAdvancedTools;
    private final PdfPasswordCrackerTools pdfPasswordCrackerTools;
    private final WebToPdfTools webToPdfTools;
    private final YouTubeTranscriptTools youTubeTranscriptTools;
    private final com.minsbot.skillpack.SkillPackTool skillPackTool;
    private final ClaudeCodeTools claudeCodeTools;
    private final SpecialCodeGenerator specialCodeGenerator;
    private final LocalCodeGenerator localCodeGenerator;
    private final ContinueProjectTools continueProjectTools;
    private final DevServerTools devServerTools;
    private final LogControlTools logControlTools;
    private final ProjectFileTools projectFileTools;
    private final ProjectVerifyService projectVerifyService;
    private final ProjectTemplateTools projectTemplateTools;
    private final ProjectAutoLaunchService projectAutoLaunchService;
    private final ProjectManagementTools projectManagementTools;
    private final ProjectTestService projectTestService;
    @org.springframework.beans.factory.annotation.Autowired
    private ResearchTool researchTool;
    @org.springframework.beans.factory.annotation.Autowired
    private DailyBriefingTool dailyBriefingTool;
    @org.springframework.beans.factory.annotation.Autowired
    private QuickNotesTool quickNotesTool;
    @org.springframework.beans.factory.annotation.Autowired
    private DailyRecapTool dailyRecapTool;
    @org.springframework.beans.factory.annotation.Autowired
    private UnifiedFindTool unifiedFindTool;
    @org.springframework.beans.factory.annotation.Autowired
    private WhatNowTool whatNowTool;
    @org.springframework.beans.factory.annotation.Autowired
    private TodaysFocusTool todaysFocusTool;
    @org.springframework.beans.factory.annotation.Autowired
    private ArchiveUrlTool archiveUrlTool;

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
            ChatHistoryTool chatHistoryTool,
            TaskStatusTool taskStatusTool,
            ClipboardTools clipboardTools,
            TodoListTools todoListTools,
            PersonalConfigTools personalConfigTools,
            MinsbotConfigTools minsbotConfigTools,
            PlaywrightTools playwrightTools,
            DownloadTools downloadTools,
            WebScraperTools webScraperTools,
            WebSearchTools webSearchTools,
            BrowserTools browserTools,
            ChromeCdpTools chromeCdpTools,
            FileTools fileTools,
            FileSystemTools fileSystemTools,
            ExcelTools excelTools,
            SystemTools systemTools,
            ImageTools imageTools,
            LocalImageTools localImageTools,
            CapabilitiesTool capabilitiesTool,
            MissionTools missionTools,
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
            SoftwareTools softwareTools,
            NetworkTools networkTools,
            PrinterTools printerTools,
            ScreenRecordTools screenRecordTools,
            ScreenWatchingTools screenWatchingTools,
            AudioListeningTools audioListeningTools,
            SensoryToggleTools sensoryToggleTools,
            TravelSearchTools travelSearchTools,
            WordDocTools wordDocTools,
            GlobalHotkeyService globalHotkeyService,
            AppSwitchTools appSwitchTools,
            PluginLoaderService pluginLoaderService,
            SystemTrayService systemTrayService,
            ScreenClickTools screenClickTools,
            KnowledgeBaseTools knowledgeBaseTools,
            CalendarTools calendarTools,
            GmailApiTools gmailApiTools,
            DriveTools driveTools,
            CodeAuditTools codeAuditTools,
            BackupConfigTools backupConfigTools,
            MusicControlTools musicControlTools,
            HealthMonitorTools healthMonitorTools,
            VideoDownloadTools videoDownloadTools,
            ClipboardHistoryTools clipboardHistoryTools,
            WakeWordTools wakeWordTools,
            AutoPilotTools autoPilotTools,
            ProactiveActionTools proactiveActionTools,
            GiftIdeaTools giftIdeaTools,
            LifeProfileTools lifeProfileTools,
            EpisodicMemoryTools episodicMemoryTools,
            ProactiveEngineTools proactiveEngineTools,
            HealthTrackerTools healthTrackerTools,
            FinanceTrackerTools financeTrackerTools,
            HabitDetectionTools habitDetectionTools,
            FeedbackLoopTools feedbackLoopTools,
            SkillAutoCreateTools skillAutoCreateTools,
            WindowManagerTools windowManagerTools,
            SocialMonitorTools socialMonitorTools,
            IntelligenceTools intelligenceTools,
            RemotionVideoTools remotionVideoTools,
            TrendScoutTools trendScoutTools,
            GitHubTools gitHubTools,
            BotWindowTools botWindowTools,
            CodeRunnerTools codeRunnerTools,
            FileWatcherTools fileWatcherTools,
            AppUsageTrackerTools appUsageTrackerTools,
            CustomSkillTools customSkillTools,
            BargeInTools bargeInTools,
            RestartTools restartTools,
            OrchestratorTools orchestratorTools,
            YouTubeTools youTubeTools,
            RemindersTools remindersTools,
            com.minsbot.skills.watcher.WatcherTools watcherTools,
            SkillDevTools skillDevTools,
            SkillProductivityTools skillProductivityTools,
            SkillSeoMarketingTools skillSeoMarketingTools,
            SkillSecurityTools skillSecurityTools,
            SkillProfessionTools skillProfessionTools,
            SkillDataToolsExtra skillDataToolsExtra,
            SkillCalcTools skillCalcTools,
            SkillExtrasTools skillExtrasTools,
            com.minsbot.integration.IntegrationCallTools integrationCallTools,
            MapsTools mapsTools,
            com.minsbot.skills.upcoming.UpcomingTools upcomingTools,
            com.minsbot.skills.recurringtask.RecurringTaskTools recurringTaskTools,
            WindowsSettingsTools windowsSettingsTools,
            com.minsbot.skills.journal.JournalService journalService,
            com.minsbot.skills.screenwatcher.ScreenRegionWatcherService screenRegionWatcher,
            com.minsbot.skills.filesearch.SemanticFileSearchService semanticFileSearch,
            com.minsbot.skills.meetingmode.MeetingModeService meetingMode,
            HeyGenTools heyGenTools,
            VeoVideoTools veoVideoTools,
            PdfAdvancedTools pdfAdvancedTools,
            PdfPasswordCrackerTools pdfPasswordCrackerTools,
            WebToPdfTools webToPdfTools,
            YouTubeTranscriptTools youTubeTranscriptTools,
            com.minsbot.skillpack.SkillPackTool skillPackTool,
            ClaudeCodeTools claudeCodeTools,
            SpecialCodeGenerator specialCodeGenerator,
            LocalCodeGenerator localCodeGenerator,
            ContinueProjectTools continueProjectTools,
            DevServerTools devServerTools,
            LogControlTools logControlTools,
            ProjectFileTools projectFileTools,
            ProjectVerifyService projectVerifyService,
            ProjectTemplateTools projectTemplateTools,
            ProjectAutoLaunchService projectAutoLaunchService,
            ProjectManagementTools projectManagementTools,
            ProjectTestService projectTestService) {

        this.directivesTools = directivesTools;
        this.directiveDataTools = directiveDataTools;
        this.chatHistoryTool = chatHistoryTool;
        this.taskStatusTool = taskStatusTool;
        this.clipboardTools = clipboardTools;
        this.todoListTools = todoListTools;
        this.personalConfigTools = personalConfigTools;
        this.minsbotConfigTools = minsbotConfigTools;
        this.playwrightTools = playwrightTools;
        this.downloadTools = downloadTools;
        this.webScraperTools = webScraperTools;
        this.webSearchTools = webSearchTools;
        this.browserTools = browserTools;
        this.chromeCdpTools = chromeCdpTools;
        this.fileTools = fileTools;
        this.fileSystemTools = fileSystemTools;
        this.excelTools = excelTools;
        this.systemTools = systemTools;
        this.imageTools = imageTools;
        this.localImageTools = localImageTools;
        this.capabilitiesTool = capabilitiesTool;
        this.missionTools = missionTools;
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
        this.softwareTools = softwareTools;
        this.networkTools = networkTools;
        this.printerTools = printerTools;
        this.screenRecordTools = screenRecordTools;
        this.screenWatchingTools = screenWatchingTools;
        this.audioListeningTools = audioListeningTools;
        this.sensoryToggleTools = sensoryToggleTools;
        this.travelSearchTools = travelSearchTools;
        this.wordDocTools = wordDocTools;
        this.globalHotkeyService = globalHotkeyService;
        this.appSwitchTools = appSwitchTools;
        this.pluginLoaderService = pluginLoaderService;
        this.systemTrayService = systemTrayService;
        this.screenClickTools = screenClickTools;
        this.knowledgeBaseTools = knowledgeBaseTools;
        this.calendarTools = calendarTools;
        this.gmailApiTools = gmailApiTools;
        this.driveTools = driveTools;
        this.codeAuditTools = codeAuditTools;
        this.backupConfigTools = backupConfigTools;
        this.musicControlTools = musicControlTools;
        this.healthMonitorTools = healthMonitorTools;
        this.videoDownloadTools = videoDownloadTools;
        this.clipboardHistoryTools = clipboardHistoryTools;
        this.wakeWordTools = wakeWordTools;
        this.autoPilotTools = autoPilotTools;
        this.proactiveActionTools = proactiveActionTools;
        this.giftIdeaTools = giftIdeaTools;
        this.lifeProfileTools = lifeProfileTools;
        this.episodicMemoryTools = episodicMemoryTools;
        this.proactiveEngineTools = proactiveEngineTools;
        this.healthTrackerTools = healthTrackerTools;
        this.financeTrackerTools = financeTrackerTools;
        this.habitDetectionTools = habitDetectionTools;
        this.feedbackLoopTools = feedbackLoopTools;
        this.skillAutoCreateTools = skillAutoCreateTools;
        this.windowManagerTools = windowManagerTools;
        this.socialMonitorTools = socialMonitorTools;
        this.intelligenceTools = intelligenceTools;
        this.remotionVideoTools = remotionVideoTools;
        this.trendScoutTools = trendScoutTools;
        this.gitHubTools = gitHubTools;
        this.botWindowTools = botWindowTools;
        this.codeRunnerTools = codeRunnerTools;
        this.fileWatcherTools = fileWatcherTools;
        this.appUsageTrackerTools = appUsageTrackerTools;
        this.customSkillTools = customSkillTools;
        this.bargeInTools = bargeInTools;
        this.restartTools = restartTools;
        this.orchestratorTools = orchestratorTools;
        this.youTubeTools = youTubeTools;
        this.remindersTools = remindersTools;
        this.watcherTools = watcherTools;
        this.skillDevTools = skillDevTools;
        this.skillProductivityTools = skillProductivityTools;
        this.skillSeoMarketingTools = skillSeoMarketingTools;
        this.skillSecurityTools = skillSecurityTools;
        this.skillProfessionTools = skillProfessionTools;
        this.skillDataToolsExtra = skillDataToolsExtra;
        this.skillCalcTools = skillCalcTools;
        this.skillExtrasTools = skillExtrasTools;
        this.integrationCallTools = integrationCallTools;
        this.mapsTools = mapsTools;
        this.upcomingTools = upcomingTools;
        this.recurringTaskTools = recurringTaskTools;
        this.windowsSettingsTools = windowsSettingsTools;
        this.journalService = journalService;
        this.screenRegionWatcher = screenRegionWatcher;
        this.semanticFileSearch = semanticFileSearch;
        this.meetingMode = meetingMode;
        this.heyGenTools = heyGenTools;
        this.veoVideoTools = veoVideoTools;
        this.pdfAdvancedTools = pdfAdvancedTools;
        this.pdfPasswordCrackerTools = pdfPasswordCrackerTools;
        this.webToPdfTools = webToPdfTools;
        this.youTubeTranscriptTools = youTubeTranscriptTools;
        this.skillPackTool = skillPackTool;
        this.claudeCodeTools = claudeCodeTools;
        this.specialCodeGenerator = specialCodeGenerator;
        this.localCodeGenerator = localCodeGenerator;
        this.continueProjectTools = continueProjectTools;
        this.devServerTools = devServerTools;
        this.logControlTools = logControlTools;
        this.projectFileTools = projectFileTools;
        this.projectVerifyService = projectVerifyService;
        this.projectTemplateTools = projectTemplateTools;
        this.projectAutoLaunchService = projectAutoLaunchService;
        this.projectManagementTools = projectManagementTools;
        this.projectTestService = projectTestService;

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

    /**
     * Fixed tool set for parallel background agents (Agents tab): web search, files, HTTP/HTML fetch,
     * and headless Playwright (separate browser contexts — not the user's Chrome/CDP).
     * Excludes screen control, CDP, shell, email, etc.
     */
    public Object[] selectToolsForBackgroundAgent() {
        Set<Object> beans = new LinkedHashSet<>();
        beans.add(webSearchTools);
        beans.add(webScraperTools);
        beans.add(fileTools);
        beans.add(fileSystemTools);
        beans.add(excelTools);
        beans.add(wordDocTools);
        beans.add(pdfTools);
        beans.add(playwrightTools);
        beans.add(downloadTools);
        beans.add(codeRunnerTools);
        // Orchestrators need to spawn & monitor sub-agents.
        beans.add(orchestratorTools);
        return beans.toArray();
    }

    public Object[] selectToolsForAutonomous(String message) {
        return doSelect(message, autonomousCoreTools, autonomousCategories, autonomousDefaultTools);
    }

    public boolean hasSpecificMatch(String message) {
        if (classifier == null || !classifier.isAvailable()) return false;
        List<String> matched = classifier.classify(message);
        return matched != null && !matched.isEmpty();
    }

    /**
     * Returns a map of category name → list of tool bean short class names, for prompt
     * enumeration. Used by SystemPromptService to inject a built-in skills listing into
     * the system prompt.
     */
    public Map<String, List<String>> getCategoryNamesByToolGroup() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        categories.forEach((cat, beans) -> {
            List<String> names = new ArrayList<>();
            for (Object bean : beans) {
                Class<?> cls = AopUtils.isAopProxy(bean)
                        ? AopUtils.getTargetClass(bean) : bean.getClass();
                names.add(cls.getSimpleName());
            }
            out.put(cat, names);
        });
        return Collections.unmodifiableMap(out);
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
                directivesTools, directiveDataTools,
                chatHistoryTool, taskStatusTool, clipboardTools, todoListTools, personalConfigTools, minsbotConfigTools,
                playwrightTools, downloadTools, webScraperTools, webSearchTools, browserTools, chromeCdpTools,
                fileTools, fileSystemTools, excelTools, systemTools,
                imageTools, pdfTools, ttsTools,
                localModelTools, huggingFaceImageTool, summarizationTools, modelSwitchTools,
                emailTools, weatherTools,
                scheduledTaskTools, timerTools, notificationTools,
                calculatorTools, qrTools, hashTools, unitConversionTools,
                exportTools, sitesConfigTools, cronConfigTools,
                screenMemoryTools, audioMemoryTools, playlistTools,
                softwareTools, networkTools, printerTools, screenRecordTools, screenWatchingTools, audioListeningTools,
                sensoryToggleTools,
                travelSearchTools, wordDocTools, appSwitchTools, globalHotkeyService, pluginLoaderService, systemTrayService,
                codeAuditTools, backupConfigTools, musicControlTools,
                videoDownloadTools, clipboardHistoryTools, wakeWordTools, autoPilotTools, proactiveActionTools, giftIdeaTools,
                lifeProfileTools, episodicMemoryTools, proactiveEngineTools,
                healthTrackerTools, financeTrackerTools,
                habitDetectionTools, feedbackLoopTools, skillAutoCreateTools, windowManagerTools,
                socialMonitorTools, intelligenceTools,
                remotionVideoTools, trendScoutTools,
                gitHubTools, botWindowTools,
                codeRunnerTools, fileWatcherTools, appUsageTrackerTools,
                customSkillTools, bargeInTools, restartTools,
                orchestratorTools, youTubeTools, mapsTools, upcomingTools, recurringTaskTools,
                remindersTools,
                watcherTools,
                driveTools,
                windowsSettingsTools,
                journalService, screenRegionWatcher, semanticFileSearch, meetingMode,
                heyGenTools, veoVideoTools,
                pdfAdvancedTools, pdfPasswordCrackerTools, webToPdfTools, youTubeTranscriptTools,
                skillPackTool
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
        List<Object> core = new ArrayList<>(List.of(
                directivesTools, directiveDataTools,
                chatHistoryTool, taskStatusTool, clipboardTools, todoListTools,
                unifiedFindTool,
                personalConfigTools, lifeProfileTools, minsbotConfigTools, webSearchTools, sensoryToggleTools,
                // Skill-pack menu: always on so the LLM can discover SKILL.md packs on any turn.
                skillPackTool));
        // Plan tool: lets the model author/update its own multi-step plan. Optional
        // bean (field-injected) so ToolRouter doesn't require it at construction.
        if (planTool != null) core.add(planTool);
        return core;
    }

    private Map<String, List<Object>> buildCategories() {
        Map<String, List<Object>> map = new LinkedHashMap<>();

        map.put("chat_browser", List.of(playwrightTools, downloadTools, sitesConfigTools, webSearchTools));
        map.put("browser",      List.of(screenClickTools, playwrightTools, downloadTools, sitesConfigTools, systemTools, chromeCdpTools, webSearchTools, travelSearchTools, researchTool, archiveUrlTool));
        map.put("cdp",          List.of(chromeCdpTools));
        map.put("sites",        List.of(sitesConfigTools));
        map.put("files",        List.of(fileTools, fileSystemTools, excelTools, systemTools, wordDocTools, pdfTools, backupConfigTools));
        map.put("excel",        List.of(excelTools));
        map.put("system",       List.of(screenClickTools, systemTools, softwareTools, screenRecordTools, appSwitchTools, musicControlTools));
        map.put("software",    List.of(softwareTools));
        map.put("network",     List.of(networkTools));
        map.put("printing",    List.of(printerTools));
        map.put("media",       List.of(imageTools, localImageTools, pdfTools, ttsTools));
        map.put("ai_model",     List.of(localModelTools, huggingFaceImageTool, summarizationTools, modelSwitchTools));
        map.put("communication", List.of(emailTools, weatherTools, gmailApiTools, calendarTools));
        map.put("scheduling",   List.of(scheduledTaskTools, timerTools, notificationTools, cronConfigTools));
        map.put("utility",      List.of(calculatorTools, qrTools, hashTools, unitConversionTools));
        map.put("export",       List.of(exportTools));
        map.put("plugins",      List.of(pluginLoaderService));
        map.put("hotkeys",      List.of(globalHotkeyService));
        map.put("tray",         List.of(systemTrayService));
        map.put("screen_memory", List.of(screenMemoryTools));
        map.put("audio_memory", List.of(audioMemoryTools, playlistTools));
        map.put("playlist",     List.of(playlistTools));
        map.put("screen_watching", List.of(screenWatchingTools, audioListeningTools, sensoryToggleTools));
        map.put("travel",        List.of(travelSearchTools, webSearchTools, wordDocTools, pdfTools));
        map.put("knowledge",     List.of(knowledgeBaseTools));
        map.put("research",      List.of(webSearchTools, webScraperTools, excelTools, pdfTools, fileTools, fileSystemTools, ttsTools, summarizationTools));
        map.put("briefing",      List.of(gmailApiTools, calendarTools, emailTools, weatherTools, ttsTools, summarizationTools, dailyBriefingTool, dailyRecapTool, whatNowTool, todaysFocusTool));
        map.put("calendar",      List.of(calendarTools));
        map.put("gmail",         List.of(gmailApiTools, emailTools));
        map.put("drive",         List.of(driveTools));
        map.put("code_audit",    List.of(codeAuditTools, fileSystemTools));
        map.put("backup",        List.of(backupConfigTools, fileSystemTools));
        map.put("music",         List.of(musicControlTools, playlistTools));
        map.put("health_monitor", List.of(healthMonitorTools));
        map.put("video_download", List.of(videoDownloadTools, downloadTools));
        map.put("clipboard_history", List.of(clipboardHistoryTools, clipboardTools));
        map.put("wake_word",      List.of(wakeWordTools));
        map.put("autopilot",      List.of(autoPilotTools, proactiveActionTools));
        map.put("proactive_action", List.of(proactiveActionTools));
        map.put("gift_ideas",     List.of(giftIdeaTools, webSearchTools));
        map.put("episodic_memory", List.of(episodicMemoryTools));
        map.put("proactive",      List.of(proactiveEngineTools));
        map.put("health_tracker", List.of(healthTrackerTools));
        map.put("finance_tracker", List.of(financeTrackerTools));
        map.put("habits",         List.of(habitDetectionTools));
        map.put("feedback",       List.of(feedbackLoopTools));
        map.put("auto_skills",    List.of(skillAutoCreateTools));
        map.put("window_manager", List.of(windowManagerTools, systemTools));
        map.put("social_monitor", List.of(socialMonitorTools, webSearchTools));
        map.put("intelligence",   List.of(intelligenceTools, calendarTools, gmailApiTools, weatherTools, financeTrackerTools, healthTrackerTools, ttsTools, travelSearchTools));
        map.put("video_creation", List.of(remotionVideoTools));
        map.put("trend_scout",   List.of(trendScoutTools, webSearchTools));
        map.put("github",        List.of(gitHubTools));
        map.put("code_gen",      List.of(claudeCodeTools, specialCodeGenerator, localCodeGenerator, continueProjectTools, devServerTools, projectFileTools, projectVerifyService, projectTemplateTools, projectAutoLaunchService, projectManagementTools, projectTestService));
        map.put("bot_window",    List.of(botWindowTools));
        map.put("code_runner",   List.of(codeRunnerTools));
        map.put("file_watcher",  List.of(fileWatcherTools, fileTools));
        map.put("app_usage",     List.of(appUsageTrackerTools, habitDetectionTools));
        map.put("youtube",       List.of(youTubeTools, webSearchTools));
        map.put("custom_skills", List.of(customSkillTools));
        map.put("barge_in",      List.of(bargeInTools));
        map.put("restart",       List.of(restartTools));
        map.put("orchestrator",  List.of(orchestratorTools));
        map.put("reminders",     List.of(remindersTools, quickNotesTool));
        map.put("watcher",       List.of(watcherTools, emailTools));

        // ─── Skill packs (wrappers around self-contained skills under com.minsbot.skills.*) ───
        map.put("dev_skills",         List.of(skillDevTools));
        map.put("productivity_skills", List.of(skillProductivityTools));
        map.put("seo_marketing_skills", List.of(skillSeoMarketingTools));
        map.put("security_skills",    List.of(skillSecurityTools));
        map.put("profession_skills",  List.of(skillProfessionTools));
        map.put("data_skills_extra",  List.of(skillDataToolsExtra));
        map.put("calc_skills",        List.of(skillCalcTools));
        map.put("extras_skills",      List.of(skillExtrasTools));
        map.put("integrations",       List.of(integrationCallTools));
        map.put("maps",               List.of(mapsTools));
        map.put("upcoming",           List.of(upcomingTools));
        map.put("recurring_task",     List.of(recurringTaskTools, scheduledTaskTools));
        map.put("windows_settings",   List.of(windowsSettingsTools, musicControlTools));
        map.put("journal",            List.of(journalService, episodicMemoryTools));
        map.put("screen_watcher",     List.of(screenRegionWatcher));
        map.put("file_search",        List.of(semanticFileSearch, fileSystemTools));
        map.put("meeting_mode",       List.of(meetingMode, audioListeningTools));
        map.put("heygen",             List.of(heyGenTools));
        map.put("veo",                List.of(veoVideoTools));
        map.put("pdf_advanced",       List.of(pdfAdvancedTools, pdfTools));
        map.put("pdf_password",       List.of(pdfPasswordCrackerTools, pdfAdvancedTools));
        map.put("web_to_pdf",         List.of(webToPdfTools, playwrightTools));
        map.put("youtube_transcript", List.of(youTubeTranscriptTools, webScraperTools));

        // SKILL.md-based external packs. Pulled in together with a shell runner + file
        // tools so a skill's "run X" instruction actually has the means to execute in
        // the same turn (skill body without shell = LLM reads docs and does nothing).
        map.put("skill_packs", List.of(skillPackTool, codeRunnerTools, systemTools, fileTools, fileSystemTools));

        return Collections.unmodifiableMap(map);
    }

    private List<Object> buildDefaultTools() {
        return List.of(
                systemTools, fileTools, chromeCdpTools,
                screenClickTools, screenMemoryTools, audioMemoryTools,
                scheduledTaskTools, notificationTools,
                weatherTools,
                // Always in scope: the local image generator should fire on "generate an image"
                // regardless of whether the classifier routed to the media category this turn.
                localImageTools,
                // Self-description needs to be reliably callable for "what can you do?" etc.
                capabilitiesTool,
                // Mission orchestration — LLM can start/stop long-running jobs from chat.
                missionTools,
                // Always in scope: episodic recall. Users ask personal questions ("my son's
                // name?", "what do I like?") without using memory-flavored keywords, so the
                // classifier can't reliably route here. Keep it available by default.
                episodicMemoryTools,
                // Always in scope: runtime log-level control ("quiet recurring tasks",
                // "mute watcher", "reset all logs"). Small (~5 tools), useful anywhere.
                logControlTools);
    }

    // ─── Autonomous mode ───

    private List<Object> buildAutonomousCoreTools() {
        return List.of(
                directivesTools, directiveDataTools,
                chatHistoryTool, taskStatusTool, clipboardTools, todoListTools,
                personalConfigTools, lifeProfileTools, webSearchTools, sensoryToggleTools);
    }

    private Map<String, List<Object>> buildAutonomousCategories() {
        Map<String, List<Object>> map = new LinkedHashMap<>();

        map.put("browser",       List.of(playwrightTools, chromeCdpTools, webSearchTools, travelSearchTools));
        map.put("cdp",           List.of(chromeCdpTools));
        map.put("files",         List.of(fileTools, fileSystemTools, excelTools, wordDocTools, pdfTools));
        map.put("excel",         List.of(excelTools));
        map.put("system",        List.of(systemTools, softwareTools, musicControlTools));
        map.put("software",     List.of(softwareTools));
        map.put("network",      List.of(networkTools));
        map.put("printing",     List.of(printerTools));
        map.put("media",        List.of(imageTools, localImageTools, pdfTools));
        map.put("ai_model",     List.of(localModelTools, summarizationTools));
        map.put("communication", List.of(emailTools, gmailApiTools, calendarTools));
        map.put("scheduling",   List.of(scheduledTaskTools, cronConfigTools));
        map.put("export",       List.of(exportTools));
        map.put("travel",       List.of(travelSearchTools, webSearchTools, wordDocTools, pdfTools));
        map.put("briefing",     List.of(gmailApiTools, calendarTools, emailTools, weatherTools, ttsTools, summarizationTools));
        map.put("calendar",     List.of(calendarTools));
        map.put("gmail",        List.of(gmailApiTools, emailTools));
        map.put("drive",        List.of(driveTools));
        map.put("watcher",      List.of(watcherTools, emailTools));
        map.put("health_monitor", List.of(healthMonitorTools));
        map.put("health_tracker", List.of(healthTrackerTools));
        map.put("finance_tracker", List.of(financeTrackerTools));

        return Collections.unmodifiableMap(map);
    }

    private List<Object> buildAutonomousDefaultTools() {
        return List.of(
                systemTools, fileTools, fileSystemTools,
                screenClickTools, playwrightTools, chromeCdpTools, imageTools, emailTools,
                scheduledTaskTools, summarizationTools,
                exportTools, localModelTools, softwareTools);
    }

    // ═══ Helpers ═══

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
