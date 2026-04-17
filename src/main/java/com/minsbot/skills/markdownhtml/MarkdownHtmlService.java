package com.minsbot.skills.markdownhtml;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MarkdownHtmlService {

    public String mdToHtml(String md) {
        if (md == null) return "";
        String[] lines = md.split("\\r?\\n");
        StringBuilder html = new StringBuilder();
        boolean inCode = false;
        boolean inList = false;
        StringBuilder paragraph = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("```")) {
                if (inCode) { html.append("</code></pre>\n"); inCode = false; }
                else { flushPara(paragraph, html); html.append("<pre><code>"); inCode = true; }
                continue;
            }
            if (inCode) { html.append(escape(line)).append("\n"); continue; }

            Matcher h = Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(line);
            if (h.matches()) {
                flushPara(paragraph, html);
                if (inList) { html.append("</ul>\n"); inList = false; }
                int level = h.group(1).length();
                html.append("<h").append(level).append(">").append(inline(h.group(2))).append("</h").append(level).append(">\n");
                continue;
            }
            Matcher li = Pattern.compile("^[-*]\\s+(.+)$").matcher(line);
            if (li.matches()) {
                flushPara(paragraph, html);
                if (!inList) { html.append("<ul>\n"); inList = true; }
                html.append("  <li>").append(inline(li.group(1))).append("</li>\n");
                continue;
            }
            if (inList) { html.append("</ul>\n"); inList = false; }
            if (line.isBlank()) { flushPara(paragraph, html); continue; }
            if (paragraph.length() > 0) paragraph.append(' ');
            paragraph.append(line);
        }
        flushPara(paragraph, html);
        if (inList) html.append("</ul>\n");
        if (inCode) html.append("</code></pre>\n");
        return html.toString().trim();
    }

    public String htmlToMd(String html) {
        if (html == null) return "";
        String s = html;
        // Convert headings
        for (int i = 6; i >= 1; i--) {
            s = s.replaceAll("(?is)<h" + i + "[^>]*>(.*?)</h" + i + ">", "\n" + "#".repeat(i) + " $1\n");
        }
        // Bold/italic/code
        s = s.replaceAll("(?is)<(strong|b)[^>]*>(.*?)</\\1>", "**$2**");
        s = s.replaceAll("(?is)<(em|i)[^>]*>(.*?)</\\1>", "*$2*");
        s = s.replaceAll("(?is)<code[^>]*>(.*?)</code>", "`$1`");
        // Links
        s = s.replaceAll("(?is)<a\\s+[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", "[$2]($1)");
        // Images
        s = s.replaceAll("(?is)<img[^>]*alt=\"([^\"]*)\"[^>]*src=\"([^\"]+)\"[^>]*/?>", "![$1]($2)");
        s = s.replaceAll("(?is)<img[^>]*src=\"([^\"]+)\"[^>]*alt=\"([^\"]*)\"[^>]*/?>", "![$2]($1)");
        // Lists (basic)
        s = s.replaceAll("(?is)<li[^>]*>(.*?)</li>", "- $1");
        // Paragraphs and breaks
        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)<p[^>]*>", "\n");
        s = s.replaceAll("(?is)</p>", "\n");
        // Strip remaining tags
        s = s.replaceAll("<[^>]+>", "");
        // Entities
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        // Collapse blank lines
        s = s.replaceAll("\\n{3,}", "\n\n").trim();
        return s;
    }

    private static void flushPara(StringBuilder p, StringBuilder html) {
        if (p.length() > 0) { html.append("<p>").append(inline(p.toString())).append("</p>\n"); p.setLength(0); }
    }

    private static String inline(String s) {
        s = escape(s);
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>");
        s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        s = s.replaceAll("!\\[([^\\]]*)\\]\\(([^)]+)\\)", "<img src=\"$2\" alt=\"$1\">");
        s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        return s;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
