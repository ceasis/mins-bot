package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Proactive Engine — periodically checks the user's life context and pushes
 * helpful notifications (morning briefings, break/hydration reminders, bill
 * alerts, relationship nudges, goal check-ins, weather alerts, meeting prep).
 *
 * <p>Runs on a configurable interval (default 5 min). Respects quiet hours.
 * Custom rules are persisted to ~/mins_bot_data/proactive_rules.txt.
 */
@Service
public class ProactiveEngineService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveEngineService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path RULES_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "proactive_rules.txt");
    private static final Path LIFE_PROFILE_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "life_profile.txt");
    private static final Path PERSONAL_CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "personal_config.txt");

    // ─── Configuration ──────────────────────────────────────────────────────────

    @Value("${app.proactive.enabled:false}")
    private volatile boolean enabled;

    @Value("${app.proactive.break-reminder-minutes:120}")
    private int breakReminderMinutes;

    @Value("${app.proactive.hydration-reminder-minutes:120}")
    private int hydrationReminderMinutes;

    @Value("${app.proactive.morning-briefing-hour:8}")
    private int morningBriefingHour;

    @Value("${app.proactive.quiet-hours-start:22}")
    private volatile int quietHoursStart;

    @Value("${app.proactive.quiet-hours-end:7}")
    private volatile int quietHoursEnd;

    // ─── Dependencies ───────────────────────────────────────────────────────────

    private final AsyncMessageService asyncMessages;

    @Autowired(required = false)
    private ChatClient chatClient;

    // ─── State ──────────────────────────────────────────────────────────────────

    /** Tracks when each notification type was last sent to avoid spam. */
    private final ConcurrentHashMap<String, LocalDateTime> lastNotificationTimes = new ConcurrentHashMap<>();

    /** Custom user-defined proactive rules. */
    private final CopyOnWriteArrayList<ProactiveRule> customRules = new CopyOnWriteArrayList<>();

    private volatile LocalDateTime lastCheckTime;
    private volatile int totalCheckCount;
    private volatile int totalNotificationsSent;

    /** Lazy-injected — avoids circular dependency. */
    @Autowired(required = false)
    private com.minsbot.agent.tools.IntelligenceTools intelligenceTools;

    @Autowired(required = false)
    private com.minsbot.agent.tools.TtsTools ttsTools;

    /** Weather location for morning briefing (read from personal config). */
    @Value("${app.proactive.weather-location:Manila}")
    private String weatherLocation;

    public ProactiveEngineService(AsyncMessageService asyncMessages) {
        this.asyncMessages = asyncMessages;
        loadCustomRules();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Scheduled check
    // ═══════════════════════════════════════════════════════════════════════════

    @Scheduled(fixedDelayString = "${app.proactive.check-interval-ms:300000}")
    public void runProactiveCheck() {
        if (!enabled) return;

        LocalDateTime now = LocalDateTime.now();
        lastCheckTime = now;
        totalCheckCount++;

        if (isDuringQuietHours(now)) {
            log.debug("[ProactiveEngine] Quiet hours — skipping check");
            return;
        }

        log.debug("[ProactiveEngine] Running proactive check #{}", totalCheckCount);

        try {
            checkMorningBriefing(now);
            checkBreakReminder(now);
            checkHydrationReminder(now);
            checkMeetingPrep(now);
            checkBillReminders(now);
            checkRelationshipNudges(now);
            checkGoalCheckIns(now);
            checkWeatherAlert(now);
            checkCustomRules(now);
        } catch (Exception e) {
            log.warn("[ProactiveEngine] Error during proactive check: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Built-in proactive checks
    // ═══════════════════════════════════════════════════════════════════════════

    private void checkMorningBriefing(LocalDateTime now) {
        if (now.getHour() != morningBriefingHour) return;
        if (wasRecentlySent("morning-briefing", 720)) return; // once per 12 hours

        // Use the full IntelligenceTools briefing if available (weather + calendar + email + tasks + health + bills)
        if (intelligenceTools != null) {
            try {
                String fullBriefing = intelligenceTools.generateDailyBriefing(weatherLocation);
                if (fullBriefing != null && !fullBriefing.isBlank()) {
                    pushNotification("morning-briefing", fullBriefing);
                    return;
                }
            } catch (Exception e) {
                log.warn("[ProactiveEngine] Full briefing failed, falling back to AI: {}", e.getMessage());
            }
        }

        // Fallback: generic AI briefing
        String context = loadContext();
        String briefing = generateAiContent(
                "Generate a concise morning briefing for the user. Include a greeting, " +
                "mention today's date (" + LocalDate.now() + ", " + now.getDayOfWeek() + "), " +
                "and any relevant items from their life context. Keep it warm and brief (3-5 sentences).\n\n" +
                "User context:\n" + context);

        if (briefing != null) {
            pushNotification("morning-briefing", briefing);
        }
    }

    private void checkBreakReminder(LocalDateTime now) {
        if (wasRecentlySent("break-reminder", breakReminderMinutes)) return;

        // Only send during working hours (9am-9pm)
        int hour = now.getHour();
        if (hour < 9 || hour > 21) return;

        String[] messages = {
                "You've been at your PC for a while. Time for a quick stretch break!",
                "Hey, take a moment to stand up and stretch. Your body will thank you.",
                "Quick reminder: step away from the screen for a minute. Rest your eyes.",
                "Break time! Stand up, stretch, look out a window for a minute.",
                "Your posture check is here. Sit up straight and take a few deep breaths."
        };
        String msg = messages[new Random().nextInt(messages.length)];
        pushNotification("break-reminder", msg);
    }

    private void checkHydrationReminder(LocalDateTime now) {
        if (wasRecentlySent("hydration-reminder", hydrationReminderMinutes)) return;

        int hour = now.getHour();
        if (hour < 7 || hour > 22) return;

        String[] messages = {
                "Stay hydrated! Time for a glass of water.",
                "Water break! Have you had enough water today?",
                "Gentle reminder: drink some water. Hydration helps focus.",
                "Hey, grab some water. Your brain works better when hydrated.",
                "Hydration check! Pour yourself a glass of water."
        };
        String msg = messages[new Random().nextInt(messages.length)];
        pushNotification("hydration-reminder", msg);
    }

    private void checkMeetingPrep(LocalDateTime now) {
        if (wasRecentlySent("meeting-prep", 30)) return; // at most every 30 min

        // Read life profile for calendar/meeting info
        String lifeProfile = readFileIfExists(LIFE_PROFILE_PATH);
        if (lifeProfile == null || lifeProfile.isBlank()) return;

        // Look for meeting/calendar entries that might be coming up
        String lowerProfile = lifeProfile.toLowerCase();
        if (!lowerProfile.contains("meeting") && !lowerProfile.contains("calendar")
                && !lowerProfile.contains("appointment") && !lowerProfile.contains("call")) {
            return;
        }

        String prep = generateAiContent(
                "Check if the user has any meetings or appointments coming up in the next 15-30 minutes " +
                "based on their life profile. If yes, generate a brief meeting prep notification. " +
                "If no upcoming meetings, respond with exactly 'NONE'.\n\n" +
                "Current time: " + now.format(FMT) + "\n" +
                "Life profile:\n" + lifeProfile);

        if (prep != null && !prep.strip().equalsIgnoreCase("NONE")) {
            pushNotification("meeting-prep", prep);
        }
    }

    private void checkBillReminders(LocalDateTime now) {
        if (wasRecentlySent("bill-reminder", 720)) return; // at most twice a day

        // Only check in the morning
        if (now.getHour() < 7 || now.getHour() > 10) return;

        String lifeProfile = readFileIfExists(LIFE_PROFILE_PATH);
        if (lifeProfile == null || lifeProfile.isBlank()) return;

        String lowerProfile = lifeProfile.toLowerCase();
        if (!lowerProfile.contains("bill") && !lowerProfile.contains("due")
                && !lowerProfile.contains("payment") && !lowerProfile.contains("rent")) {
            return;
        }

        String reminder = generateAiContent(
                "Check if the user has any bills due within the next 3 days based on their life profile. " +
                "If yes, generate a brief reminder. If no bills are due soon, respond with exactly 'NONE'.\n\n" +
                "Today's date: " + LocalDate.now() + "\n" +
                "Life profile:\n" + lifeProfile);

        if (reminder != null && !reminder.strip().equalsIgnoreCase("NONE")) {
            pushNotification("bill-reminder", reminder);
        }
    }

    private void checkRelationshipNudges(LocalDateTime now) {
        if (wasRecentlySent("relationship-nudge", 1440)) return; // once per day max

        // Only in the evening (relaxed time)
        if (now.getHour() < 17 || now.getHour() > 20) return;

        String lifeProfile = readFileIfExists(LIFE_PROFILE_PATH);
        String personalConfig = readFileIfExists(PERSONAL_CONFIG_PATH);
        String combined = (lifeProfile != null ? lifeProfile : "") + "\n" +
                           (personalConfig != null ? personalConfig : "");

        if (combined.isBlank()) return;

        String lowerCombined = combined.toLowerCase();
        if (!lowerCombined.contains("friend") && !lowerCombined.contains("family")
                && !lowerCombined.contains("parent") && !lowerCombined.contains("partner")
                && !lowerCombined.contains("contact") && !lowerCombined.contains("relationship")) {
            return;
        }

        String nudge = generateAiContent(
                "Based on the user's profile, suggest one person they might want to reach out to " +
                "(family member, friend, etc.). Make it warm and gentle, not pushy. " +
                "If there's not enough info to make a suggestion, respond with exactly 'NONE'.\n\n" +
                "User context:\n" + combined);

        if (nudge != null && !nudge.strip().equalsIgnoreCase("NONE")) {
            pushNotification("relationship-nudge", nudge);
        }
    }

    private void checkGoalCheckIns(LocalDateTime now) {
        if (wasRecentlySent("goal-checkin", 10080)) return; // once per week (7 * 1440)

        // Only on Sundays or Mondays in the morning
        var dow = now.getDayOfWeek();
        if (dow != java.time.DayOfWeek.SUNDAY && dow != java.time.DayOfWeek.MONDAY) return;
        if (now.getHour() < 9 || now.getHour() > 11) return;

        String lifeProfile = readFileIfExists(LIFE_PROFILE_PATH);
        if (lifeProfile == null || lifeProfile.isBlank()) return;

        String lowerProfile = lifeProfile.toLowerCase();
        if (!lowerProfile.contains("goal") && !lowerProfile.contains("target")
                && !lowerProfile.contains("objective") && !lowerProfile.contains("plan")) {
            return;
        }

        String checkIn = generateAiContent(
                "The user has goals/targets in their life profile. Generate a brief, encouraging " +
                "weekly check-in message asking about their progress. Keep it motivational (2-3 sentences). " +
                "If there are no clear goals, respond with exactly 'NONE'.\n\n" +
                "Life profile:\n" + lifeProfile);

        if (checkIn != null && !checkIn.strip().equalsIgnoreCase("NONE")) {
            pushNotification("goal-checkin", checkIn);
        }
    }

    private void checkWeatherAlert(LocalDateTime now) {
        if (wasRecentlySent("weather-alert", 480)) return; // at most every 8 hours

        // Only check in the morning
        if (now.getHour() != morningBriefingHour && now.getHour() != morningBriefingHour + 1) return;

        String personalConfig = readFileIfExists(PERSONAL_CONFIG_PATH);
        String lifeProfile = readFileIfExists(LIFE_PROFILE_PATH);
        String combined = (personalConfig != null ? personalConfig : "") + "\n" +
                           (lifeProfile != null ? lifeProfile : "");

        // Try to find location info
        String lowerCombined = combined.toLowerCase();
        if (!lowerCombined.contains("location") && !lowerCombined.contains("city")
                && !lowerCombined.contains("live in") && !lowerCombined.contains("address")
                && !lowerCombined.contains("home")) {
            return;
        }

        String alert = generateAiContent(
                "Based on the user's profile, identify their location and generate a brief morning " +
                "weather note (e.g. 'Looks like rain today, bring an umbrella!'). " +
                "If you can't determine location, respond with exactly 'NONE'.\n\n" +
                "User context:\n" + combined + "\n" +
                "Date: " + LocalDate.now());

        if (alert != null && !alert.strip().equalsIgnoreCase("NONE")) {
            pushNotification("weather-alert", alert);
        }
    }

    private void checkCustomRules(LocalDateTime now) {
        for (ProactiveRule rule : customRules) {
            if (wasRecentlySent("custom-" + rule.id, rule.intervalMinutes)) continue;

            String content = generateAiContent(
                    "Execute this proactive check rule and generate a brief notification if appropriate. " +
                    "If nothing to notify about, respond with exactly 'NONE'.\n\n" +
                    "Rule: " + rule.description + "\n" +
                    "Current time: " + now.format(FMT));

            if (content != null && !content.strip().equalsIgnoreCase("NONE")) {
                pushNotification("custom-" + rule.id, content);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public API (used by ProactiveEngineTools)
    // ═══════════════════════════════════════════════════════════════════════════

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("[ProactiveEngine] {} proactive engine", enabled ? "Enabled" : "Disabled");
    }

    public int getQuietHoursStart() {
        return quietHoursStart;
    }

    public int getQuietHoursEnd() {
        return quietHoursEnd;
    }

    public void setQuietHours(int start, int end) {
        this.quietHoursStart = start;
        this.quietHoursEnd = end;
        log.info("[ProactiveEngine] Quiet hours set: {}:00 - {}:00", start, end);
    }

    public LocalDateTime getLastCheckTime() {
        return lastCheckTime;
    }

    public int getTotalCheckCount() {
        return totalCheckCount;
    }

    public int getTotalNotificationsSent() {
        return totalNotificationsSent;
    }

    public Map<String, LocalDateTime> getLastNotificationTimes() {
        return Collections.unmodifiableMap(lastNotificationTimes);
    }

    public String addCustomRule(String description, int intervalMinutes) {
        String id = "rule-" + System.currentTimeMillis();
        ProactiveRule rule = new ProactiveRule(id, description, intervalMinutes);
        customRules.add(rule);
        saveCustomRules();
        log.info("[ProactiveEngine] Added custom rule: {} (every {} min)", description, intervalMinutes);
        return id;
    }

    public boolean removeCustomRule(String ruleId) {
        boolean removed = customRules.removeIf(r -> r.id.equals(ruleId));
        if (removed) {
            lastNotificationTimes.remove("custom-" + ruleId);
            saveCustomRules();
            log.info("[ProactiveEngine] Removed custom rule: {}", ruleId);
        }
        return removed;
    }

    public List<ProactiveRule> getCustomRules() {
        return Collections.unmodifiableList(customRules);
    }

    /** Force an immediate proactive check (ignores the scheduled interval). */
    public void triggerImmediateCheck() {
        log.info("[ProactiveEngine] Triggering immediate proactive check");
        boolean wasEnabled = enabled;
        enabled = true;
        try {
            runProactiveCheck();
        } finally {
            enabled = wasEnabled;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isDuringQuietHours(LocalDateTime now) {
        int hour = now.getHour();
        if (quietHoursStart > quietHoursEnd) {
            // Wraps midnight, e.g. 22-7
            return hour >= quietHoursStart || hour < quietHoursEnd;
        } else {
            return hour >= quietHoursStart && hour < quietHoursEnd;
        }
    }

    private boolean wasRecentlySent(String type, int cooldownMinutes) {
        LocalDateTime last = lastNotificationTimes.get(type);
        if (last == null) return false;
        return ChronoUnit.MINUTES.between(last, LocalDateTime.now()) < cooldownMinutes;
    }

    private void pushNotification(String type, String message) {
        lastNotificationTimes.put(type, LocalDateTime.now());
        totalNotificationsSent++;
        asyncMessages.push(message);
        log.info("[ProactiveEngine] Sent [{}]: {}", type,
                message.substring(0, Math.min(80, message.length())));

        // Speak the notification aloud (skip morning briefing — it already speaks via IntelligenceTools)
        if (ttsTools != null && !"morning-briefing".equals(type)) {
            try {
                // Keep spoken version short
                String spoken = message.length() > 300 ? message.substring(0, 300) : message;
                ttsTools.speak(spoken);
            } catch (Exception e) {
                log.debug("[ProactiveEngine] TTS failed: {}", e.getMessage());
            }
        }
    }

    private String generateAiContent(String prompt) {
        if (chatClient == null) {
            log.debug("[ProactiveEngine] No ChatClient available — skipping AI generation");
            return null;
        }
        try {
            String content = chatClient.prompt()
                    .system("You are a helpful personal assistant generating proactive notifications. " +
                            "Keep messages concise (1-3 sentences), warm, and actionable. " +
                            "If the check doesn't warrant a notification, respond with exactly 'NONE'.")
                    .user(prompt)
                    .call()
                    .content();
            return (content != null && !content.isBlank()) ? content.strip() : null;
        } catch (Exception e) {
            log.warn("[ProactiveEngine] AI generation failed: {}", e.getMessage());
            return null;
        }
    }

    private String loadContext() {
        StringBuilder sb = new StringBuilder();
        String personalConfig = readFileIfExists(PERSONAL_CONFIG_PATH);
        if (personalConfig != null) sb.append("Personal config:\n").append(personalConfig).append("\n\n");
        String lifeProfile = readFileIfExists(LIFE_PROFILE_PATH);
        if (lifeProfile != null) sb.append("Life profile:\n").append(lifeProfile).append("\n\n");
        return sb.length() > 0 ? sb.toString() : "No personal context available.";
    }

    private String readFileIfExists(Path path) {
        try {
            if (Files.exists(path)) {
                String content = Files.readString(path, StandardCharsets.UTF_8).trim();
                return content.isEmpty() ? null : content;
            }
        } catch (IOException e) {
            log.debug("[ProactiveEngine] Could not read {}: {}", path, e.getMessage());
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Custom rules persistence
    // ═══════════════════════════════════════════════════════════════════════════

    private void loadCustomRules() {
        try {
            if (!Files.exists(RULES_PATH)) return;
            List<String> lines = Files.readAllLines(RULES_PATH, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // Format: id|intervalMinutes|description
                String[] parts = line.split("\\|", 3);
                if (parts.length == 3) {
                    try {
                        String id = parts[0].trim();
                        int interval = Integer.parseInt(parts[1].trim());
                        String desc = parts[2].trim();
                        customRules.add(new ProactiveRule(id, desc, interval));
                    } catch (NumberFormatException e) {
                        log.warn("[ProactiveEngine] Skipping invalid rule line: {}", line);
                    }
                }
            }
            log.info("[ProactiveEngine] Loaded {} custom rules", customRules.size());
        } catch (IOException e) {
            log.warn("[ProactiveEngine] Failed to load custom rules: {}", e.getMessage());
        }
    }

    private void saveCustomRules() {
        try {
            Files.createDirectories(RULES_PATH.getParent());
            StringBuilder sb = new StringBuilder("# Proactive engine custom rules\n");
            sb.append("# Format: id|intervalMinutes|description\n\n");
            for (ProactiveRule rule : customRules) {
                sb.append(rule.id).append("|").append(rule.intervalMinutes).append("|")
                        .append(rule.description).append("\n");
            }
            Files.writeString(RULES_PATH, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[ProactiveEngine] Failed to save custom rules: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Inner class
    // ═══════════════════════════════════════════════════════════════════════════

    public static class ProactiveRule {
        public final String id;
        public final String description;
        public final int intervalMinutes;

        public ProactiveRule(String id, String description, int intervalMinutes) {
            this.id = id;
            this.description = description;
            this.intervalMinutes = intervalMinutes;
        }
    }
}
