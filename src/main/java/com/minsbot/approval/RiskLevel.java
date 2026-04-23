package com.minsbot.approval;

/**
 * Risk tier a tool method declares via {@link RequiresApproval}. Drives whether the
 * approval gate auto-approves, prompts once per session, or prompts on every call.
 */
public enum RiskLevel {
    /** Read-only / observational. Never prompts. */
    SAFE,

    /**
     * Has observable side effects outside the local machine (sends email, posts a
     * message, creates a PR, calls an external API). Prompts once per tool per
     * session unless Always-Allow is granted.
     */
    SIDE_EFFECT,

    /**
     * Destructive or hard-to-reverse on the local machine (deletes a file, moves
     * files in bulk, runs a shell command, cracks a password). Prompts every call
     * unless Always-Allow is granted.
     */
    DESTRUCTIVE
}
