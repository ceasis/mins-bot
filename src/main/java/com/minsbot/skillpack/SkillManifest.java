package com.minsbot.skillpack;

import java.nio.file.Path;
import java.util.List;

/**
 * Parsed SKILL.md manifest — SKILL.md format. Every skill folder
 * on disk has one {@code SKILL.md} with YAML frontmatter and a markdown body.
 *
 * <p>The YAML carries enough metadata that Mins Bot can decide, <em>without
 * loading the body into context</em>, whether the skill applies to the current
 * platform, whether its prerequisites are met, and how to install it if not.</p>
 */
public record SkillManifest(
        String name,
        String description,
        String homepage,
        String emoji,

        /** Platform gate. Empty list = runs on any OS. Known values: "darwin", "linux", "windows". */
        List<String> osList,

        /** All these CLI binaries must be discoverable on PATH. */
        List<String> requiredBins,

        /** At least one of these CLI binaries must be on PATH. */
        List<String> anyOfBins,

        /** All these environment variables must be set (non-empty). */
        List<String> requiredEnv,

        /** The "primary" env var this skill consumes — used for UI hints. */
        String primaryEnv,

        /** Ordered install strategies; the UI offers the first one that is compatible. */
        List<InstallRecipe> installRecipes,

        /** Absolute path to the skill folder (parent of SKILL.md). */
        Path folder,

        /** Raw markdown body — everything after the closing YAML {@code ---}. Loaded on demand. */
        String body,

        /** Optional: output extension this skill produces (e.g. "pdf", "pptx", "docx"). null when n/a. */
        String output,

        /** Optional: deliverable style this skill targets (e.g. "report", "slides", "memo"). null when n/a. */
        String format,

        /** Optional: model id this skill prefers (e.g. "gpt-5.1", "gpt-4o-mini"). null = use app default. */
        String model,

        /** Optional per-skill Playwright visibility override. {@code true} =
         *  show the browser window during this skill's runs (debug / demo);
         *  {@code false} = force headless even if the global default is on;
         *  {@code null} = inherit {@code app.playwright.headless}. Read from
         *  {@code metadata.minsbot.playwright.show-browser} in SKILL.md. */
        Boolean showPlaywrightBrowser,

        /** Format keywords that route to this skill (e.g. ["word", "docx",
         *  "word doc", "word document"] for the docx skill). The deliverable
         *  intent interceptor builds its regex from the union of every
         *  installed skill's keywords, so adding a new format-skill requires
         *  zero Java changes — just declare keywords in
         *  {@code metadata.minsbot.triggers.keywords}. */
        List<String> triggerKeywords
) {
    public boolean supportsOs(String osId) {
        if (osList == null || osList.isEmpty()) return true;
        return osList.stream().anyMatch(o -> o.equalsIgnoreCase(osId));
    }

    /**
     * One install strategy inside {@code metadata.minsbot.install}. Kinds seen so far:
     * {@code brew}, {@code node} (npm global), {@code pip}, {@code cargo}, {@code manual}.
     */
    public record InstallRecipe(
            String id,
            String kind,
            /** {@code formula} (brew) or {@code package} (node/pip/cargo) — whichever the kind uses. */
            String packageName,
            List<String> bins,
            String label
    ) {}
}
