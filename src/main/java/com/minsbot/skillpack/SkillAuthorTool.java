package com.minsbot.skillpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Lets the LLM author its own skill packs at the user's request.
 *
 * <p>Triggered by phrasing like "make a skill that …", "create a skill for …",
 * "save this as a skill". Writes a {@code skill.md} into the user-writable
 * skill-packs root, then asks {@link SkillRegistry} to rescan so the new
 * skill lights up immediately — no restart required.
 *
 * <p>Why this lives here, not as part of {@link SkillPackTool}: that one is
 * read-only (list/invoke). Authoring deserves its own surface and its own
 * description so the LLM doesn't accidentally route a "use the X skill" ask
 * through the create path.
 */
@Component
public class SkillAuthorTool {

    private static final Logger log = LoggerFactory.getLogger(SkillAuthorTool.class);

    /** Same default the registry uses — the user-writable root. We never write
     *  into the bundled repo folder; that's read-only by convention. */
    @Value("${app.skill-packs.folder:#{systemProperties['user.home']}/mins_bot_data/skill_packs}")
    private String userSkillFolder;

    /** Slug rule: lowercase letters, digits, dashes. Keeps folder names safe
     *  on every filesystem and matches the convention of the bundled packs. */
    private static final java.util.regex.Pattern SLUG = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9\\-]{1,48}[a-z0-9]");

    /** Hard ceiling so a runaway prompt can't fill the disk with one giant body. */
    private static final int MAX_BODY_CHARS = 24_000;

    private final SkillRegistry registry;

    public SkillAuthorTool(SkillRegistry registry) {
        this.registry = registry;
    }

    @Tool(description =
            "Create a new SKILL.md-based skill pack on disk so future requests can use it. "
            + "Use when the user says things like 'make a skill that …', 'create a skill for …', "
            + "'save this workflow as a skill', 'turn this into a reusable skill'. "
            + "Writes ~/mins_bot_data/skill_packs/<name>/skill.md and rescans the registry — "
            + "the skill is usable immediately, no restart needed. "
            + "Refuses to overwrite an existing skill unless overwrite=true. "
            + "DO NOT use this to invoke or list existing skills — that's listSkillPacks / invokeSkillPack.")
    public String createSkillPack(
            @ToolParam(description = "Lowercase slug, dashes only — e.g. 'weekly-standup-notes', 'csv-to-chart'. "
                    + "1–50 chars, must start and end with a letter or digit.") String name,
            @ToolParam(description = "One-sentence description of what the skill does PLUS the trigger phrases "
                    + "(comma-separated). Example: 'Summarize a YouTube URL into 5 bullets. Trigger on \"summarize "
                    + "this video\", \"yt tldr\", \"what does this video say\".'") String description,
            @ToolParam(description = "Markdown body — the actual instructions the LLM will follow when this skill "
                    + "is invoked. Use ## headings for steps and guardrails. Reference @Tool methods by name "
                    + "(e.g. searchWeb, fetchPageText, produceDeliverable). Keep under 24 KB.") String body,
            @ToolParam(description = "Output extension if this skill produces a file: 'pdf', 'pptx', 'docx', 'md'. "
                    + "Empty string when the skill doesn't produce a file.", required = false) String output,
            @ToolParam(description = "Deliverable style if applicable: 'report', 'slides', 'memo', 'brief'. "
                    + "Empty string when the skill isn't a deliverable.", required = false) String format,
            @ToolParam(description = "Model id this skill prefers, e.g. 'gpt-5.1' or 'gpt-4o-mini'. "
                    + "Empty string to inherit the app default.", required = false) String model,
            @ToolParam(description = "Emoji to show in the Skills tab and pack list. Empty string for none.",
                    required = false) String emoji,
            @ToolParam(description = "Set true to overwrite an existing skill of the same name. Default false — "
                    + "prefer renaming to avoid clobbering the user's work.", required = false) Boolean overwrite
    ) {
        if (name == null || !SLUG.matcher(name.trim()).matches()) {
            return "Refused: skill name must be a 3–50 char lowercase slug (letters/digits/dashes), e.g. 'weekly-standup-notes'.";
        }
        if (description == null || description.isBlank()) {
            return "Refused: description is required — that's how the LLM (and the user) discover the skill.";
        }
        if (body == null || body.isBlank()) {
            return "Refused: body is required — without instructions there's nothing for the skill to do.";
        }
        if (body.length() > MAX_BODY_CHARS) {
            return "Refused: body is " + body.length() + " chars (cap is " + MAX_BODY_CHARS + "). "
                    + "Trim it or move detail into a references/<file>.md the skill loads on demand.";
        }

        Path root = Paths.get(userSkillFolder).toAbsolutePath();
        Path dir = root.resolve(name.trim());
        Path skillFile = dir.resolve("skill.md");

        boolean exists = Files.isRegularFile(skillFile);
        if (exists && !Boolean.TRUE.equals(overwrite)) {
            return "Refused: skill '" + name + "' already exists at " + skillFile
                    + ". Pass overwrite=true if you really mean to replace it, or pick a different name.";
        }

        try {
            Files.createDirectories(dir);
            String md = renderSkillMd(name.trim(), description.trim(),
                    nullIfBlank(emoji), nullIfBlank(output), nullIfBlank(format), nullIfBlank(model),
                    body.trim());
            Files.writeString(skillFile, md, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("[SkillAuthor] failed to write {}: {}", skillFile, e.getMessage());
            return "Failed to write skill file: " + e.getMessage();
        }

        int loaded = registry.rescan();
        log.info("[SkillAuthor] {} skill '{}' at {} (registry now has {} skills)",
                exists ? "overwrote" : "created", name, skillFile, loaded);

        return (exists ? "Updated" : "Created") + " skill '" + name + "' at " + skillFile
                + ". Registry rescanned (" + loaded + " skills loaded). "
                + "The user can invoke it now or manage it from the Skills tab.";
    }

    @Tool(description =
            "Delete a user-created skill pack. ONLY removes skills under the user-writable root "
            + "(~/mins_bot_data/skill_packs/) — bundled skills shipped with the app are protected. "
            + "Use when the user says 'delete the X skill', 'remove the skill for Y'. Always confirm with "
            + "the user before calling this.")
    public String deleteSkillPack(
            @ToolParam(description = "Exact skill name as returned by listSkillPacks.") String name
    ) {
        if (name == null || !SLUG.matcher(name.trim()).matches()) {
            return "Refused: invalid skill name.";
        }
        Path root = Paths.get(userSkillFolder).toAbsolutePath();
        Path dir = root.resolve(name.trim()).normalize();
        if (!dir.startsWith(root)) {
            return "Refused: path escapes the user skill-pack root.";
        }
        if (!Files.isDirectory(dir)) {
            return "No user skill called '" + name + "' at " + dir
                    + ". (Bundled skills can't be deleted; they live with the app install.)";
        }
        try {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
            }
        } catch (IOException e) {
            return "Failed to delete: " + e.getMessage();
        }
        int loaded = registry.rescan();
        return "Deleted skill '" + name + "'. Registry rescanned (" + loaded + " skills remain).";
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private static String renderSkillMd(String name, String description,
                                        String emoji, String output, String format, String model,
                                        String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append('\n');
        sb.append("description: ").append(yamlScalar(description)).append('\n');
        sb.append("metadata:\n");
        sb.append("  minsbot:\n");
        sb.append("    os: [\"windows\", \"darwin\", \"linux\"]\n");
        if (emoji != null)  sb.append("    emoji: ").append(yamlScalar(emoji)).append('\n');
        if (output != null) sb.append("    output: ").append(yamlScalar(output)).append('\n');
        if (format != null) sb.append("    format: ").append(yamlScalar(format)).append('\n');
        if (model != null)  sb.append("    model: ").append(yamlScalar(model)).append('\n');
        sb.append("---\n\n");
        sb.append(body);
        if (!body.endsWith("\n")) sb.append('\n');
        return sb.toString();
    }

    /** Quote a YAML scalar when it contains characters that confuse the parser
     *  (colons, hashes, leading symbols). Plain values stay unquoted for readability. */
    private static String yamlScalar(String s) {
        if (s == null) return "\"\"";
        boolean needsQuote = s.contains(":") || s.contains("#") || s.contains("\n")
                || s.startsWith("-") || s.startsWith("\"") || s.startsWith("'");
        if (!needsQuote) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
