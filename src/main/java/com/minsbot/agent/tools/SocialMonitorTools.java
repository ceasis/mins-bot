package com.minsbot.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Social media monitor: track contacts, birthdays, mentions, and important posts.
 * Manages a contacts list with social handles, birthdays, and relationship tags.
 * Checks for upcoming birthdays, searches for mentions, and logs important posts.
 * Persisted to ~/mins_bot_data/social_monitor/.
 */
@Component
public class SocialMonitorTools {

    private static final Logger log = LoggerFactory.getLogger(SocialMonitorTools.class);
    private static final Path BASE_DIR = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "social_monitor");
    private static final Path CONTACTS_FILE = BASE_DIR.resolve("contacts.json");
    private static final Path POSTS_FILE = BASE_DIR.resolve("tracked_posts.json");
    private static final Path MENTIONS_FILE = BASE_DIR.resolve("mentions.json");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("MMM d");

    private final ToolExecutionNotifier notifier;
    private final AtomicLong idGen = new AtomicLong(1);
    private final List<Map<String, Object>> contacts = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> trackedPosts = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> mentions = new CopyOnWriteArrayList<>();

    public SocialMonitorTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @PostConstruct
    public void init() {
        load(CONTACTS_FILE, contacts);
        load(POSTS_FILE, trackedPosts);
        load(MENTIONS_FILE, mentions);
        long maxId = 0;
        for (var list : List.of(contacts, trackedPosts, mentions)) {
            for (var item : list) {
                long id = ((Number) item.getOrDefault("id", 0)).longValue();
                if (id > maxId) maxId = id;
            }
        }
        idGen.set(maxId + 1);
        log.info("[SocialMonitor] Loaded {} contacts, {} posts, {} mentions",
                contacts.size(), trackedPosts.size(), mentions.size());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Contact management
    // ═══════════════════════════════════════════════════════════════════════

    @Tool(description = "Add a contact to track on social media. Save their name, birthday, "
            + "social handles, relationship, and interests. "
            + "Use when the user says 'track my friend X', 'add contact', 'remember X's birthday'.")
    public String addContact(
            @ToolParam(description = "Contact's full name") String name,
            @ToolParam(description = "Birthday in YYYY-MM-DD format, or empty if unknown") String birthday,
            @ToolParam(description = "Relationship: family, friend, colleague, partner, close-friend, acquaintance") String relationship,
            @ToolParam(description = "Social handles as comma-separated 'platform:handle' pairs, "
                    + "e.g. 'twitter:@john, instagram:john.doe, facebook:John Doe, linkedin:john-doe'") String socialHandles,
            @ToolParam(description = "Interests/notes about this person, e.g. 'loves hiking, works at Google, gluten-free'") String notes) {
        notifier.notify("Adding contact: " + name);

        // Check for duplicates
        String lowerName = name.toLowerCase().trim();
        for (Map<String, Object> c : contacts) {
            if (((String) c.get("name")).toLowerCase().equals(lowerName)) {
                return "Contact '" + name + "' already exists. Use updateContact to modify.";
            }
        }

        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("id", idGen.getAndIncrement());
        contact.put("name", name.trim());
        contact.put("birthday", birthday != null && !birthday.isBlank() ? birthday.trim() : null);
        contact.put("relationship", relationship != null ? relationship.trim() : "friend");
        contact.put("socialHandles", parseSocialHandles(socialHandles));
        contact.put("notes", notes != null ? notes.trim() : "");
        contact.put("addedAt", System.currentTimeMillis());
        contact.put("lastChecked", 0);
        contacts.add(contact);
        save(CONTACTS_FILE, contacts);

        StringBuilder sb = new StringBuilder("Contact added: " + name);
        if (birthday != null && !birthday.isBlank()) {
            sb.append(" (birthday: ").append(birthday).append(")");
        }
        return sb.toString();
    }

    @Tool(description = "Update an existing contact's info. Pass the name and the fields to update.")
    public String updateContact(
            @ToolParam(description = "Contact name to update") String name,
            @ToolParam(description = "New birthday (YYYY-MM-DD), or empty to skip") String birthday,
            @ToolParam(description = "New relationship, or empty to skip") String relationship,
            @ToolParam(description = "New social handles (replaces existing), or empty to skip") String socialHandles,
            @ToolParam(description = "New notes, or empty to skip") String notes) {
        Map<String, Object> contact = findContact(name);
        if (contact == null) return "Contact not found: " + name;

        if (birthday != null && !birthday.isBlank()) contact.put("birthday", birthday.trim());
        if (relationship != null && !relationship.isBlank()) contact.put("relationship", relationship.trim());
        if (socialHandles != null && !socialHandles.isBlank()) contact.put("socialHandles", parseSocialHandles(socialHandles));
        if (notes != null && !notes.isBlank()) contact.put("notes", notes.trim());
        save(CONTACTS_FILE, contacts);
        return "Updated contact: " + name;
    }

    @Tool(description = "List all tracked contacts with their info. "
            + "Use when the user asks 'who am I tracking?', 'show my contacts', 'list social contacts'.")
    public String listContacts() {
        if (contacts.isEmpty()) return "No contacts tracked yet. Say 'add contact' to start.";

        StringBuilder sb = new StringBuilder("Tracked Contacts (" + contacts.size() + "):\n\n");
        for (Map<String, Object> c : contacts) {
            sb.append("● ").append(c.get("name"));
            String rel = (String) c.getOrDefault("relationship", "");
            if (!rel.isBlank()) sb.append(" [").append(rel).append("]");
            String bday = (String) c.get("birthday");
            if (bday != null) {
                sb.append(" — Birthday: ").append(bday);
                long daysUntil = daysUntilBirthday(bday);
                if (daysUntil == 0) sb.append(" (TODAY!)");
                else if (daysUntil <= 7) sb.append(" (in ").append(daysUntil).append(" days)");
            }
            sb.append("\n");
            @SuppressWarnings("unchecked")
            Map<String, String> handles = (Map<String, String>) c.getOrDefault("socialHandles", Map.of());
            if (!handles.isEmpty()) {
                sb.append("  Socials: ").append(handles.entrySet().stream()
                        .map(e -> e.getKey() + ": " + e.getValue())
                        .collect(Collectors.joining(", "))).append("\n");
            }
            String notes = (String) c.getOrDefault("notes", "");
            if (!notes.isBlank()) sb.append("  Notes: ").append(notes).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Remove a contact from tracking.")
    public String removeContact(
            @ToolParam(description = "Contact name to remove") String name) {
        String lower = name.toLowerCase().trim();
        boolean removed = contacts.removeIf(c -> ((String) c.get("name")).toLowerCase().contains(lower));
        if (removed) { save(CONTACTS_FILE, contacts); return "Removed contact: " + name; }
        return "Contact not found: " + name;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Birthday tracking
    // ═══════════════════════════════════════════════════════════════════════

    @Tool(description = "Check for upcoming birthdays in the next N days. "
            + "Use when the user asks 'any birthdays coming up?', 'who has a birthday soon?', "
            + "'birthday reminders', or as part of a morning briefing.")
    public String checkBirthdays(
            @ToolParam(description = "Number of days ahead to check (1-90, default 30)") double daysAhead) {
        int days = Math.max(1, Math.min(90, (int) daysAhead));
        notifier.notify("Checking birthdays in next " + days + " days...");

        List<String> upcoming = new ArrayList<>();
        List<String> today = new ArrayList<>();

        for (Map<String, Object> c : contacts) {
            String bday = (String) c.get("birthday");
            if (bday == null || bday.isBlank()) continue;

            long daysUntil = daysUntilBirthday(bday);
            String name = (String) c.get("name");
            int age = getUpcomingAge(bday);

            if (daysUntil == 0) {
                today.add(name + " turns " + age + " today!");
            } else if (daysUntil <= days) {
                LocalDate nextBday = getNextBirthday(bday);
                upcoming.add(name + " turns " + age + " on "
                        + nextBday.format(DISPLAY_FMT) + " (" + daysUntil + " days)");
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!today.isEmpty()) {
            sb.append("🎂 TODAY:\n");
            today.forEach(t -> sb.append("  • ").append(t).append("\n"));
            sb.append("\n");
        }
        if (!upcoming.isEmpty()) {
            sb.append("📅 Upcoming (next ").append(days).append(" days):\n");
            upcoming.forEach(u -> sb.append("  • ").append(u).append("\n"));
        }
        if (today.isEmpty() && upcoming.isEmpty()) {
            sb.append("No birthdays in the next ").append(days).append(" days.");
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Post tracking & mentions
    // ═══════════════════════════════════════════════════════════════════════

    @Tool(description = "Save an important social media post to track. "
            + "Use when the user says 'save this post', 'track this tweet', 'remember this post'. "
            + "Good for tracking announcements, job changes, life events from contacts.")
    public String trackPost(
            @ToolParam(description = "Who posted it (contact name or handle)") String author,
            @ToolParam(description = "Platform: twitter, instagram, facebook, linkedin, reddit, tiktok, other") String platform,
            @ToolParam(description = "Summary or content of the post") String content,
            @ToolParam(description = "URL of the post, or empty if unavailable") String url,
            @ToolParam(description = "Tags: comma-separated, e.g. 'job-change, announcement, milestone'") String tags) {
        notifier.notify("Tracking post from " + author);

        Map<String, Object> post = new LinkedHashMap<>();
        post.put("id", idGen.getAndIncrement());
        post.put("author", author.trim());
        post.put("platform", platform != null ? platform.trim() : "other");
        post.put("content", content.trim());
        post.put("url", url != null && !url.isBlank() ? url.trim() : null);
        post.put("tags", tags != null ? Arrays.stream(tags.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList()) : List.of());
        post.put("savedAt", System.currentTimeMillis());
        post.put("date", LocalDate.now().toString());
        trackedPosts.add(post);
        while (trackedPosts.size() > 500) trackedPosts.remove(0);
        save(POSTS_FILE, trackedPosts);

        return "Post tracked: " + author + " on " + platform + " — " + truncate(content, 80);
    }

    @Tool(description = "Log a mention of the user on social media. "
            + "Use when monitoring finds someone mentioning the user or their business.")
    public String logMention(
            @ToolParam(description = "Who mentioned you") String mentionedBy,
            @ToolParam(description = "Platform where the mention occurred") String platform,
            @ToolParam(description = "Content of the mention") String content,
            @ToolParam(description = "URL of the mention, or empty") String url,
            @ToolParam(description = "Sentiment: positive, neutral, negative") String sentiment) {
        notifier.notify("Logging mention from " + mentionedBy);

        Map<String, Object> mention = new LinkedHashMap<>();
        mention.put("id", idGen.getAndIncrement());
        mention.put("mentionedBy", mentionedBy.trim());
        mention.put("platform", platform != null ? platform.trim() : "unknown");
        mention.put("content", content.trim());
        mention.put("url", url != null && !url.isBlank() ? url.trim() : null);
        mention.put("sentiment", sentiment != null ? sentiment.trim() : "neutral");
        mention.put("timestamp", System.currentTimeMillis());
        mention.put("date", LocalDate.now().toString());
        mention.put("read", false);
        mentions.add(mention);
        while (mentions.size() > 500) mentions.remove(0);
        save(MENTIONS_FILE, mentions);

        return "Mention logged: " + mentionedBy + " on " + platform + " (" + sentiment + ")";
    }

    @Tool(description = "Show recent tracked posts from contacts, optionally filtered by person or tag. "
            + "Use when the user asks 'what have my contacts posted?', 'any updates from X?', "
            + "'show tracked posts'.")
    public String showTrackedPosts(
            @ToolParam(description = "Filter by contact name (or 'all' for everything)") String filter,
            @ToolParam(description = "Number of recent posts to show (1-50, default 10)") double count) {
        int n = Math.max(1, Math.min(50, (int) count));
        String lower = filter != null ? filter.toLowerCase().trim() : "all";

        List<Map<String, Object>> filtered;
        if ("all".equals(lower)) {
            filtered = new ArrayList<>(trackedPosts);
        } else {
            filtered = trackedPosts.stream()
                    .filter(p -> ((String) p.getOrDefault("author", "")).toLowerCase().contains(lower))
                    .collect(Collectors.toList());
        }

        if (filtered.isEmpty()) return "No tracked posts" + ("all".equals(lower) ? "." : " from " + filter + ".");

        int start = Math.max(0, filtered.size() - n);
        StringBuilder sb = new StringBuilder("Tracked Posts");
        if (!"all".equals(lower)) sb.append(" from ").append(filter);
        sb.append(" (showing ").append(Math.min(n, filtered.size())).append("):\n\n");

        for (int i = start; i < filtered.size(); i++) {
            Map<String, Object> p = filtered.get(i);
            sb.append("  ").append(p.get("author")).append(" [").append(p.get("platform")).append("] ")
                    .append(p.get("date")).append("\n");
            sb.append("    ").append(truncate((String) p.get("content"), 120)).append("\n");
            if (p.get("url") != null) sb.append("    ").append(p.get("url")).append("\n");
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) p.getOrDefault("tags", List.of());
            if (!tags.isEmpty()) sb.append("    Tags: ").append(String.join(", ", tags)).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Show recent mentions of the user across social media. "
            + "Use when the user asks 'any mentions?', 'who mentioned me?', 'social mentions'.")
    public String showMentions(
            @ToolParam(description = "Number of recent mentions to show (1-30, default 10)") double count) {
        int n = Math.max(1, Math.min(30, (int) count));
        if (mentions.isEmpty()) return "No mentions tracked yet.";

        int start = Math.max(0, mentions.size() - n);
        long unread = mentions.stream().filter(m -> !(Boolean) m.getOrDefault("read", false)).count();

        StringBuilder sb = new StringBuilder("Social Mentions (" + unread + " unread):\n\n");
        for (int i = start; i < mentions.size(); i++) {
            Map<String, Object> m = mentions.get(i);
            boolean read = (Boolean) m.getOrDefault("read", false);
            sb.append(read ? "  ○ " : "  ● ");
            sb.append(m.get("mentionedBy")).append(" [").append(m.get("platform")).append("] ")
                    .append(m.get("date"));
            String sentiment = (String) m.getOrDefault("sentiment", "neutral");
            if ("positive".equals(sentiment)) sb.append(" 👍");
            else if ("negative".equals(sentiment)) sb.append(" 👎");
            sb.append("\n    ").append(truncate((String) m.get("content"), 120)).append("\n");
            if (m.get("url") != null) sb.append("    ").append(m.get("url")).append("\n");
            m.put("read", true); // mark as read when shown
            sb.append("\n");
        }
        save(MENTIONS_FILE, mentions);
        return sb.toString();
    }

    @Tool(description = "Get a social media summary for a specific contact: their handles, "
            + "tracked posts, birthday status, and notes. "
            + "Use when the user asks 'tell me about X', 'X's social profile'.")
    public String contactSummary(
            @ToolParam(description = "Contact name") String name) {
        Map<String, Object> contact = findContact(name);
        if (contact == null) return "Contact not found: " + name;

        StringBuilder sb = new StringBuilder("Social Profile: " + contact.get("name") + "\n\n");
        String rel = (String) contact.getOrDefault("relationship", "");
        if (!rel.isBlank()) sb.append("  Relationship: ").append(rel).append("\n");

        String bday = (String) contact.get("birthday");
        if (bday != null) {
            long daysUntil = daysUntilBirthday(bday);
            int age = getUpcomingAge(bday);
            sb.append("  Birthday: ").append(bday);
            if (daysUntil == 0) sb.append(" (TODAY! Turning ").append(age).append(")");
            else sb.append(" (in ").append(daysUntil).append(" days, turning ").append(age).append(")");
            sb.append("\n");
        }

        @SuppressWarnings("unchecked")
        Map<String, String> handles = (Map<String, String>) contact.getOrDefault("socialHandles", Map.of());
        if (!handles.isEmpty()) {
            sb.append("  Social handles:\n");
            handles.forEach((p, h) -> sb.append("    • ").append(p).append(": ").append(h).append("\n"));
        }

        String notes = (String) contact.getOrDefault("notes", "");
        if (!notes.isBlank()) sb.append("  Notes: ").append(notes).append("\n");

        // Recent posts from this contact
        String lower = ((String) contact.get("name")).toLowerCase();
        List<Map<String, Object>> posts = trackedPosts.stream()
                .filter(p -> ((String) p.getOrDefault("author", "")).toLowerCase().contains(lower))
                .collect(Collectors.toList());
        if (!posts.isEmpty()) {
            sb.append("\n  Recent posts (").append(posts.size()).append("):\n");
            int start = Math.max(0, posts.size() - 5);
            for (int i = start; i < posts.size(); i++) {
                Map<String, Object> p = posts.get(i);
                sb.append("    [").append(p.get("platform")).append("] ")
                        .append(truncate((String) p.get("content"), 80)).append("\n");
            }
        }
        return sb.toString();
    }

    @Tool(description = "Generate a social monitoring report: birthday alerts, recent mentions, "
            + "post activity from close contacts. "
            + "Use for daily social briefings or when the user asks 'social update'.")
    public String socialReport() {
        notifier.notify("Generating social report...");
        StringBuilder sb = new StringBuilder("Social Monitor Report\n");
        sb.append("═".repeat(40)).append("\n\n");

        // Birthdays
        String birthdays = checkBirthdays(14);
        sb.append("🎂 BIRTHDAYS\n").append(birthdays).append("\n\n");

        // Unread mentions
        long unread = mentions.stream().filter(m -> !(Boolean) m.getOrDefault("read", false)).count();
        sb.append("📣 MENTIONS (").append(unread).append(" unread)\n");
        if (unread > 0) {
            mentions.stream()
                    .filter(m -> !(Boolean) m.getOrDefault("read", false))
                    .limit(5)
                    .forEach(m -> {
                        sb.append("  • ").append(m.get("mentionedBy")).append(" [").append(m.get("platform")).append("]: ")
                                .append(truncate((String) m.get("content"), 80)).append("\n");
                    });
        } else {
            sb.append("  No new mentions.\n");
        }
        sb.append("\n");

        // Recent posts from close contacts
        sb.append("📱 RECENT POSTS FROM CLOSE CONTACTS\n");
        Set<String> closeNames = contacts.stream()
                .filter(c -> {
                    String rel = (String) c.getOrDefault("relationship", "");
                    return "family".equals(rel) || "partner".equals(rel) || "close-friend".equals(rel);
                })
                .map(c -> ((String) c.get("name")).toLowerCase())
                .collect(Collectors.toSet());

        List<Map<String, Object>> closePosts = trackedPosts.stream()
                .filter(p -> {
                    String author = ((String) p.getOrDefault("author", "")).toLowerCase();
                    return closeNames.stream().anyMatch(author::contains);
                })
                .sorted((a, b) -> Long.compare(
                        ((Number) b.getOrDefault("savedAt", 0)).longValue(),
                        ((Number) a.getOrDefault("savedAt", 0)).longValue()))
                .limit(5)
                .collect(Collectors.toList());

        if (closePosts.isEmpty()) {
            sb.append("  No recent posts from close contacts.\n");
        } else {
            closePosts.forEach(p -> {
                sb.append("  • ").append(p.get("author")).append(" [").append(p.get("platform")).append("]: ")
                        .append(truncate((String) p.get("content"), 80)).append("\n");
            });
        }

        // Stats
        sb.append("\n📊 STATS\n");
        sb.append("  Contacts tracked: ").append(contacts.size()).append("\n");
        sb.append("  Posts tracked: ").append(trackedPosts.size()).append("\n");
        sb.append("  Total mentions: ").append(mentions.size()).append("\n");

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private Map<String, Object> findContact(String name) {
        String lower = name.toLowerCase().trim();
        return contacts.stream()
                .filter(c -> ((String) c.get("name")).toLowerCase().contains(lower))
                .findFirst().orElse(null);
    }

    private long daysUntilBirthday(String birthdayStr) {
        try {
            LocalDate bday = LocalDate.parse(birthdayStr, DATE_FMT);
            LocalDate today = LocalDate.now();
            LocalDate nextBday = bday.withYear(today.getYear());
            if (nextBday.isBefore(today)) nextBday = nextBday.plusYears(1);
            return ChronoUnit.DAYS.between(today, nextBday);
        } catch (Exception e) { return 999; }
    }

    private LocalDate getNextBirthday(String birthdayStr) {
        LocalDate bday = LocalDate.parse(birthdayStr, DATE_FMT);
        LocalDate today = LocalDate.now();
        LocalDate next = bday.withYear(today.getYear());
        if (next.isBefore(today)) next = next.plusYears(1);
        return next;
    }

    private int getUpcomingAge(String birthdayStr) {
        try {
            LocalDate bday = LocalDate.parse(birthdayStr, DATE_FMT);
            LocalDate nextBday = getNextBirthday(birthdayStr);
            return nextBday.getYear() - bday.getYear();
        } catch (Exception e) { return 0; }
    }

    private Map<String, String> parseSocialHandles(String handles) {
        Map<String, String> result = new LinkedHashMap<>();
        if (handles == null || handles.isBlank()) return result;
        for (String pair : handles.split(",")) {
            String[] parts = pair.trim().split(":", 2);
            if (parts.length == 2) {
                result.put(parts[0].trim().toLowerCase(), parts[1].trim());
            }
        }
        return result;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @SuppressWarnings("unchecked")
    private void load(Path file, List<Map<String, Object>> target) {
        if (Files.exists(file)) {
            try {
                target.addAll(mapper.readValue(file.toFile(), new TypeReference<List<Map<String, Object>>>() {}));
            } catch (IOException e) { log.warn("[SocialMonitor] Load failed for {}: {}", file.getFileName(), e.getMessage()); }
        }
    }

    private void save(Path file, List<Map<String, Object>> data) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) { log.error("[SocialMonitor] Save failed: {}", e.getMessage()); }
    }
}
