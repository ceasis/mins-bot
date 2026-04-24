package com.minsbot.agent.tools;

/**
 * Progress sink passed into the three code generators so they can emit
 * structured events (log lines, file-created notices, status changes) to
 * interested observers — the SSE stream on the Code page, the chat notifier,
 * a background job record, etc.
 *
 * <p>All methods are no-ops when the implementation has no listeners, so
 * generators can call them liberally without caring whether anyone subscribes.</p>
 */
public interface CodeGenSink {

    /** A free-form log line. UI renders these as a scrolling log. */
    void log(String line);

    /** A new file was written (relative path, forward-slash separated). */
    void file(String relativePath);

    /** Coarse status change: "running", "writing", "committing", "pushing", "done", "failed". */
    void status(String status);

    /** Called when the generator starts a subprocess, so the job can cancel it. */
    default void setProcess(Process p) { }

    /** No-op sink for callers that don't care. */
    CodeGenSink NOOP = new CodeGenSink() {
        @Override public void log(String line) { }
        @Override public void file(String rel) { }
        @Override public void status(String s) { }
    };
}
