package com.minsbot.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gift idea generator backed by a contacts file at ~/mins_bot_data/gift_contacts.txt.
 * Each contact is a section with interests, age, relationship, past gifts, and wish list.
 * The AI uses this data + budget to generate personalized gift suggestions.
 */
@Component
public class GiftIdeaTools {

    private static final Path CONTACTS_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "gift_contacts.txt");

    private static final Pattern SECTION_PATTERN = Pattern.compile("(^##\\s+.+$)", Pattern.MULTILINE);
    private static final Pattern CONTACT_PATTERN = Pattern.compile("(^#\\s+.+$)", Pattern.MULTILINE);

    private static final String DEFAULT_TEMPLATE = """
            # Gift Contacts
            Add people here so the bot can suggest personalized gifts.
            Use "Save contact" to add someone, then ask for gift ideas anytime.
            """;

    private final ToolExecutionNotifier notifier;

    public GiftIdeaTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    // ─── Contact management ─────────────────────────────────────────────────

    @Tool(description = "Save or update a contact for gift ideas. Stores their interests, age, "
            + "relationship to the user, and optional wish list. Use when the user says "
            + "'remember that my mom likes gardening', 'save gift info for John', etc.")
    public String saveContact(
            @ToolParam(description = "Contact name, e.g. 'Mom', 'John', 'Sarah'") String name,
            @ToolParam(description = "Relationship to user, e.g. 'mother', 'friend', 'coworker', 'partner'") String relationship,
            @ToolParam(description = "Interests and hobbies, comma-separated, e.g. 'gardening, cooking, mystery novels'") String interests,
            @ToolParam(description = "Age or age range, e.g. '35', '60s', 'teenager'. Use '-' if unknown") String age,
            @ToolParam(description = "Optional wish list items or notes, e.g. 'wants a Kindle, loves blue'. Use '-' if none") String notes) {
        notifier.notify("Saving contact: " + name + "...");
        try {
            ensureFileExists();
            String content = Files.readString(CONTACTS_PATH, StandardCharsets.UTF_8);

            String contactBlock = buildContactBlock(name.trim(), relationship, interests, age, notes);
            String updated = replaceOrAddContact(content, name.trim(), contactBlock);
            Files.writeString(CONTACTS_PATH, updated, StandardCharsets.UTF_8);

            return "Saved gift contact: " + name.trim()
                    + "\n  Relationship: " + relationship
                    + "\n  Interests: " + interests
                    + "\n  Age: " + age
                    + (notes != null && !notes.equals("-") ? "\n  Notes: " + notes : "");
        } catch (IOException e) {
            return "Failed to save contact: " + e.getMessage();
        }
    }

    @Tool(description = "Add past gift to a contact's history so you don't suggest duplicates. "
            + "Use when the user says 'I already gave Mom a Kindle last Christmas'.")
    public String addPastGift(
            @ToolParam(description = "Contact name") String name,
            @ToolParam(description = "Gift description, e.g. 'Kindle Paperwhite (Christmas 2025)'") String gift) {
        notifier.notify("Adding past gift for " + name + "...");
        try {
            ensureFileExists();
            String content = Files.readString(CONTACTS_PATH, StandardCharsets.UTF_8);
            String contactData = extractContact(content, name.trim());
            if (contactData == null) {
                return "Contact '" + name + "' not found. Save them first with saveContact.";
            }

            // Find the Past Gifts subsection and append
            String updated = appendToSubsection(content, name.trim(), "Past Gifts", gift.trim());
            Files.writeString(CONTACTS_PATH, updated, StandardCharsets.UTF_8);
            return "Added past gift for " + name + ": " + gift;
        } catch (IOException e) {
            return "Failed to add past gift: " + e.getMessage();
        }
    }

    @Tool(description = "List all saved gift contacts with their interests and relationship. "
            + "Use when the user asks 'who do I have saved for gifts?', 'show my gift contacts'.")
    public String listContacts() {
        notifier.notify("Loading gift contacts...");
        try {
            ensureFileExists();
            String content = Files.readString(CONTACTS_PATH, StandardCharsets.UTF_8);
            List<String> names = extractContactNames(content);
            if (names.isEmpty()) {
                return "No gift contacts saved yet. Use saveContact to add someone.";
            }

            StringBuilder sb = new StringBuilder("Gift Contacts (" + names.size() + "):\n\n");
            for (String cName : names) {
                String data = extractContact(content, cName);
                if (data == null) continue;
                String rel = extractField(data, "Relationship");
                String interests = extractField(data, "Interests");
                sb.append("  ").append(cName);
                if (rel != null) sb.append(" (").append(rel).append(")");
                if (interests != null) sb.append(" — ").append(interests);
                sb.append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "Failed to list contacts: " + e.getMessage();
        }
    }

    @Tool(description = "Get full details of a saved gift contact including interests, past gifts, "
            + "wish list, and notes. Use to review what you know about someone before suggesting gifts.")
    public String getContact(
            @ToolParam(description = "Contact name") String name) {
        notifier.notify("Looking up " + name + "...");
        try {
            ensureFileExists();
            String content = Files.readString(CONTACTS_PATH, StandardCharsets.UTF_8);
            String data = extractContact(content, name.trim());
            if (data == null) {
                return "Contact '" + name + "' not found. Saved contacts: "
                        + String.join(", ", extractContactNames(content));
            }
            return data;
        } catch (IOException e) {
            return "Failed to get contact: " + e.getMessage();
        }
    }

    @Tool(description = "Remove a contact from the gift contacts list.")
    public String removeContact(
            @ToolParam(description = "Contact name to remove") String name) {
        notifier.notify("Removing " + name + "...");
        try {
            ensureFileExists();
            String content = Files.readString(CONTACTS_PATH, StandardCharsets.UTF_8);
            String data = extractContact(content, name.trim());
            if (data == null) return "Contact '" + name + "' not found.";

            String heading = "# " + name.trim();
            String updated = removeContactBlock(content, heading);
            Files.writeString(CONTACTS_PATH, updated, StandardCharsets.UTF_8);
            return "Removed contact: " + name;
        } catch (IOException e) {
            return "Failed to remove contact: " + e.getMessage();
        }
    }

    // ─── Gift idea generation ───────────────────────────────────────────────

    @Tool(description = "Generate personalized gift ideas for a saved contact based on their interests, "
            + "age, relationship, past gifts (to avoid duplicates), and your budget. "
            + "Use when the user asks 'what should I get Mom for her birthday?', "
            + "'gift ideas for John under $50', 'Christmas gift for my partner'. "
            + "Returns structured suggestions the AI can then enhance with web search.")
    public String generateGiftIdeas(
            @ToolParam(description = "Contact name (must be saved already)") String name,
            @ToolParam(description = "Budget in dollars, e.g. 50, 100, 200. Use 0 for no budget limit") double budget,
            @ToolParam(description = "Occasion, e.g. 'birthday', 'Christmas', 'anniversary', 'just because'") String occasion) {
        notifier.notify("Generating gift ideas for " + name + "...");
        try {
            ensureFileExists();
            String content = Files.readString(CONTACTS_PATH, StandardCharsets.UTF_8);
            String contactData = extractContact(content, name.trim());
            if (contactData == null) {
                return "Contact '" + name + "' not found. Save them first with saveContact, "
                        + "or tell me their interests and I'll save them and suggest gifts.";
            }

            String interests = extractField(contactData, "Interests");
            String relationship = extractField(contactData, "Relationship");
            String age = extractField(contactData, "Age");
            String pastGifts = extractField(contactData, "Past Gifts");
            String notes = extractField(contactData, "Notes");

            StringBuilder prompt = new StringBuilder();
            prompt.append("GIFT IDEA REQUEST\n");
            prompt.append("═══════════════════\n\n");
            prompt.append("Recipient: ").append(name).append("\n");
            if (relationship != null) prompt.append("Relationship: ").append(relationship).append("\n");
            if (age != null && !age.equals("-")) prompt.append("Age: ").append(age).append("\n");
            if (interests != null) prompt.append("Interests: ").append(interests).append("\n");
            if (notes != null && !notes.equals("-")) prompt.append("Notes/Wish list: ").append(notes).append("\n");
            prompt.append("Occasion: ").append(occasion).append("\n");
            if (budget > 0) {
                prompt.append("Budget: $").append(String.format("%.0f", budget)).append("\n");
            } else {
                prompt.append("Budget: No limit\n");
            }
            if (pastGifts != null && !pastGifts.equals("-")) {
                prompt.append("\nPast gifts (AVOID these):\n").append(pastGifts).append("\n");
            }

            prompt.append("\n═══════════════════\n");
            prompt.append("Based on the above profile, suggest 5-8 thoughtful gift ideas.\n");
            prompt.append("For each idea include: name, estimated price, and WHY it fits this person.\n");
            prompt.append("Prioritize gifts that match their specific interests.\n");
            if (budget > 0) {
                prompt.append("All suggestions must be under $").append(String.format("%.0f", budget)).append(".\n");
            }
            prompt.append("After listing ideas, offer to search the web for real products and prices.");

            return prompt.toString();
        } catch (IOException e) {
            return "Failed to generate ideas: " + e.getMessage();
        }
    }

    @Tool(description = "Update a specific field for a saved contact (interests, age, relationship, notes). "
            + "Use when the user says 'update Mom's interests', 'add cooking to John's hobbies'.")
    public String updateContactField(
            @ToolParam(description = "Contact name") String name,
            @ToolParam(description = "Field to update: 'Interests', 'Age', 'Relationship', or 'Notes'") String field,
            @ToolParam(description = "New value for the field") String value) {
        notifier.notify("Updating " + name + "'s " + field + "...");
        try {
            ensureFileExists();
            String content = Files.readString(CONTACTS_PATH, StandardCharsets.UTF_8);
            String contactData = extractContact(content, name.trim());
            if (contactData == null) {
                return "Contact '" + name + "' not found.";
            }

            String updated = replaceSubsection(content, name.trim(), field.trim(), value.trim());
            Files.writeString(CONTACTS_PATH, updated, StandardCharsets.UTF_8);
            return "Updated " + name + "'s " + field + " to: " + value;
        } catch (IOException e) {
            return "Failed to update contact: " + e.getMessage();
        }
    }

    // ─── File helpers ───────────────────────────────────────────────────────

    private void ensureFileExists() throws IOException {
        if (!Files.exists(CONTACTS_PATH)) {
            Files.createDirectories(CONTACTS_PATH.getParent());
            Files.writeString(CONTACTS_PATH, DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
        }
    }

    private String buildContactBlock(String name, String relationship, String interests,
                                     String age, String notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(name).append("\n");
        sb.append("## Relationship\n").append(safe(relationship)).append("\n");
        sb.append("## Interests\n").append(safe(interests)).append("\n");
        sb.append("## Age\n").append(safe(age)).append("\n");
        sb.append("## Past Gifts\n-\n");
        sb.append("## Notes\n").append(safe(notes)).append("\n");
        return sb.toString();
    }

    private String safe(String s) {
        return (s == null || s.isBlank()) ? "-" : s.trim();
    }

    private String replaceOrAddContact(String content, String name, String block) {
        String text = normalize(content);
        String heading = "# " + name;

        int start = findContactStart(text, heading);
        if (start >= 0) {
            int end = findNextContactStart(text, start + heading.length());
            if (end < 0) end = text.length();
            String before = text.substring(0, start);
            String after = text.substring(end);
            return before + block + "\n" + after;
        }
        // Not found — append
        return text.trim() + "\n\n" + block;
    }

    private String removeContactBlock(String content, String heading) {
        String text = normalize(content);
        int start = findContactStart(text, heading);
        if (start < 0) return text;
        int end = findNextContactStart(text, start + heading.length());
        if (end < 0) end = text.length();
        String before = text.substring(0, start);
        String after = text.substring(end);
        return (before + after).replaceAll("\n{3,}", "\n\n").trim() + "\n";
    }

    private String extractContact(String content, String name) {
        String text = normalize(content);
        String heading = "# " + name;
        int start = findContactStart(text, heading);
        if (start < 0) return null;
        int end = findNextContactStart(text, start + heading.length());
        if (end < 0) end = text.length();
        return text.substring(start, end).trim();
    }

    private List<String> extractContactNames(String content) {
        String text = normalize(content);
        List<String> names = new ArrayList<>();
        Matcher m = CONTACT_PATTERN.matcher(text);
        while (m.find()) {
            String line = m.group(1).trim();
            // Only single # (not ## subsections), and skip the file header
            if (!line.startsWith("## ") && !line.equals("# Gift Contacts")) {
                names.add(line.substring(2).trim());
            }
        }
        return names;
    }

    private String extractField(String contactData, String fieldName) {
        String heading = "## " + fieldName;
        int start = contactData.indexOf(heading);
        if (start < 0) return null;
        int bodyStart = contactData.indexOf('\n', start);
        if (bodyStart < 0) return null;
        bodyStart++;
        // Find next ## or end
        Matcher m = SECTION_PATTERN.matcher(contactData);
        m.region(bodyStart, contactData.length());
        int end = m.find() ? m.start() : contactData.length();
        String value = contactData.substring(bodyStart, end).trim();
        return value.isEmpty() || value.equals("-") ? null : value;
    }

    private String appendToSubsection(String content, String contactName,
                                       String subsection, String newLine) {
        String text = normalize(content);
        String contactHeading = "# " + contactName;
        int contactStart = findContactStart(text, contactHeading);
        if (contactStart < 0) return text;

        int contactEnd = findNextContactStart(text, contactStart + contactHeading.length());
        if (contactEnd < 0) contactEnd = text.length();

        String contactBlock = text.substring(contactStart, contactEnd);
        String subHeading = "## " + subsection;
        int subStart = contactBlock.indexOf(subHeading);
        if (subStart < 0) {
            // Add the subsection
            String updatedBlock = contactBlock.trim() + "\n" + subHeading + "\n" + newLine + "\n";
            return text.substring(0, contactStart) + updatedBlock + "\n" + text.substring(contactEnd);
        }

        int bodyStart = contactBlock.indexOf('\n', subStart) + 1;
        Matcher m = SECTION_PATTERN.matcher(contactBlock);
        m.region(bodyStart, contactBlock.length());
        int nextSub = m.find() ? m.start() : contactBlock.length();

        String currentBody = contactBlock.substring(bodyStart, nextSub).trim();
        String updatedBody;
        if (currentBody.equals("-") || currentBody.isEmpty()) {
            updatedBody = newLine;
        } else {
            updatedBody = currentBody + "\n" + newLine;
        }

        String updatedBlock = contactBlock.substring(0, bodyStart)
                + updatedBody + "\n"
                + contactBlock.substring(nextSub);

        return text.substring(0, contactStart) + updatedBlock + text.substring(contactEnd);
    }

    private String replaceSubsection(String content, String contactName,
                                      String subsection, String newValue) {
        String text = normalize(content);
        String contactHeading = "# " + contactName;
        int contactStart = findContactStart(text, contactHeading);
        if (contactStart < 0) return text;

        int contactEnd = findNextContactStart(text, contactStart + contactHeading.length());
        if (contactEnd < 0) contactEnd = text.length();

        String contactBlock = text.substring(contactStart, contactEnd);
        String subHeading = "## " + subsection;
        int subStart = contactBlock.indexOf(subHeading);
        if (subStart < 0) {
            // Add subsection
            String updatedBlock = contactBlock.trim() + "\n" + subHeading + "\n" + newValue + "\n";
            return text.substring(0, contactStart) + updatedBlock + "\n" + text.substring(contactEnd);
        }

        int bodyStart = contactBlock.indexOf('\n', subStart) + 1;
        Matcher m = SECTION_PATTERN.matcher(contactBlock);
        m.region(bodyStart, contactBlock.length());
        int nextSub = m.find() ? m.start() : contactBlock.length();

        String updatedBlock = contactBlock.substring(0, bodyStart)
                + newValue + "\n"
                + contactBlock.substring(nextSub);

        return text.substring(0, contactStart) + updatedBlock + text.substring(contactEnd);
    }

    private int findContactStart(String text, String heading) {
        int i = 0;
        while (true) {
            int start = text.indexOf(heading, i);
            if (start < 0) return -1;
            // Must be at line start and followed by newline (not ## subsection)
            boolean atLineStart = start == 0 || text.charAt(start - 1) == '\n';
            int afterHeading = start + heading.length();
            boolean followedByNewline = afterHeading >= text.length() || text.charAt(afterHeading) == '\n';
            // Make sure it's not a ## subsection (single # only)
            boolean notSubsection = !heading.startsWith("## ");
            if (atLineStart && followedByNewline && notSubsection) {
                return start;
            }
            i = start + 1;
        }
    }

    private int findNextContactStart(String text, int from) {
        Matcher m = CONTACT_PATTERN.matcher(text);
        m.region(from, text.length());
        while (m.find()) {
            String match = m.group(1);
            // Only single # (not ##)
            if (!match.startsWith("## ")) {
                return m.start();
            }
        }
        return -1;
    }

    private String normalize(String text) {
        String t = text.replace("\r\n", "\n").replace("\r", "\n");
        if (!t.endsWith("\n")) t += "\n";
        return t;
    }
}
