package com.minsbot.agent.tools;

import com.minsbot.agent.BargeInService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Chat controls for the JARVIS-style barge-in feature — lets the user toggle
 * "can I interrupt you mid-sentence" from the chat.
 */
@Component
public class BargeInTools {

    private final BargeInService bargeInService;
    private final ToolExecutionNotifier notifier;

    public BargeInTools(BargeInService bargeInService, ToolExecutionNotifier notifier) {
        this.bargeInService = bargeInService;
        this.notifier = notifier;
    }

    @Tool(description = "Enable or disable barge-in. When ON, the bot stops speaking as soon as it hears "
            + "the user start to speak (JARVIS-style interrupt). Use when the user says 'let me interrupt you', "
            + "'stop barging in', 'turn on barge-in', 'let me cut you off', 'don't let me interrupt'.")
    public String setBargeInEnabled(
            @ToolParam(description = "true to allow user to interrupt TTS, false to keep playback uninterruptible") boolean enabled) {
        notifier.notify((enabled ? "Enabling" : "Disabling") + " barge-in...");
        bargeInService.setEnabled(enabled);
        return "Barge-in " + (enabled ? "ENABLED — I'll stop speaking the moment I hear you."
                : "DISABLED — I'll finish whatever I'm saying before listening again.");
    }

    @Tool(description = "Check whether barge-in (interrupt-TTS-on-speech) is currently enabled.")
    public String getBargeInStatus() {
        return "Barge-in is currently " + (bargeInService.isEnabled() ? "ENABLED" : "DISABLED") + ".";
    }
}
