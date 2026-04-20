package com.minsbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Small-model (gpt-4o-mini) helper to auto-name a chat based on its transcript.
 * Uses raw HTTP against the OpenAI API — avoids polluting the main ChatClient's memory.
 */
@Service
public class ChatNameGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(ChatNameGeneratorService.class);

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${app.chat-name-generator.model:gpt-4o-mini}")
    private String model;

    private HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && httpClient != null;
    }

    /**
     * Generate a 3-6 word name for the given chat transcript lines.
     * Returns a fallback like "Chat <date> <time>" if the API is unavailable or fails.
     */
    public String generateName(List<String> transcriptLines) {
        String fallback = "Chat " + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        if (transcriptLines == null || transcriptLines.isEmpty()) return fallback;
        if (!isAvailable()) return fallback;

        // Trim to the first ~40 lines and cap total size
        StringBuilder sb = new StringBuilder();
        int cap = 4000;
        int lines = Math.min(transcriptLines.size(), 40);
        for (int i = 0; i < lines; i++) {
            String l = transcriptLines.get(i);
            if (sb.length() + l.length() + 1 > cap) break;
            sb.append(l).append("\n");
        }
        if (sb.length() == 0) return fallback;

        String systemPrompt = "You name chat conversations. Output ONLY a concise 3-6 word title that "
                + "captures the main topic or task. No quotes, no period, no explanation. "
                + "Use title case. If the conversation is empty or trivial, respond with exactly: Untitled chat.";
        String userPrompt = "Name this conversation:\n\n" + sb.toString().trim();

        try {
            String payload = mapper.writeValueAsString(java.util.Map.of(
                    "model", model,
                    "messages", List.of(
                            java.util.Map.of("role", "system", "content", systemPrompt),
                            java.util.Map.of("role", "user", "content", userPrompt)
                    ),
                    "temperature", 0.3,
                    "max_tokens", 24
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[ChatNameGen] HTTP {}: {}", resp.statusCode(),
                        resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
                return fallback;
            }
            JsonNode root = mapper.readTree(resp.body());
            String name = root.path("choices").path(0).path("message").path("content").asText("").trim();
            // Strip quotes/punctuation at edges
            name = name.replaceAll("^[\"'`]+|[\"'`.]+$", "").trim();
            if (name.isEmpty()) return fallback;
            if (name.length() > 80) name = name.substring(0, 80).trim();
            log.info("[ChatNameGen] Named chat: '{}'", name);
            return name;
        } catch (Exception e) {
            log.warn("[ChatNameGen] failed: {}", e.getMessage());
            return fallback;
        }
    }
}
