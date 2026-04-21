package com.minsbot.skills.meetingmode;

import com.minsbot.TranscriptService;
import com.minsbot.agent.AsyncMessageService;
import com.minsbot.agent.EpisodicMemoryService;
import com.minsbot.agent.tools.AudioListeningTools;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Auto-detect when the user is in a video/voice meeting (Teams, Zoom, Meet,
 * Discord, Slack call) by scanning the foreground window title every 30s.
 * When a meeting is detected: starts audio listening + remembers the start
 * timestamp. When the meeting window goes away for > 60s: stops listening,
 * asks the AI to summarize what was transcribed, saves a meeting episode.
 */
@Service
public class MeetingModeService {

    private static final Logger log = LoggerFactory.getLogger(MeetingModeService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Window-title substrings (lowercase) that signal "you're in a meeting". */
    private static final List<String> MEETING_MARKERS = List.of(
            "| microsoft teams", "microsoft teams meeting", "meeting |", "| meeting",
            " | zoom", "zoom meeting", "zoom – meeting", "zoom - meeting",
            "google meet", "meet.google.com",
            "— discord", "- discord",   // Discord call windows add "Call — Friends"
            "slack | huddle"
    );

    @Autowired(required = false) private AudioListeningTools audio;
    @Autowired(required = false) private AsyncMessageService asyncMessages;
    @Autowired(required = false) private ChatClient chatClient;
    @Autowired(required = false) private EpisodicMemoryService episodic;
    @Autowired(required = false) private TranscriptService transcript;

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private volatile boolean inMeeting = false;
    private volatile Instant meetingStartedAt = null;
    private volatile String meetingAppTitle = null;
    private volatile int consecutiveMissed = 0;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "meeting-mode"); t.setDaemon(true); return t;
    });

    @Tool(description = "Turn on Meeting Mode. When enabled, Mins Bot checks every 30s whether the foreground "
            + "window is a Teams/Zoom/Meet/Discord call and automatically starts audio listening for it, "
            + "then summarizes and saves the meeting notes to episodic memory when the call ends. "
            + "Use when the user says 'enable meeting mode', 'auto-transcribe my meetings', 'listen to my meetings'.")
    public String enableMeetingMode() {
        if (enabled.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::tick, 2, 30, TimeUnit.SECONDS);
            return "🎙 Meeting Mode enabled. I'll auto-start audio listening when I see a call window, "
                    + "and summarize the meeting when it ends.";
        }
        return "Meeting Mode is already enabled.";
    }

    @Tool(description = "Turn off Meeting Mode. Stops auto-detection and any current listening session.")
    public String disableMeetingMode() {
        if (enabled.compareAndSet(true, false)) {
            if (inMeeting) stopAndSummarize("Meeting mode disabled mid-call.");
            return "Meeting Mode disabled.";
        }
        return "Meeting Mode is already off.";
    }

    @Tool(description = "Get the current Meeting Mode status: enabled/disabled and whether a meeting is currently being recorded.")
    public String meetingStatus() {
        if (!enabled.get()) return "Meeting Mode: OFF";
        if (!inMeeting) return "Meeting Mode: ON (no meeting detected yet)";
        long secs = Instant.now().getEpochSecond() - meetingStartedAt.getEpochSecond();
        return "Meeting Mode: ON · in-meeting for " + (secs / 60) + " min "
                + (secs % 60) + "s (window: " + meetingAppTitle + ")";
    }

    // ─── Internals ───

    private void tick() {
        try {
            String title = getForegroundWindowTitle();
            boolean isMeetingWindow = matchesMeetingMarker(title);

            if (isMeetingWindow && !inMeeting) {
                startMeeting(title);
            } else if (!isMeetingWindow && inMeeting) {
                consecutiveMissed++;
                // Require ~2 checks (60s) of no meeting window before ending
                if (consecutiveMissed >= 2) {
                    stopAndSummarize("Meeting window no longer in focus.");
                }
            } else {
                if (inMeeting) consecutiveMissed = 0;
            }
        } catch (Exception e) {
            log.debug("[MeetingMode] tick failed: {}", e.getMessage());
        }
    }

    private void startMeeting(String title) {
        inMeeting = true;
        meetingStartedAt = Instant.now();
        meetingAppTitle = title;
        consecutiveMissed = 0;
        if (audio != null && !audio.isListening()) {
            try { audio.startListening(); } catch (Exception ignored) {}
        }
        log.info("[MeetingMode] Meeting detected: {}", title);
        if (asyncMessages != null) {
            asyncMessages.push("🎙 Meeting detected (" + title + "). Auto-listening until it ends.");
        }
    }

    private void stopAndSummarize(String reason) {
        String startTs = meetingStartedAt != null ? TS.format(LocalDateTime.ofInstant(meetingStartedAt,
                java.time.ZoneId.systemDefault())) : "?";
        long durationMin = meetingStartedAt != null
                ? (Instant.now().getEpochSecond() - meetingStartedAt.getEpochSecond()) / 60 : 0;
        String title = meetingAppTitle;

        inMeeting = false;
        meetingStartedAt = null;
        meetingAppTitle = null;
        consecutiveMissed = 0;

        if (audio != null && audio.isListening()) {
            try { audio.stopListening(); } catch (Exception ignored) {}
        }

        // Build summary from recent chat (listen-mode pushes transcriptions there)
        String notes = summarizeMeeting(startTs, durationMin, title);

        // Save to episodic memory
        if (episodic != null && notes != null && !notes.isBlank()) {
            try {
                episodic.saveEpisode(
                        "meeting",
                        "Meeting — " + startTs + " (" + durationMin + " min)",
                        notes,
                        List.of("meeting", "auto"),
                        List.of(),
                        7);
            } catch (Exception ignored) {}
        }

        if (asyncMessages != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("📝 **Meeting ended** — ").append(reason).append("\n")
              .append("Duration: ").append(durationMin).append(" min · Started: ").append(startTs).append("\n\n");
            if (notes != null && !notes.isBlank()) sb.append(notes);
            asyncMessages.push(sb.toString());
        }
        log.info("[MeetingMode] Meeting ended: {} (duration {} min)", title, durationMin);
    }

    private String summarizeMeeting(String startTs, long durationMin, String title) {
        if (chatClient == null || transcript == null) {
            return "_(No AI or transcript service — raw audio logged to audio memory.)_";
        }
        try {
            List<String> recent = transcript.getRecentMemory();
            if (recent == null || recent.isEmpty()) {
                return "_(No chat lines captured during the meeting.)_";
            }
            // Take last ~60 lines; filter to listen-feed-like messages
            int from = Math.max(0, recent.size() - 80);
            StringBuilder capt = new StringBuilder();
            for (int i = from; i < recent.size(); i++) capt.append(recent.get(i)).append('\n');

            String prompt = "The user was just in a meeting (" + title + ", " + durationMin
                    + " min, starting " + startTs + "). Below are recent chat lines which include "
                    + "audio transcriptions from the meeting. Produce meeting notes in this format:\n\n"
                    + "**Key topics:** (2-5 bullets)\n"
                    + "**Decisions:** (bullets, or 'none' if unclear)\n"
                    + "**Action items:** (who — what; or 'none' if unclear)\n\n"
                    + "Be concise. Do not invent details that aren't in the transcript.\n\n"
                    + "Transcript:\n" + capt;

            return chatClient.prompt()
                    .system("You write crisp meeting notes. Keep them short, factual, skimmable.")
                    .user(prompt)
                    .call().content();
        } catch (Exception e) {
            log.warn("[MeetingMode] summary failed: {}", e.getMessage());
            return "_(Summary failed: " + e.getMessage() + ")_";
        }
    }

    private static String getForegroundWindowTitle() {
        try {
            WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return "";
            char[] buf = new char[512];
            User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
            return Native.toString(buf).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean matchesMeetingMarker(String title) {
        if (title == null || title.isEmpty()) return false;
        String low = title.toLowerCase(Locale.ROOT);
        for (String m : MEETING_MARKERS) if (low.contains(m)) return true;
        return false;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
