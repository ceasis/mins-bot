package com.minsbot.agent.tools;

import com.minsbot.BackgroundAgentService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Orchestrator / sub-agent spawning. One unified tool — if {@code parentAgentId}
 * is empty, the spawned agent is a top-level orchestrator. If set, it's a sub-agent
 * running under that orchestrator.
 *
 * <p>Typical pattern:
 * <ol>
 *   <li>User asks for something complex. Main chat AI calls
 *       {@code spawnAgent("Marketing Agent", mission, null)} — creates an orchestrator.</li>
 *   <li>Orchestrator decomposes the mission and calls
 *       {@code spawnAgent("Video Creator Agent", subMission, orchestratorId)} for each subtask.</li>
 *   <li>Orchestrator waits for/monitors children via {@code listAgents} and {@code getAgentStatus}.</li>
 * </ol>
 */
@Component
public class OrchestratorTools {

    private final BackgroundAgentService agents;
    private final ToolExecutionNotifier notifier;

    @Autowired
    public OrchestratorTools(@Lazy BackgroundAgentService agents, ToolExecutionNotifier notifier) {
        this.agents = agents;
        this.notifier = notifier;
    }

    @Tool(description = "Spawn a background agent to work on a task. If parentAgentId is empty, "
            + "creates a top-level ORCHESTRATOR agent. If parentAgentId is set, creates a SUB-AGENT "
            + "running under that orchestrator. Use this when the user asks for complex multi-step "
            + "work you can decompose (e.g. 'make a marketing campaign' → spawn a 'Marketing Agent' "
            + "orchestrator, which spawns 'Video Creator Agent', 'Content Creator Agent', "
            + "'Video Editor Agent' as children). Returns the new agent's ID.")
    public String spawnAgent(
            @ToolParam(description = "User-facing display name, e.g. 'Marketing Agent', 'Research Agent', 'Video Editor Agent'") String displayName,
            @ToolParam(description = "Mission for the agent: concrete, self-contained task description") String mission,
            @ToolParam(description = "Parent orchestrator's agent ID, or empty string '' if this is a top-level orchestrator") String parentAgentId) {
        String name = (displayName == null || displayName.isBlank()) ? "Agent" : displayName.trim();
        notifier.notify("Spawning " + name + "...");

        String parent = (parentAgentId == null || parentAgentId.isBlank()) ? null : parentAgentId.trim();
        try {
            String id = agents.startAgent(mission, null, parent, name);
            String role = parent == null ? "orchestrator" : "sub-agent of " + parent;
            return "Spawned " + name + " (id: " + id + ", " + role + "). "
                    + "It will appear in the Agents tab. "
                    + (parent == null
                        ? "As the orchestrator, it can spawn its own sub-agents by calling spawnAgent with parentAgentId=" + id + "."
                        : "It will report its result when done.");
        } catch (IllegalStateException e) {
            return "Could not spawn agent: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid spawn: " + e.getMessage();
        }
    }

    @Tool(description = "List all active and recent background agents with their status, parent, "
            + "and progress. Use when deciding whether to spawn a new agent or wait for existing ones, "
            + "or when the user asks 'what agents are running'.")
    public String listAgents() {
        notifier.notify("Listing background agents...");
        var jobs = agents.listJobs();
        if (jobs.isEmpty()) return "No agents running.";
        StringBuilder sb = new StringBuilder("Agents (" + jobs.size() + "):\n");
        for (var j : jobs) {
            String name = String.valueOf(j.getOrDefault("name", "?"));
            String status = String.valueOf(j.getOrDefault("status", "?"));
            String parentId = String.valueOf(j.getOrDefault("parentJobId", ""));
            String id = String.valueOf(j.getOrDefault("id", "?"));
            int pct = (int) j.getOrDefault("progressPercent", 0);
            sb.append(parentId.isEmpty() ? "  • " : "    ↳ ")
                    .append(name).append(" [").append(id).append("] ")
                    .append(status).append(" (").append(pct).append("%)\n");
        }
        return sb.toString();
    }

    @Tool(description = "Get the current status, progress, log, and result of a specific agent by ID.")
    public String getAgentStatus(
            @ToolParam(description = "Agent ID returned by spawnAgent") String agentId) {
        var job = agents.getJob(agentId);
        if (job == null) return "Agent '" + agentId + "' not found.";
        StringBuilder sb = new StringBuilder();
        sb.append(job.getEffectiveName()).append(" [").append(job.getId()).append("]\n");
        sb.append("  Status: ").append(job.getStatus()).append(" (").append(job.getProgressPercent()).append("%)\n");
        sb.append("  Progress: ").append(job.getProgress() == null ? "" : job.getProgress()).append("\n");
        if (job.getParentJobId() != null) sb.append("  Parent: ").append(job.getParentJobId()).append("\n");
        String res = job.getResult();
        if (res != null && !res.isBlank()) {
            sb.append("  Result: ").append(res.length() > 500 ? res.substring(0, 500) + "..." : res).append("\n");
        }
        String err = job.getError();
        if (err != null && !err.isBlank()) sb.append("  Error: ").append(err).append("\n");
        return sb.toString();
    }
}
