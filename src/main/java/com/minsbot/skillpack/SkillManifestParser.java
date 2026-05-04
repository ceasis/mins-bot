package com.minsbot.skillpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reads a {@code SKILL.md} file and produces a {@link SkillManifest}.
 *
 * <p>Some authors use pure YAML; others use JSON-in-YAML (flow style) for the
 * {@code metadata.minsbot} block — SnakeYAML handles both in one pass.</p>
 */
final class SkillManifestParser {

    private static final Logger log = LoggerFactory.getLogger(SkillManifestParser.class);

    /** Returns {@code null} and logs if the file isn't a valid SKILL.md (parse error, missing frontmatter, missing name). */
    @SuppressWarnings("unchecked")
    static SkillManifest parse(Path skillMd) {
        if (!Files.isRegularFile(skillMd)) return null;
        String raw;
        try {
            raw = Files.readString(skillMd);
        } catch (IOException e) {
            log.warn("[SkillParser] can't read {}: {}", skillMd, e.getMessage());
            return null;
        }
        // Frontmatter lives between the first two '---' lines.
        if (!raw.startsWith("---")) {
            log.debug("[SkillParser] {} has no YAML frontmatter", skillMd);
            return null;
        }
        int end = raw.indexOf("\n---", 3);
        if (end < 0) {
            log.debug("[SkillParser] {} frontmatter not closed", skillMd);
            return null;
        }
        String yaml = raw.substring(3, end).trim();
        String body = raw.substring(end + 4).replaceFirst("^[\\r\\n]+", "");

        Map<String, Object> top;
        try {
            top = new Yaml().load(yaml);
        } catch (Exception e) {
            log.warn("[SkillParser] YAML parse failed in {}: {}", skillMd, e.getMessage());
            return null;
        }
        if (top == null) return null;

        String name = str(top.get("name"));
        String description = str(top.get("description"));
        if (name == null || name.isBlank()) {
            log.warn("[SkillParser] {} has no name", skillMd);
            return null;
        }
        String homepage = str(top.get("homepage"));

        Map<String, Object> meta = asMap(top.get("metadata"));
        Map<String, Object> oc = asMap(meta.get("minsbot"));

        String emoji = str(oc.get("emoji"));
        List<String> osList = strList(oc.get("os"));

        Map<String, Object> requires = asMap(oc.get("requires"));
        List<String> requiredBins = strList(requires.get("bins"));
        List<String> anyOfBins = strList(requires.get("anyBins"));
        List<String> requiredEnv = strList(requires.get("env"));
        String primaryEnv = str(oc.get("primaryEnv"));

        List<SkillManifest.InstallRecipe> installs = new ArrayList<>();
        Object installRaw = oc.get("install");
        if (installRaw instanceof List<?> items) {
            for (Object item : items) {
                Map<String, Object> r = asMap(item);
                String id = str(r.get("id"));
                String kind = str(r.get("kind"));
                // formula (brew) OR package (node/pip/cargo) — accept either key
                String pkg = firstNonBlank(str(r.get("package")), str(r.get("formula")));
                List<String> bins = strList(r.get("bins"));
                String label = str(r.get("label"));
                if (kind != null && !kind.isBlank()) {
                    installs.add(new SkillManifest.InstallRecipe(id, kind, pkg, bins, label));
                }
            }
        }

        // Optional deliverable hints — let a skill declare its output format,
        // deliverable style, and preferred model so the interceptor can dispatch
        // through the right path without LLM tool-pick guesswork.
        String output = str(oc.get("output"));
        String format = str(oc.get("format"));
        String model = str(oc.get("model"));

        // Per-skill Playwright visibility override:
        //   metadata.minsbot.playwright.show-browser: true|false
        // Absent = inherit the global app.playwright.headless default.
        Boolean showPwBrowser = null;
        Map<String, Object> pw = asMap(oc.get("playwright"));
        Object showRaw = pw.get("show-browser");
        if (showRaw instanceof Boolean b) {
            showPwBrowser = b;
        } else if (showRaw != null) {
            String s = String.valueOf(showRaw).trim().toLowerCase();
            if ("true".equals(s) || "yes".equals(s))      showPwBrowser = Boolean.TRUE;
            else if ("false".equals(s) || "no".equals(s)) showPwBrowser = Boolean.FALSE;
        }

        // Per-skill format keywords for the deliverable interceptor:
        //   metadata.minsbot.triggers.keywords: ["word", "docx", "word doc"]
        // Empty/missing → skill is invisible to the interceptor's keyword regex
        // (it can still be invoked via invokeSkillPack).
        List<String> triggerKeywords = List.of();
        Map<String, Object> triggers = asMap(oc.get("triggers"));
        Object kwRaw = triggers.get("keywords");
        if (kwRaw != null) triggerKeywords = strList(kwRaw);

        return new SkillManifest(
                name, description, homepage, emoji,
                osList, requiredBins, anyOfBins, requiredEnv, primaryEnv,
                Collections.unmodifiableList(installs),
                skillMd.getParent(),
                body,
                output, format, model, showPwBrowser, triggerKeywords
        );
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private static String str(Object o) {
        return (o == null) ? null : String.valueOf(o).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object x : list) if (x != null) out.add(String.valueOf(x));
            return Collections.unmodifiableList(out);
        }
        if (o instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return Collections.emptyList();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b != null && !b.isBlank()) ? b : null;
    }

    private SkillManifestParser() {}
}
