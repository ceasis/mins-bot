package com.botsfer.agent.tools;

import com.botsfer.TranscriptService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChatHistoryTool {

    private final TranscriptService transcriptService;
    private final ToolExecutionNotifier notifier;

    public ChatHistoryTool(TranscriptService transcriptService, ToolExecutionNotifier notifier) {
        this.transcriptService = transcriptService;
        this.notifier = notifier;
    }

    @Tool(description = "Recall recent conversation history from memory (last 100 messages). "
            + "Use this when the user asks about something you said earlier, "
            + "or references a previous conversation in this session.")
    public String recallRecentConversation(
            @ToolParam(description = "Keyword or phrase to search for in recent messages") String query) {
        notifier.notify("Searching recent memory: " + query);
        List<String> matches = transcriptService.searchMemory(query);
        if (matches.isEmpty()) {
            return "No recent messages found matching \"" + query + "\".";
        }
        StringBuilder sb = new StringBuilder("Found " + matches.size() + " recent message(s):\n");
        for (String line : matches) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Search past conversation history files on disk. "
            + "Use this when the user asks about older conversations from previous days or sessions, "
            + "or when nothing was found in recent memory.")
    public String searchPastConversations(
            @ToolParam(description = "Keyword or phrase to search for in past conversations") String query) {
        notifier.notify("Searching chat history files: " + query);
        List<String> matches = transcriptService.searchHistoryFiles(query, 30);
        if (matches.isEmpty()) {
            return "No past conversations found matching \"" + query + "\".";
        }
        StringBuilder sb = new StringBuilder("Found " + matches.size() + " past message(s):\n");
        for (String line : matches) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Get the full recent conversation buffer (last 100 messages) to review context")
    public String getFullRecentHistory() {
        notifier.notify("Loading recent conversation...");
        List<String> history = transcriptService.getRecentMemory();
        if (history.isEmpty()) {
            return "No conversation history yet.";
        }
        StringBuilder sb = new StringBuilder("Last " + history.size() + " messages:\n");
        for (String line : history) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }
}
