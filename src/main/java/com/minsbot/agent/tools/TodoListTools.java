package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent todolist tools for tracking plan step completion.
 * Reads/writes ~/mins_bot_data/todolist.txt.
 */
@Component
public class TodoListTools {

    private static final Logger log = LoggerFactory.getLogger(TodoListTools.class);

    private static final Path TODO_FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "todolist.txt");

    @Tool(description = "Mark a step as DONE in the todolist. Call this after completing each step in your plan. "
            + "Pass the step number (1, 2, 3...) to mark as done. This updates the LAST task's steps in todolist.txt.")
    public String markStepDone(
            @ToolParam(description = "The step number to mark as done, e.g. 1, 2, 3") int stepNumber) {
        try {
            if (!Files.exists(TODO_FILE)) {
                return "No todolist.txt found.";
            }

            List<String> lines = new ArrayList<>(Files.readAllLines(TODO_FILE));
            String target = "[PENDING] " + stepNumber + ".";
            boolean found = false;

            // Search from bottom up to find the most recent task's step
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i).trim();
                if (line.startsWith(target)) {
                    lines.set(i, lines.get(i).replace("[PENDING]", "[DONE]"));
                    found = true;
                    log.info("[TodoList] Marked step {} as DONE", stepNumber);
                    break;
                }
                // Stop at the task header — don't go past the current task
                if (line.startsWith("--- Task:")) break;
            }

            if (!found) {
                return "Step " + stepNumber + " not found as [PENDING] in current task.";
            }

            Files.writeString(TODO_FILE, String.join("\n", lines) + "\n");
            return "Step " + stepNumber + " marked as done.";
        } catch (IOException e) {
            log.error("[TodoList] Failed to update: {}", e.getMessage());
            return "Failed to update todolist: " + e.getMessage();
        }
    }

    @Tool(description = "Get ONLY the most recent task's pending (incomplete) steps from the todolist. "
            + "Older task blocks are suppressed so this doesn't dump dozens of unrelated backlogs into "
            + "the context. Total output is capped at ~3000 characters.")
    public String getPendingTasks() {
        try {
            if (!Files.exists(TODO_FILE)) {
                return "No todolist.txt found — no pending tasks.";
            }

            String content = Files.readString(TODO_FILE);
            if (!content.contains("[PENDING]")) {
                return "No pending tasks. All steps are done.";
            }

            // Extract ONLY the most recent task block's pending items. Older task blocks
            // are stale and ballooning the context with them burns tokens + latency.
            String[] lines = content.split("\n");
            String lastTaskHeader = null;
            List<String> lastPending = new ArrayList<>();
            String runningTask = null;
            List<String> runningPending = new ArrayList<>();
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("--- Task:")) {
                    if (!runningPending.isEmpty() && runningTask != null) {
                        lastTaskHeader = runningTask;
                        lastPending = new ArrayList<>(runningPending);
                    }
                    runningTask = t;
                    runningPending.clear();
                } else if (t.startsWith("[PENDING]")) {
                    runningPending.add(t);
                }
            }
            if (!runningPending.isEmpty() && runningTask != null) {
                lastTaskHeader = runningTask;
                lastPending = runningPending;
            }
            if (lastTaskHeader == null || lastPending.isEmpty()) return "No pending tasks.";
            StringBuilder out = new StringBuilder();
            out.append(lastTaskHeader).append('\n');
            for (String p : lastPending) {
                if (out.length() > 3000) { out.append("  ... (truncated)\n"); break; }
                out.append("  ").append(p).append('\n');
            }
            return out.toString().trim();
        } catch (IOException e) {
            return "Failed to read todolist: " + e.getMessage();
        }
    }
}
