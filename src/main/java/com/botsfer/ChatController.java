package com.botsfer;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final TranscriptService transcriptService;

    public ChatController(ChatService chatService, TranscriptService transcriptService) {
        this.chatService = chatService;
        this.transcriptService = transcriptService;
    }

    /** Returns recent chat history for the frontend to display on load. */
    @GetMapping(value = "/chat/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chatHistory() {
        List<Map<String, Object>> messages = transcriptService.getStructuredHistory();
        return Map.of("messages", messages);
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> chat(@RequestBody Map<String, String> body) {
        String message = body != null ? body.get("message") : null;
        String reply = chatService.getReply(message);
        return Map.of("reply", reply);
    }

    /** Poll for async agent results (background tasks like file collection). */
    @GetMapping(value = "/chat/async", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> pollAsync() {
        String result = chatService.pollAsyncResult();
        if (result != null) {
            return Map.of("hasResult", true, "reply", result);
        }
        return Map.of("hasResult", false);
    }

    /** Poll for tool execution status updates while a request is in-flight. */
    @GetMapping(value = "/chat/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> pollToolStatus() {
        List<String> messages = chatService.drainToolStatus();
        return Map.of("messages", messages);
    }

    /** Open a file or folder in the system file explorer. */
    @PostMapping(value = "/open-path", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> openPath(@RequestBody Map<String, String> body) {
        String path = body != null ? body.get("path") : null;
        if (path == null || path.isBlank()) {
            return Map.of("status", "error", "message", "No path provided");
        }
        try {
            Path p = Paths.get(path.trim()).toAbsolutePath();
            if (!Files.exists(p)) {
                return Map.of("status", "error", "message", "Path not found: " + p);
            }
            // For files, open the parent folder and select the file
            if (Files.isRegularFile(p)) {
                new ProcessBuilder("explorer.exe", "/select,", p.toString()).start();
            } else {
                Desktop.getDesktop().open(p.toFile());
            }
            return Map.of("status", "ok", "message", "Opened: " + p);
        } catch (Exception e) {
            return Map.of("status", "error", "message", "Failed: " + e.getMessage());
        }
    }
}
