package com.minsbot.skills.markdowntools;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MarkdownToolsService {

    private static final Pattern HEADING = Pattern.compile("^(#+)\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");

    public Map<String, Object> toc(String markdown, int maxDepth) {
        List<Map<String, Object>> headings = new ArrayList<>();
        String cleaned = CODE_BLOCK.matcher(markdown == null ? "" : markdown).replaceAll("");
        Matcher m = HEADING.matcher(cleaned);
        StringBuilder toc = new StringBuilder();
        while (m.find()) {
            int level = m.group(1).length();
            if (level > maxDepth) continue;
            String text = m.group(2).trim();
            String anchor = slugify(text);
            headings.add(Map.of("level", level, "text", text, "anchor", anchor));
            for (int i = 1; i < level; i++) toc.append("  ");
            toc.append("- [").append(text).append("](#").append(anchor).append(")\n");
        }
        return Map.of("toc", toc.toString(), "headings", headings);
    }

    public Map<String, Object> linkReport(String markdown) {
        List<Map<String, String>> links = new ArrayList<>();
        List<Map<String, String>> images = new ArrayList<>();
        if (markdown == null) markdown = "";
        Matcher lm = LINK.matcher(markdown);
        while (lm.find()) links.add(Map.of("text", lm.group(1), "url", lm.group(2)));
        Matcher im = IMAGE.matcher(markdown);
        while (im.find()) images.add(Map.of("alt", im.group(1), "url", im.group(2)));
        int external = (int) links.stream().filter(l -> l.get("url").startsWith("http")).count();
        int anchors = (int) links.stream().filter(l -> l.get("url").startsWith("#")).count();
        return Map.of(
                "linkCount", links.size(),
                "imageCount", images.size(),
                "externalLinks", external,
                "anchorLinks", anchors,
                "relativeLinks", links.size() - external - anchors,
                "links", links,
                "images", images
        );
    }

    public Map<String, Object> validateHeadings(String markdown) {
        if (markdown == null) markdown = "";
        String cleaned = CODE_BLOCK.matcher(markdown).replaceAll("");
        Matcher m = HEADING.matcher(cleaned);
        int h1Count = 0;
        List<String> issues = new ArrayList<>();
        int prevLevel = 0;
        int headingCount = 0;
        while (m.find()) {
            int level = m.group(1).length();
            String text = m.group(2).trim();
            headingCount++;
            if (level == 1) h1Count++;
            if (prevLevel > 0 && level - prevLevel > 1) {
                issues.add("Heading level jumps from H" + prevLevel + " to H" + level + " at: \"" + text + "\"");
            }
            prevLevel = level;
        }
        if (h1Count == 0) issues.add("No H1 heading found");
        if (h1Count > 1) issues.add("Multiple H1 headings (" + h1Count + ") — recommend one per document");
        return Map.of("headingCount", headingCount, "h1Count", h1Count, "issues", issues);
    }

    private static String slugify(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9\\s-]", "").trim().replaceAll("\\s+", "-");
    }
}
