package com.minsbot.agent.tools;

import com.minsbot.mission.Mission;
import com.minsbot.mission.MissionService;
import com.minsbot.mission.MissionStep;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Chat-facing wrappers for the mission system — lets the LLM launch long-running,
 * goal-directed jobs directly from a user message (e.g. "spend the next few hours
 * finding and summarizing every blog post about Claude 4.7"), then check their
 * status later.
 */
@Component
public class MissionTools {

    private final ToolExecutionNotifier notifier;
    private final MissionService missions;

    public MissionTools(ToolExecutionNotifier notifier, MissionService missions) {
        this.notifier = notifier;
        this.missions = missions;
    }

    @Tool(description =
            "Kick off a LONG-RUNNING mission: a goal-directed background job that decomposes " +
            "into steps and runs each in isolation. Call this when the user wants the bot to " +
            "work on something over hours (\"spend the next 4 hours doing X\", \"keep working " +
            "on Y until done\", \"start a mission to Z\", \"run this overnight\"). Returns the " +
            "mission id. Progress is surfaced in chat automatically as each step finishes — " +
            "do NOT poll status unless the user asks.")
    public String startMission(
            @ToolParam(description = "The mission goal, in the user's own words. Plain English, " +
                    "as specific as they gave it.") String goal) {
        notifier.notify("Starting mission: " + shorten(goal, 60));
        try {
            String id = missions.startMission(goal);
            return "Mission started. Id: " + id + ". I'll post progress as each step completes. "
                    + "You can say \"stop the mission\" to cancel.";
        } catch (Exception e) {
            return "Couldn't start mission: " + e.getMessage();
        }
    }

    @Tool(description =
            "Check the status of a running or finished mission. Call this when the user asks " +
            "\"how's the mission going\", \"mission status\", or names a mission id. Returns " +
            "a short summary: which step is running, steps done, any errors.")
    public String checkMissionStatus(
            @ToolParam(description = "The mission id. If the user didn't give one, pass an " +
                    "empty string and the latest mission is used.", required = false) String id) {
        notifier.notify("Checking mission status");
        Mission m = resolve(id);
        if (m == null) return "No mission found.";
        long done = m.steps.stream().filter(s -> "done".equals(s.status)).count();
        long failed = m.steps.stream().filter(s -> "failed".equals(s.status)).count();
        StringBuilder sb = new StringBuilder();
        sb.append("Mission ").append(shortId(m.id)).append(" — ").append(m.status).append('\n');
        sb.append("Goal: ").append(shorten(m.goal, 160)).append('\n');
        sb.append("Progress: ").append(done).append(" done");
        if (failed > 0) sb.append(", ").append(failed).append(" failed");
        sb.append(" of ").append(m.steps.size()).append(" steps").append('\n');
        if (!"done".equals(m.status) && m.currentStepIndex < m.steps.size()) {
            MissionStep cur = m.steps.get(m.currentStepIndex);
            sb.append("Current: ").append(shorten(cur.description, 120)).append(" [")
              .append(cur.status).append("]").append('\n');
        }
        if (m.lastError != null && !m.lastError.isBlank()) {
            sb.append("Last error: ").append(shorten(m.lastError, 200)).append('\n');
        }
        return sb.toString().trim();
    }

    @Tool(description = "Cancel a running mission. Call when the user says stop/cancel/abort the mission.")
    public String stopMission(
            @ToolParam(description = "Mission id; empty string = latest mission.", required = false) String id) {
        notifier.notify("Stopping mission");
        Mission m = resolve(id);
        if (m == null) return "No mission found to stop.";
        boolean ok = missions.stopMission(m.id);
        return ok ? "Cancel signal sent to mission " + shortId(m.id) + "."
                  : "Couldn't signal mission " + shortId(m.id) + " (may already be finished).";
    }

    @Tool(description = "List recent missions (running and finished). Call when the user asks " +
            "\"what missions are running\", \"show my missions\", \"list missions\".")
    public String listMissions() {
        notifier.notify("Listing missions");
        List<Mission> ms = missions.listMissions();
        if (ms.isEmpty()) return "No missions yet.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(ms.size(), 10); i++) {
            Mission m = ms.get(i);
            long done = m.steps.stream().filter(s -> "done".equals(s.status)).count();
            sb.append(shortId(m.id)).append(" · ").append(m.status).append(" · ")
              .append(done).append("/").append(m.steps.size()).append(" steps · ")
              .append(shorten(m.goal, 80)).append('\n');
        }
        return sb.toString().trim();
    }

    // ─── helpers ───────────────────────────────────────────────────

    private Mission resolve(String id) {
        if (id != null && !id.isBlank()) return missions.getMission(id.trim());
        List<Mission> all = missions.listMissions();
        return all.isEmpty() ? null : all.get(0);
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(8, id.length()));
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }
}
