package com.minsbot.mission;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One atomic step in a {@link Mission}. Each step is a self-contained instruction
 * ("open the CSV at X and list headers", "download the top 10 results for query Y")
 * executed by a single LLM call with the full tool suite. Retries are recorded on
 * the step itself so a crashed run can resume exactly where it left off.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MissionStep {

    public int index;
    public String description;
    public String status;       // pending | running | done | failed | skipped
    public int attempts = 0;
    public String result;       // latest tool/LLM output (may be long — used to build scratchpad)
    public String error;        // on final failure, the exception / LLM error
    public String startedAt;
    public String completedAt;

    public MissionStep() {}

    public MissionStep(int index, String description) {
        this.index = index;
        this.description = description;
        this.status = "pending";
    }
}
