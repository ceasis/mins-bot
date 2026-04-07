package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Auto-memory extractor: analyzes every user message for life facts worth remembering
 * and auto-saves them to episodic memory without being asked.
 *
 * <p>Detects: birthdays, relationships, preferences, life events, job changes,
 * personal info, important dates, health conditions, etc.
 *
 * <p>Runs asynchronously so it never slows down the chat response.
 */
@Service
public class AutoMemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(AutoMemoryExtractor.class);

    private final EpisodicMemoryService episodicMemory;

    @Autowired(required = false)
    private volatile ChatClient chatClient;

    @Value("${app.auto-memory.enabled:true}")
    private boolean enabled;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auto-memory-extractor");
        t.setDaemon(true);
        return t;
    });

    // Quick regex pre-filters — only run AI extraction when the message MIGHT contain a life fact.
    // This avoids burning tokens on every "hi" and "what time is it?"
    private static final Pattern MIGHT_CONTAIN_FACT = Pattern.compile(
            "(?i)"
            + "\\b(my|i'm|i am|im|i have|i've|ive|i got|i like|i love|i hate|i prefer|i need|i want)\\b"
            + "|\\b(born|birthday|anniversary|married|engaged|divorced|pregnant|died|passed away)\\b"
            + "|\\b(my (mom|dad|mother|father|brother|sister|wife|husband|partner|son|daughter|friend|boss|dog|cat))\\b"
            + "|\\b(allergic|allergy|medication|diagnosed|condition|surgery)\\b"
            + "|\\b(moved to|live in|living in|from \\w+ originally|hometown|address)\\b"
            + "|\\b(work at|working at|job|hired|fired|promoted|retired|started at|left my)\\b"
            + "|\\b(email is|phone is|number is|account is|password|address is)\\b"
            + "|\\b(favorite|favourite)\\b"
            + "|\\b(remember that|don't forget|important date|save this|keep in mind)\\b"
    );

    private static final String EXTRACTION_PROMPT = """
            You are a memory extractor. Analyze the user's message and extract any life facts worth \
            remembering for future conversations. Focus on:

            - People: names, relationships (my mom is Katherine, my boss is John)
            - Dates: birthdays, anniversaries, important dates (mom's birthday is March 15)
            - Preferences: likes, dislikes, favorites (I love sushi, I hate mornings)
            - Life events: job changes, moves, health, milestones (I just got promoted, we moved to Seattle)
            - Personal info: allergies, conditions, dietary restrictions (I'm lactose intolerant)
            - Contact info: when voluntarily shared (my email is X)

            Rules:
            - ONLY extract genuine personal facts, not questions or hypotheticals
            - If the message contains NO personal facts, respond with exactly: NONE
            - For each fact found, output one line in this format:
              TYPE|SUMMARY|DETAILS|TAGS|PEOPLE|IMPORTANCE
            - TYPE: relationship, date, preference, life_event, health, personal_info, work, contact
            - IMPORTANCE: 1-5 (1=trivial preference, 3=notable fact, 5=major life event)
            - TAGS: comma-separated keywords
            - PEOPLE: comma-separated names mentioned

            Examples:
            User: "My mom's birthday is March 15, her name is Katherine"
            date|Mom Katherine's birthday is March 15|User's mother Katherine has birthday on March 15|birthday,family,mother|Katherine|4

            User: "I just got promoted to senior engineer at Google"
            work|Promoted to senior engineer at Google|User was promoted to senior engineer role at Google|promotion,career,google|User|4

            User: "I'm allergic to peanuts"
            health|Allergic to peanuts|User has a peanut allergy - important for food recommendations|allergy,food,health|User|4

            User: "What's the weather?"
            NONE

            User message: "%s"
            """;

    public AutoMemoryExtractor(EpisodicMemoryService episodicMemory) {
        this.episodicMemory = episodicMemory;
    }

    /**
     * Analyze a user message for auto-saveable life facts.
     * Called asynchronously after every user chat message.
     */
    public void analyzeAsync(String userMessage) {
        if (!enabled || chatClient == null || userMessage == null) return;
        // Quick pre-filter: skip messages that clearly don't contain personal facts
        if (userMessage.length() < 10) return;
        if (!MIGHT_CONTAIN_FACT.matcher(userMessage).find()) return;

        executor.submit(() -> {
            try {
                extract(userMessage);
            } catch (Exception e) {
                log.debug("[AutoMemory] Extraction failed: {}", e.getMessage());
            }
        });
    }

    private void extract(String userMessage) {
        String prompt = String.format(EXTRACTION_PROMPT, userMessage.replace("\"", "'"));

        String result;
        try {
            result = chatClient.prompt()
                    .system("You are a memory extraction AI. Be precise and concise.")
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.debug("[AutoMemory] AI call failed: {}", e.getMessage());
            return;
        }

        if (result == null || result.isBlank() || result.trim().equalsIgnoreCase("NONE")) return;

        // Parse each line
        for (String line : result.split("\n")) {
            line = line.trim();
            if (line.isBlank() || line.equalsIgnoreCase("NONE")) continue;

            String[] parts = line.split("\\|", 6);
            if (parts.length < 4) continue;

            String type = parts[0].trim();
            String summary = parts.length > 1 ? parts[1].trim() : "";
            String details = parts.length > 2 ? parts[2].trim() : summary;
            String tags = parts.length > 3 ? parts[3].trim() : "";
            String people = parts.length > 4 ? parts[4].trim() : "";
            int importance = 3;
            if (parts.length > 5) {
                try { importance = Integer.parseInt(parts[5].trim()); }
                catch (NumberFormatException ignored) {}
            }

            if (summary.isBlank()) continue;

            // Check for duplicates (simple: search existing memories for similar summary)
            List<Map<String, Object>> existing = episodicMemory.searchEpisodes(summary, 5);
            boolean isDuplicate = false;
            if (existing != null && !existing.isEmpty()) {
                String subKey = summary.toLowerCase().substring(0, Math.min(20, summary.length()));
                for (Map<String, Object> ep : existing) {
                    String epSummary = String.valueOf(ep.getOrDefault("summary", ""));
                    if (epSummary.toLowerCase().contains(subKey)) { isDuplicate = true; break; }
                }
            }
            if (isDuplicate) {
                log.debug("[AutoMemory] Skipping duplicate: {}", summary);
                continue;
            }

            List<String> tagList = Arrays.stream(tags.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            List<String> peopleList = Arrays.stream(people.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();

            // Add "auto-extracted" tag so user knows this was automatic
            var allTags = new java.util.ArrayList<>(tagList);
            allTags.add("auto-extracted");

            String id = episodicMemory.saveEpisode(type, summary, details, allTags, peopleList,
                    Math.max(1, Math.min(5, importance)));

            if (id != null) {
                log.info("[AutoMemory] Auto-saved: {} — {} (importance={})", type, summary, importance);
            }
        }
    }
}
