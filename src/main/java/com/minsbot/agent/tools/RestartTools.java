package com.minsbot.agent.tools;

import com.minsbot.RestartService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Chat tools for self-restart. Use when the user says "restart yourself",
 * "reboot the bot", "restart mins bot", etc.
 */
@Component
public class RestartTools {

    private final RestartService restartService;
    private final ToolExecutionNotifier notifier;

    public RestartTools(RestartService restartService, ToolExecutionNotifier notifier) {
        this.restartService = restartService;
        this.notifier = notifier;
    }

    @Tool(description = "Restart the Mins Bot application: quits the running process and relaunches "
            + "itself in the background. Use when the user says 'restart yourself', 'reboot the bot', "
            + "'restart mins bot', 'quit and start again'. WARNING: the chat session ends until the "
            + "new process is up (~3-5 seconds).")
    public String restartBot(
            @ToolParam(description = "Optional reason to log/announce (e.g. 'config changed', 'applying new prompt')") String reason) {
        String why = (reason == null || reason.isBlank()) ? "user-requested" : reason.trim();
        notifier.notify("Restarting bot (" + why + ")...");
        return restartService.restart();
    }

    @Tool(description = "Preview the exact command that would be used to relaunch the bot, without "
            + "actually restarting. Useful for debugging restart issues.")
    public String previewRestartCommand() {
        return restartService.previewCommand();
    }
}
