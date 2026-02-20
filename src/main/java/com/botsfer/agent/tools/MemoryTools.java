package com.botsfer.agent.tools;

import com.botsfer.memory.MemoryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class MemoryTools {

    private final MemoryService memoryService;
    private final ToolExecutionNotifier notifier;

    public MemoryTools(MemoryService memoryService, ToolExecutionNotifier notifier) {
        this.memoryService = memoryService;
        this.notifier = notifier;
    }

    @Tool(description = "Save a note or reminder under a key. The user can later ask to recall it. Key must be alphanumeric with dots, dashes, or underscores (e.g. 'meeting-time', 'reminder.birthday').")
    public String saveNote(
            @ToolParam(description = "A short key to remember this by (e.g. 'meeting-time', 'wifi-password')") String key,
            @ToolParam(description = "The text to remember") String value) {
        if (!memoryService.isEnabled()) return "Notes are disabled (app.memory.enabled=false).";
        notifier.notify("Saving note...");
        try {
            memoryService.save(key, value != null ? value : "");
            return "Saved note under key: " + key;
        } catch (IllegalArgumentException e) {
            return "Invalid key: " + e.getMessage() + " Use only letters, numbers, dots, dashes, underscores.";
        } catch (IOException e) {
            return "Failed to save note: " + e.getMessage();
        }
    }

    @Tool(description = "Recall a note or reminder previously saved under a key. Use when the user asks what they asked to remember or to look up a saved note.")
    public String getNote(
            @ToolParam(description = "The key the note was saved under") String key) {
        if (!memoryService.isEnabled()) return "Notes are disabled (app.memory.enabled=false).";
        notifier.notify("Recalling note...");
        try {
            return memoryService.load(key)
                    .map(v -> v.isEmpty() ? "(empty note)" : v)
                    .orElse("No note found for key: " + key);
        } catch (IOException e) {
            return "Failed to load note: " + e.getMessage();
        }
    }

    @Tool(description = "List all note keys the user has saved. Use to see what notes exist before calling getNote.")
    public String listNoteKeys() {
        if (!memoryService.isEnabled()) return "Notes are disabled (app.memory.enabled=false).";
        notifier.notify("Listing notes...");
        try {
            List<String> keys = memoryService.listKeys();
            if (keys.isEmpty()) return "No notes saved yet.";
            return "Saved note keys: " + String.join(", ", keys);
        } catch (IOException e) {
            return "Failed to list notes: " + e.getMessage();
        }
    }

    @Tool(description = "Delete a saved note by its key.")
    public String deleteNote(
            @ToolParam(description = "The key of the note to delete") String key) {
        if (!memoryService.isEnabled()) return "Notes are disabled (app.memory.enabled=false).";
        notifier.notify("Deleting note...");
        try {
            boolean removed = memoryService.delete(key);
            return removed ? "Deleted note: " + key : "No note found for key: " + key;
        } catch (IOException e) {
            return "Failed to delete note: " + e.getMessage();
        }
    }
}
