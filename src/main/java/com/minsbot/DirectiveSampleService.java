package com.minsbot;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates fresh random directive samples via GPT-4o-mini.
 * Used by the Directives UI "Generate" button so each click produces
 * a new, creative set rather than cycling a fixed pool.
 */
@Service
public class DirectiveSampleService {

    private static final Logger log = LoggerFactory.getLogger(DirectiveSampleService.class);

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${app.directive-sampler.model:gpt-4o-mini}")
    private String model;

    private HttpClient httpClient;

    private static final String SYSTEM_PROMPT =
            "You generate short standing directives a user might give to their personal AI assistant. "
          + "Each directive is one sentence, imperative voice, 6-18 words, concrete and actionable. "
          + "Cover a mix of: safety, communication style, workflow preferences, privacy, "
          + "verification habits, time management, and everyday assistant behavior. "
          + "Return EXACTLY 6 directives, one per line, no numbering, no bullets, no quotes, no preamble.";

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Returns 6 freshly generated directives, or empty list on failure. */
    public List<String> generate(int count) {
        if (!isAvailable()) return Collections.emptyList();
        int seed = ThreadLocalRandom.current().nextInt(100000);
        String userPrompt = "Generate " + count + " fresh directives. Vary topics widely. "
                + "Random seed: " + seed + " (use it to pick unusual angles).";
        try {
            String body = ("""
                    {"model":"%s","temperature":1.2,"top_p":0.95,"max_tokens":400,"messages":[
                      {"role":"system","content":"%s"},
                      {"role":"user","content":"%s"}
                    ]}""").formatted(escapeJson(model), escapeJson(SYSTEM_PROMPT), escapeJson(userPrompt));

            String url = baseUrl.endsWith("/")
                    ? baseUrl + "v1/chat/completions"
                    : baseUrl + "/v1/chat/completions";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.debug("[DirectiveSampler] HTTP {} from model", res.statusCode());
                return Collections.emptyList();
            }

            String content = extractContent(res.body());
            return parseLines(content, count);
        } catch (Exception e) {
            log.debug("[DirectiveSampler] Failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<String> parseLines(String content, int max) {
        if (content == null || content.isBlank()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            line = line.replaceFirst("^[-*•]\\s*", "");
            line = line.replaceFirst("^\\d+[.)]\\s*", "");
            line = line.replaceAll("^[\"'\u201C\u2018]+|[\"'\u201D\u2019]+$", "");
            line = line.trim();
            if (line.length() < 8) continue;
            out.add(line);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static String extractContent(String json) {
        int choicesIdx = json.indexOf("\"choices\"");
        if (choicesIdx < 0) return "";
        int contentIdx = json.indexOf("\"content\":", choicesIdx);
        if (contentIdx < 0) return "";
        int start = json.indexOf('"', contentIdx + 10);
        if (start < 0) return "";
        start++;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                i++;
                char next = json.charAt(i);
                if (next == 'n') sb.append('\n');
                else if (next == 't') sb.append('\t');
                else if (next == 'r') sb.append('\r');
                else sb.append(next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
