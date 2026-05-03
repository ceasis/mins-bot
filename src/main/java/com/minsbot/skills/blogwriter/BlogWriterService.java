package com.minsbot.skills.blogwriter;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Drafts a markdown article skeleton from a primary keyword + supporting
 * keywords. Includes meta tags, H2/H3 outline, intro hook, FAQ schema,
 * internal-link suggestions, and a CTA. NOT an LLM-written article — it's a
 * structured starting point an operator (or downstream LLM) finishes.
 */
@Service
public class BlogWriterService {

    private final BlogWriterConfig.BlogWriterProperties props;
    private Path dir;

    public BlogWriterService(BlogWriterConfig.BlogWriterProperties props) { this.props = props; }

    @PostConstruct
    void init() throws IOException {
        dir = Paths.get(props.getStorageDir());
        Files.createDirectories(dir);
    }

    public Map<String, Object> draft(String primaryKeyword, List<String> supportingKeywords,
                                     String audience, String productCta, List<String> internalLinks) throws IOException {
        if (primaryKeyword == null || primaryKeyword.isBlank())
            throw new IllegalArgumentException("primaryKeyword required");
        if (supportingKeywords == null) supportingKeywords = List.of();
        if (internalLinks == null) internalLinks = List.of();
        if (audience == null) audience = "readers";

        String title = titleCase(primaryKeyword);
        String slug = primaryKeyword.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", "").trim().replaceAll("\\s+", "-");
        String metaTitle = title + " — A Practical Guide";
        String metaDesc = "Everything you need to know about " + primaryKeyword
                + ". Includes examples, comparisons, and a quick checklist.";
        if (metaDesc.length() > 155) metaDesc = metaDesc.substring(0, 152) + "...";

        StringBuilder md = new StringBuilder();
        md.append("---\n");
        md.append("title: \"").append(metaTitle).append("\"\n");
        md.append("slug: ").append(slug).append("\n");
        md.append("description: \"").append(metaDesc).append("\"\n");
        md.append("date: ").append(LocalDate.now()).append("\n");
        md.append("keywords: [").append(String.join(", ",
                concat(List.of(primaryKeyword), supportingKeywords))).append("]\n");
        md.append("audience: ").append(audience).append("\n");
        md.append("---\n\n");

        md.append("# ").append(title).append(": A Practical Guide for ").append(audience).append("\n\n");
        md.append("> TL;DR — write 2 sentences here that summarize the article. Most readers stop here, so make it count.\n\n");

        md.append("## Why ").append(primaryKeyword).append(" matters right now\n\n");
        md.append("Open with a concrete pain point or recent shift. 2-3 paragraphs. End with a transition to the meat.\n\n");

        md.append("## What ").append(primaryKeyword).append(" actually is\n\n");
        md.append("Define the term. If you're competing for the keyword, this section gets featured-snippet treatment, so keep the definition <50 words.\n\n");

        if (!supportingKeywords.isEmpty()) {
            md.append("## Key concepts\n\n");
            for (String k : supportingKeywords) {
                md.append("### ").append(titleCase(k)).append("\n\n");
                md.append("Explain ").append(k).append(" in 1-2 paragraphs. Include an example.\n\n");
            }
        }

        md.append("## How to do it (step-by-step)\n\n");
        md.append("1. **Step one** — what to do first.\n");
        md.append("2. **Step two** — the tricky part.\n");
        md.append("3. **Step three** — how to verify it worked.\n\n");

        md.append("## Common mistakes\n\n");
        md.append("- Mistake 1 — why people make it, what to do instead.\n");
        md.append("- Mistake 2 — same.\n");
        md.append("- Mistake 3 — same.\n\n");

        md.append("## ").append(title).append(" vs alternatives\n\n");
        md.append("| Option | Best for | Cost | Drawback |\n");
        md.append("|---|---|---|---|\n");
        md.append("| ").append(title).append(" | ... | ... | ... |\n");
        md.append("| Alternative 1 | ... | ... | ... |\n");
        md.append("| Alternative 2 | ... | ... | ... |\n\n");

        md.append("## FAQ\n\n");
        for (String k : concat(List.of(primaryKeyword), supportingKeywords)) {
            md.append("### Is ").append(k).append(" worth it?\n\n").append("Short answer here.\n\n");
        }
        md.append("### How long does it take to see results?\n\n").append("Short answer.\n\n");

        if (!internalLinks.isEmpty()) {
            md.append("## Related reading\n\n");
            for (String link : internalLinks) md.append("- [").append(link).append("](").append(link).append(")\n");
            md.append("\n");
        }

        md.append("## Wrap up\n\n");
        md.append("Recap the 3 main takeaways. ");
        if (productCta != null && !productCta.isBlank()) md.append(productCta).append("\n");
        else md.append("Then a soft CTA to your product or newsletter.\n");

        String filename = LocalDate.now() + "-" + slug + ".md";
        Path file = dir.resolve(filename);
        Files.writeString(file, md.toString());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "blogwriter");
        result.put("primaryKeyword", primaryKeyword);
        result.put("slug", slug);
        result.put("title", metaTitle);
        result.put("metaDescription", metaDesc);
        result.put("wordCountEstimate", md.toString().split("\\s+").length);
        result.put("storedAt", file.toAbsolutePath().toString());
        result.put("filename", filename);
        result.put("markdown", md.toString());
        return result;
    }

    public List<String> list() throws IOException {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted(Comparator.reverseOrder()).toList();
        }
    }

    private static String titleCase(String s) {
        String[] parts = s.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
            sb.append(" ");
        }
        return sb.toString().trim();
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a); out.addAll(b); return out;
    }
}
