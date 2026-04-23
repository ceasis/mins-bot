package com.minsbot.mission;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A long-running, multi-step task executed by {@link MissionService}. Missions
 * are goal-directed: a user states what they want, an LLM decomposes that into
 * steps, and the service executes each step in isolation (fresh LLM call per
 * step, not one mega-context). Each step's result is distilled into a shared
 * scratchpad so later steps can see what earlier ones produced without blowing
 * the context window.
 *
 * <p>Persisted to {@code memory/missions/<id>/mission.json} after every step so
 * a crash doesn't lose progress.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Mission {

    public String id;
    public String goal;
    public String status;             // pending | planning | running | paused | done | failed | cancelled
    public String createdAt;
    public String startedAt;
    public String completedAt;

    public List<MissionStep> steps = new ArrayList<>();
    public int currentStepIndex = 0;

    /** Soft cap — stop if cumulative tokens used exceed this. 0 = no cap. */
    public long tokenBudget = 500_000L;
    public long tokensUsed = 0L;

    /** How many times to retry a failed step before giving up. */
    public int maxRetries = 3;

    /** Last error surfaced to the user. */
    public String lastError;

    /**
     * Per-mission scratchpad of concise step summaries the LLM sees on each
     * subsequent step. Capped so it doesn't grow without bound.
     */
    public List<String> scratchpad = new ArrayList<>();

    public Mission() {}

    public Mission(String id, String goal) {
        this.id = id;
        this.goal = goal;
        this.status = "pending";
        this.createdAt = Instant.now().toString();
    }
}
