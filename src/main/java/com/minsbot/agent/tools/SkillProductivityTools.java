package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.clipboardhistory.ClipboardHistoryConfig;
import com.minsbot.skills.clipboardhistory.ClipboardHistoryService;
import com.minsbot.skills.meetingcost.MeetingCostConfig;
import com.minsbot.skills.meetingcost.MeetingCostService;
import com.minsbot.skills.notes.NotesConfig;
import com.minsbot.skills.notes.NotesService;
import com.minsbot.skills.okrtracker.OkrTrackerConfig;
import com.minsbot.skills.okrtracker.OkrTrackerService;
import com.minsbot.skills.reminders.RemindersConfig;
import com.minsbot.skills.reminders.RemindersService;
import com.minsbot.skills.slacalc.SlaCalcConfig;
import com.minsbot.skills.slacalc.SlaCalcService;
import com.minsbot.skills.timer.TimerConfig;
import com.minsbot.skills.timer.TimerService;
import com.minsbot.skills.timezoneconvert.TimezoneConvertConfig;
import com.minsbot.skills.timezoneconvert.TimezoneConvertService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SkillProductivityTools {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolExecutionNotifier notifier;

    private final NotesService notes; private final NotesConfig.NotesProperties notesProps;
    private final RemindersService reminders; private final RemindersConfig.RemindersProperties remProps;
    private final TimerService timer; private final TimerConfig.TimerProperties timerProps;
    private final ClipboardHistoryService clipHist; private final ClipboardHistoryConfig.ClipboardHistoryProperties clipProps;
    private final OkrTrackerService okrs; private final OkrTrackerConfig.OkrTrackerProperties okrProps;
    private final TimezoneConvertService tz; private final TimezoneConvertConfig.TimezoneConvertProperties tzProps;
    private final MeetingCostService meetingCost; private final MeetingCostConfig.MeetingCostProperties mcProps;
    private final SlaCalcService sla; private final SlaCalcConfig.SlaCalcProperties slaProps;

    public SkillProductivityTools(ToolExecutionNotifier notifier,
                                  NotesService notes, NotesConfig.NotesProperties notesProps,
                                  RemindersService reminders, RemindersConfig.RemindersProperties remProps,
                                  TimerService timer, TimerConfig.TimerProperties timerProps,
                                  ClipboardHistoryService clipHist, ClipboardHistoryConfig.ClipboardHistoryProperties clipProps,
                                  OkrTrackerService okrs, OkrTrackerConfig.OkrTrackerProperties okrProps,
                                  TimezoneConvertService tz, TimezoneConvertConfig.TimezoneConvertProperties tzProps,
                                  MeetingCostService meetingCost, MeetingCostConfig.MeetingCostProperties mcProps,
                                  SlaCalcService sla, SlaCalcConfig.SlaCalcProperties slaProps) {
        this.notifier = notifier;
        this.notes = notes; this.notesProps = notesProps;
        this.reminders = reminders; this.remProps = remProps;
        this.timer = timer; this.timerProps = timerProps;
        this.clipHist = clipHist; this.clipProps = clipProps;
        this.okrs = okrs; this.okrProps = okrProps;
        this.tz = tz; this.tzProps = tzProps;
        this.meetingCost = meetingCost; this.mcProps = mcProps;
        this.sla = sla; this.slaProps = slaProps;
    }

    @Tool(description = "Create a quick note with a title and optional body. Returns the created note ID.")
    public String createNote(
            @ToolParam(description = "Note title") String title,
            @ToolParam(description = "Note body (can be empty)") String body) {
        if (!notesProps.isEnabled()) return disabled("notes");
        notifier.notify("Creating note: " + title);
        try { return toJson(notes.create(title, body, null)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Search saved notes by keyword. Returns matching notes.")
    public String searchNotes(
            @ToolParam(description = "Search query (case-insensitive match in title or body)") String query) {
        if (!notesProps.isEnabled()) return disabled("notes");
        try { return toJson(notes.search(query, null)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Create a time-based reminder. fireAt must be ISO-8601 (e.g. 2026-05-01T14:30:00Z).")
    public String createReminder(
            @ToolParam(description = "Reminder message") String message,
            @ToolParam(description = "ISO-8601 datetime when reminder should fire") String fireAt) {
        if (!remProps.isEnabled()) return disabled("reminders");
        try { return toJson(reminders.create(message, fireAt)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "List all pending reminders (not yet fired).")
    public String listReminders() {
        if (!remProps.isEnabled()) return disabled("reminders");
        try { return toJson(reminders.list(false)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Start a countdown timer. Returns the timer ID.")
    public String startTimer(
            @ToolParam(description = "Timer name (e.g. 'Tea brew', 'Pomodoro')") String name,
            @ToolParam(description = "Duration in seconds") double seconds) {
        if (!timerProps.isEnabled()) return disabled("timer");
        try { return toJson(timer.start(name, (long) (seconds * 1000))); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "List all active timers with remaining time.")
    public String listTimers() {
        if (!timerProps.isEnabled()) return disabled("timer");
        return toJson(timer.list());
    }

    @Tool(description = "Search the clipboard history (last ~100 copied items) for a keyword.")
    public String searchClipboardHistory(
            @ToolParam(description = "Search query") String query) {
        if (!clipProps.isEnabled()) return disabled("clipboardhistory");
        return toJson(clipHist.search(query));
    }

    @Tool(description = "Create an OKR (Objective + Key Results). keyResults is a list of {name, target, current} maps.")
    public String createOkr(
            @ToolParam(description = "The objective (e.g. 'Ship v2 by Q2')") String objective,
            @ToolParam(description = "Owner name (optional, can be empty string)") String owner) {
        if (!okrProps.isEnabled()) return disabled("okrtracker");
        try { return toJson(okrs.create(objective, List.of(), owner)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "List all OKRs with their current progress.")
    public String listOkrs() {
        if (!okrProps.isEnabled()) return disabled("okrtracker");
        try { return toJson(okrs.list()); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Convert a datetime between time zones. dateTime format: 'yyyy-MM-ddTHH:mm:ss'. Zones are IANA IDs like 'America/New_York' or 'Asia/Tokyo'.")
    public String convertTimezone(
            @ToolParam(description = "Local datetime in 'yyyy-MM-ddTHH:mm:ss' format") String dateTime,
            @ToolParam(description = "Source IANA timezone ID") String fromZone,
            @ToolParam(description = "Target IANA timezone ID") String toZone) {
        if (!tzProps.isEnabled()) return disabled("timezoneconvert");
        try { return toJson(tz.convert(dateTime, fromZone, toZone)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Calculate the cost of a meeting given attendee count, average hourly rate, and duration in minutes.")
    public String calculateMeetingCost(
            @ToolParam(description = "Number of attendees") double attendees,
            @ToolParam(description = "Average hourly rate in dollars") double avgHourlyRate,
            @ToolParam(description = "Meeting duration in minutes") double durationMinutes) {
        if (!mcProps.isEnabled()) return disabled("meetingcost");
        try { return toJson(meetingCost.computeSimple((int) attendees, avgHourlyRate, (int) durationMinutes)); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Convert an uptime % (e.g. 99.9) into allowed downtime per year/month/week/day. "
            + "Use for SLA calculations.")
    public String slaUptimeToDowntime(
            @ToolParam(description = "Uptime percentage (0-100)") double uptimePct) {
        if (!slaProps.isEnabled()) return disabled("slacalc");
        try { return toJson(sla.uptimeToDowntime(uptimePct)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String disabled(String name) { return "Skill '" + name + "' is disabled. Enable via app.skills." + name + ".enabled=true"; }
    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return String.valueOf(obj); }
    }
}
