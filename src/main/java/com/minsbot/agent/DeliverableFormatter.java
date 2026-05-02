package com.minsbot.agent;

import com.minsbot.agent.tools.PdfTools;
import com.minsbot.agent.tools.WordDocTools;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Rectangle;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the markdown deliverable produced by {@link DeliverableExecutor}
 * into the user's target file format. The planning/critique loop already
 * tailored the markdown structure to the chosen <em>style</em> (report / memo
 * / brief / slides); this class is a pure structural conversion — it does NOT
 * call the LLM.
 *
 * <ul>
 *   <li>{@code md}    — pass-through (FINAL.md is the deliverable)</li>
 *   <li>{@code pdf}   — delegate to {@link PdfTools#createPdfDocument}</li>
 *   <li>{@code docx}  — delegate to {@link WordDocTools#createWordDocument}</li>
 *   <li>{@code pptx}  — Apache POI XSLF, one slide per H2 section</li>
 * </ul>
 */
@Service
public class DeliverableFormatter {

    private static final Logger log = LoggerFactory.getLogger(DeliverableFormatter.class);

    private final PdfTools pdfTools;
    private final WordDocTools wordDocTools;

    @org.springframework.beans.factory.annotation.Value("${app.web-search.serper-api-key:}")
    private String serperApiKey;

    /** Optional. When present, PDF rendering goes through HTML+Chromium —
     *  proper image rendering, real CSS layout, all CDN headers handled by
     *  the browser. PDFBox path is the fallback when Playwright isn't ready. */
    @Autowired(required = false)
    private com.minsbot.agent.tools.PlaywrightService playwright;

    /** Surfaces image-search queries + picked URLs to the chat status feed
     *  so the user can audit relevance ("why is there a telescope photo on
     *  the MacBook slide?"). Optional — formatter still works without it. */
    @Autowired(required = false)
    private com.minsbot.agent.tools.ToolExecutionNotifier notifier;

    @Autowired
    public DeliverableFormatter(PdfTools pdfTools, WordDocTools wordDocTools) {
        this.pdfTools = pdfTools;
        this.wordDocTools = wordDocTools;
    }

    /**
     * Convert {@code markdownPath}'s content to {@code output} format and write
     * to a sibling file in the same directory. Returns the new file path,
     * or the original markdown path if conversion is unsupported / fails.
     */
    public Path convert(Path markdownPath, String output, String goalAsTitle) {
        if (markdownPath == null) return null;
        String fmt = (output == null) ? "md" : output.trim().toLowerCase();
        if (fmt.isEmpty() || fmt.equals("md") || fmt.equals("markdown")) {
            return markdownPath;
        }
        try {
            String md = java.nio.file.Files.readString(markdownPath);
            String title = (goalAsTitle == null || goalAsTitle.isBlank())
                    ? "Deliverable" : goalAsTitle.trim();
            // Strip any leading H1 from the body — it'll become the doc title instead.
            String body = stripLeadingH1(md);
            // SCRAP all LLM-emitted image references. The synthesizer hallucinates
            // image URLs ~80% of the time (vendor /wp-content/uploads/ patterns
            // that 404, guessed CDN paths, etc.). Don't even bother verifying.
            // Strip everything image-shaped, then source images solely from
            // Serper's Google Images results keyed on section headings.
            body = stripAllImageRefs(body);
            // Inject images into every section that warrants one. Skip for raw md.
            // The task's images/ subfolder lives next to the markdown so the user
            // can audit every image the bot picked (and replace any they dislike).
            Path imagesDir = markdownPath.getParent() == null
                    ? null : markdownPath.getParent().resolve("images");
            if (imagesDir != null) {
                try { java.nio.file.Files.createDirectories(imagesDir); }
                catch (Exception ignored) {}
            }
            if (!"md".equalsIgnoreCase(fmt) && !"markdown".equalsIgnoreCase(fmt)) {
                body = injectImagesForBareSections(body, imagesDir);
            }
            // Final scrub: kill any LLM-emitted "Hero image URL:" lines, bracketed
            // "embed failed" debug strings, and stray H1s the synthesizer wrote
            // mid-body. The synthesize prompt forbids these but the model still
            // smuggles them through about a third of the time.
            body = scrubLeftoverPlaceholders(body);

            return switch (fmt) {
                case "pdf"  -> writePdf(markdownPath, title, body);
                case "doc", "docx", "word" -> writeDocx(markdownPath, title, body);
                case "ppt", "pptx", "slides", "deck" -> writePptx(markdownPath, title, body);
                default -> markdownPath; // unknown → leave as md
            };
        } catch (Exception e) {
            log.warn("[Formatter] convert {} → {} failed: {}", markdownPath, fmt, e.getMessage());
            return markdownPath;
        }
    }

    // ─── Per-format writers ──────────────────────────────────────────────

    private Path writePdf(Path mdPath, String title, String body) {
        Path out = sibling(mdPath, ".pdf");
        // Primary path: render markdown → styled HTML → Chromium → PDF.
        // Browser fetches images with real CDN headers, decodes any format
        // (webp/avif/progressive JPEG/CMYK), and lays out CSS properly. No
        // ImageIO, no magic-byte sniffing, no PDFBox aspect-ratio drift.
        if (playwright != null) {
            try {
                String html = markdownToStyledHtml(title, body);
                String result = playwright.renderHtmlStringToPdf(
                        html, out.toAbsolutePath().toString(), "Letter", false);
                log.info("[Formatter] pdf (chromium): {} → {}", mdPath.getFileName(), result);
                return out;
            } catch (Exception e) {
                log.warn("[Formatter] chromium PDF render failed, falling back to PDFBox: {}",
                        e.getMessage());
            }
        }
        // Fallback: PDFBox-based renderer.
        String result = pdfTools.createPdfDocument(out.toAbsolutePath().toString(), title, body);
        log.info("[Formatter] pdf (pdfbox-fallback): {} → {}", mdPath.getFileName(), result);
        return out;
    }

    // ─── Markdown → styled HTML (for Chromium-based PDF rendering) ───────

    /**
     * Convert the deliverable markdown into a self-contained HTML document with
     * embedded CSS. Handles the markdown shapes the synthesizer emits:
     * H1/H2/H3 headings, paragraphs, bullets, bold, italic, links, images,
     * horizontal rules, and the bold-numbered item pattern (`**1. Name**`).
     *
     * <p>The CSS gives:
     * <ul>
     *   <li>Letter-size pages with proper margins</li>
     *   <li>Title page on its own (page-break after .title-page)</li>
     *   <li>Two-column flex layout for sections containing an image</li>
     *   <li>Page numbers + doc title in footer via {@code @page}</li>
     *   <li>Decent typography (system serif for body, sans for headings)</li>
     * </ul>
     */
    static String markdownToStyledHtml(String title, String md) {
        String body = mdToHtmlBody(md == null ? "" : md);
        String safeTitle = escapeHtml(title == null ? "Deliverable" : title);
        String date = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"));
        return """
                <!doctype html>
                <html><head><meta charset="utf-8"><title>%TITLE%</title>
                <!-- no-referrer meta: image CDNs frequently hot-link-block when
                     the Referer header is the file:// temp HTML. Sending no
                     referrer at all makes most of them serve normally. -->
                <meta name="referrer" content="no-referrer">
                <style>
                  @page { size: Letter; margin: 0.75in 0.6in; }
                  @page { @bottom-left  { content: "%TITLE%"; font-size: 9pt; color: #8a93a6; }
                          @bottom-right { content: "Page " counter(page) " of " counter(pages);
                                          font-size: 9pt; color: #8a93a6; } }
                  html, body { font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
                               color: #1a1f2c; line-height: 1.55; font-size: 11pt; margin: 0; }
                  h1 { font-size: 28pt; font-weight: 700; color: #1e3c70; margin: 0 0 .3em;
                       overflow-wrap: break-word; word-break: normal; hyphens: auto; }
                  h2 { font-size: 16pt; font-weight: 700; color: #1e3c70; margin: 1.6em 0 .25em;
                       padding-bottom: 6px; border-bottom: 1px solid #c0c8d8; }
                  h3 { font-size: 13pt; font-weight: 700; margin: 1em 0 .25em; color: #2a4070; }
                  p  { margin: .55em 0; }
                  ul { margin: .35em 0 .9em 1.2em; padding: 0; }
                  li { margin: .2em 0; }
                  strong { color: #0e1d3a; }
                  a { color: #2962a8; text-decoration: none; word-break: break-all; }
                  hr { border: 0; border-top: 1px solid #d8dde8; margin: 1.4em 0; }
                  /* Cap every image at a sensible page-fraction so a single
                     photo can't consume a whole page. The two-column section
                     layout has its own tighter cap (280px); this applies to
                     any image NOT inside section.with-image (e.g. a hero
                     that landed in the preamble or a section the grouper
                     didn't catch). max-height: 3.2in keeps images small
                     enough that captions + body still fit alongside. */
                  img { max-width: 100%; max-height: 3.2in; height: auto;
                        border-radius: 6px; display: block;
                        object-fit: contain; margin: .5em auto; }
                  /* Title page */
                  .title-page { page-break-after: always; min-height: 9.2in; display: flex;
                                flex-direction: column; align-items: center; justify-content: center;
                                text-align: center; }
                  /* Title page H1: cap font when long titles would clip. CSS
                     can't do "shrink to fit" without JS, so allow wrap and
                     softer max-width. Shorter titles still center cleanly. */
                  .title-page h1 { font-size: 28pt; max-width: 6.5in; line-height: 1.2;
                                    overflow-wrap: break-word; word-break: normal;
                                    hyphens: auto; padding: 0 .25in; }
                  .title-page .accent { width: 200px; border-top: 2px solid #c0c8d8;
                                         margin: 1em 0 1.2em; }
                  .title-page .meta { color: #6c7588; font-size: 12pt; margin: .25em 0; }
                  /* Section with image — CSS GRID, not flex. Print rendering of
                     flex inside page-break-inside: avoid is unreliable in
                     Chromium; grid prints rock-solid. */
                  section.with-image { display: grid; grid-template-columns: 1.4fr 1fr;
                                        gap: 24px; align-items: start;
                                        page-break-inside: avoid; margin: 1em 0; }
                  section.with-image .figure { width: 100%; }
                  section.with-image .figure img { width: 100%; max-height: 280px;
                                                    object-fit: contain; }
                  section.with-image .figure .caption { font-size: 9pt; color: #6c7588;
                                                         text-align: center; margin-top: 6px; }
                  section.no-image { page-break-inside: avoid; }
                </style></head>
                <body>
                  <div class="title-page">
                    <h1>%TITLE%</h1>
                    <div class="accent"></div>
                    <div class="meta">%DATE%</div>
                    <div class="meta">Prepared by Mins Bot</div>
                  </div>
                  %BODY%
                </body></html>
                """
                .replace("%TITLE%", safeTitle)
                .replace("%DATE%", date)
                .replace("%BODY%", body);
    }

    /** Convert the markdown body into HTML, grouping sections so each H2 (and
     *  bold-numbered item) lays out as either a two-column row (when it has an
     *  image) or a single-column block. */
    private static String mdToHtmlBody(String md) {
        StringBuilder out = new StringBuilder(md.length() + 4096);
        // Split into sections: each H2 / **1. Name** opens a new section.
        java.util.List<String[]> sections = splitSectionsForHtml(md);
        for (String[] sec : sections) {
            String heading = sec[0];      // null for preamble
            int level = sec[1] == null ? 0 : Integer.parseInt(sec[1]);
            String body = sec[2];

            // Find the FIRST image line, pull it out, render the rest as text.
            String[] imgAndRest = extractFirstImage(body);
            String imgUrl = imgAndRest[0];
            String imgAlt = imgAndRest[1];
            String text   = imgAndRest[2];

            if (heading == null && (text == null || text.isBlank()) && imgUrl == null) continue;

            boolean hasImage = imgUrl != null && !imgUrl.isBlank();
            String tag = hasImage ? "section class=\"with-image\"" : "section class=\"no-image\"";
            out.append("<").append(tag).append(">\n");
            if (hasImage) out.append("<div class=\"text\">\n");

            if (heading != null && !heading.isBlank()) {
                String hTag = level <= 2 ? "h2" : "h3";
                out.append("<").append(hTag).append(">").append(escapeHtml(heading))
                   .append("</").append(hTag).append(">\n");
            }
            out.append(renderBlocks(text)).append("\n");

            if (hasImage) {
                out.append("</div>\n<div class=\"figure\"><img src=\"").append(escapeAttr(imgUrl))
                   .append("\" alt=\"").append(escapeAttr(imgAlt == null ? "" : imgAlt))
                   .append("\">");
                if (imgAlt != null && !imgAlt.isBlank()) {
                    out.append("<div class=\"caption\">").append(escapeHtml(imgAlt))
                       .append("</div>");
                }
                out.append("</div>\n");
            }
            out.append("</section>\n");
        }
        return out.toString();
    }

    /** Split markdown body into sections at H2/H3 boundaries OR `**N. Name**`
     *  bold-numbered items. Returns triples [heading, level, body-text]. */
    private static java.util.List<String[]> splitSectionsForHtml(String md) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        java.util.regex.Pattern boldNum = java.util.regex.Pattern.compile(
                "^\\*\\*\\s*(\\d+)\\s*[.\\)\\-:]\\s*([^*]{3,80}?)\\s*\\*\\*\\s*$");
        String currentHeading = null;
        String currentLevel = null;
        StringBuilder buf = new StringBuilder();
        for (String raw : md.split("\n", -1)) {
            String t = raw.trim();
            String newHead = null, newLevel = null;
            if (t.startsWith("## ")) { newHead = t.substring(3).trim(); newLevel = "2"; }
            else if (t.startsWith("### ")) { newHead = t.substring(4).trim(); newLevel = "3"; }
            else {
                java.util.regex.Matcher bm = boldNum.matcher(t);
                if (bm.matches()) { newHead = bm.group(2).trim(); newLevel = "2"; }
            }
            if (newHead != null) {
                // Close previous
                out.add(new String[]{currentHeading, currentLevel, buf.toString()});
                buf.setLength(0);
                currentHeading = newHead;
                currentLevel   = newLevel;
            } else {
                buf.append(raw).append("\n");
            }
        }
        out.add(new String[]{currentHeading, currentLevel, buf.toString()});
        return out;
    }

    /** Pull the first markdown image out of {@code body}, return [url, alt, body-without-it]. */
    private static String[] extractFirstImage(String body) {
        if (body == null) return new String[]{null, null, ""};
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)").matcher(body);
        if (m.find()) {
            String alt = m.group(1);
            String url = m.group(2);
            String rest = body.substring(0, m.start()) + body.substring(m.end());
            return new String[]{url, alt, rest};
        }
        return new String[]{null, null, body};
    }

    /** Render block-level markdown: paragraphs, bullets, hr. Inline (bold,
     *  italic, links) handled by {@link #renderInline}. Crude but sufficient
     *  for the synthesizer's output. */
    private static String renderBlocks(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n");
        StringBuilder paragraph = new StringBuilder();
        boolean inList = false;
        for (String raw : lines) {
            String t = raw.trim();
            if (t.isEmpty()) {
                if (paragraph.length() > 0) {
                    out.append("<p>").append(renderInline(paragraph.toString().trim())).append("</p>\n");
                    paragraph.setLength(0);
                }
                if (inList) { out.append("</ul>\n"); inList = false; }
                continue;
            }
            if (t.startsWith("---") || t.startsWith("***") || t.startsWith("___")) {
                if (paragraph.length() > 0) { out.append("<p>").append(renderInline(paragraph.toString().trim())).append("</p>\n"); paragraph.setLength(0); }
                if (inList) { out.append("</ul>\n"); inList = false; }
                out.append("<hr>\n");
                continue;
            }
            if (t.startsWith("- ") || t.startsWith("* ")) {
                if (paragraph.length() > 0) { out.append("<p>").append(renderInline(paragraph.toString().trim())).append("</p>\n"); paragraph.setLength(0); }
                if (!inList) { out.append("<ul>\n"); inList = true; }
                out.append("<li>").append(renderInline(t.substring(2).trim())).append("</li>\n");
                continue;
            }
            if (inList) { out.append("</ul>\n"); inList = false; }
            if (paragraph.length() > 0) paragraph.append(' ');
            paragraph.append(t);
        }
        if (paragraph.length() > 0) out.append("<p>").append(renderInline(paragraph.toString().trim())).append("</p>\n");
        if (inList) out.append("</ul>\n");
        return out.toString();
    }

    /** Inline markdown: **bold**, *italic*, _italic_, [text](url). */
    private static String renderInline(String s) {
        if (s == null || s.isEmpty()) return "";
        String r = escapeHtml(s);
        // Links — process before bold/italic so URL contents aren't mangled.
        r = r.replaceAll("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)",
                "<a href=\"$2\">$1</a>");
        r = r.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        r = r.replaceAll("(?<![A-Za-z0-9])_([^_]+)_(?![A-Za-z0-9])", "<em>$1</em>");
        return r;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String escapeAttr(String s) { return escapeHtml(s); }

    private Path writeDocx(Path mdPath, String title, String body) {
        Path out = sibling(mdPath, ".docx");
        String result = wordDocTools.createWordDocument(out.toAbsolutePath().toString(), title, body);
        log.info("[Formatter] docx: {} → {}", mdPath.getFileName(), result);
        return out;
    }

    /**
     * Split body by H2 ("## ") sections. Each section → one slide. First slide is
     * a title slide with the document title. Inside each section, lines starting
     * with "- " or "* " become bullets.
     */
    private Path writePptx(Path mdPath, String title, String body) throws Exception {
        Path out = sibling(mdPath, ".pptx");
        try (XMLSlideShow ppt = new XMLSlideShow();
             FileOutputStream fos = new FileOutputStream(out.toFile())) {
            // 16:9 widescreen
            ppt.setPageSize(new java.awt.Dimension(960, 540));

            addTitleSlide(ppt, title);
            List<Section> sections = splitH2(body);
            for (Section s : sections) {
                addContentSlide(ppt, s.heading, s.bullets, s.prose, s.imageUrl, s.imageAlt);
            }
            ppt.write(fos);
        }
        log.info("[Formatter] pptx: {} → {} ({} slides)", mdPath.getFileName(), out.getFileName(), 1);
        return out;
    }

    // ─── PPTX helpers ────────────────────────────────────────────────────

    private record Section(String heading, List<String> bullets, String prose,
                            String imageUrl, String imageAlt) {}

    /** Markdown image: {@code ![alt](url)}. Normalizer already promotes other shapes. */
    private static final Pattern PPT_IMG = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");

    /** Bare image URL fallback. */
    private static final Pattern PPT_BARE_IMG = Pattern.compile(
            "https?://[^\\s\"')<>]+?\\.(?:jpe?g|png|gif|webp|bmp)(?:\\?[^\\s\"')<>]*)?",
            Pattern.CASE_INSENSITIVE);

    private static void addTitleSlide(XMLSlideShow ppt, String title) {
        XSLFSlide slide = ppt.createSlide();
        // Background
        slide.getBackground().setFillColor(new Color(10, 20, 32));

        XSLFTextBox tb = slide.createTextBox();
        tb.setAnchor(new Rectangle(60, 200, 840, 140));
        XSLFTextParagraph p = tb.addNewTextParagraph();
        p.setTextAlign(TextAlign.CENTER);
        XSLFTextRun r = p.addNewTextRun();
        r.setText(title);
        r.setFontSize(40d);
        r.setBold(true);
        r.setFontColor(new Color(207, 234, 255));
    }

    private static void addContentSlide(XMLSlideShow ppt, String heading, List<String> bullets,
                                         String prose, String imageUrl, String imageAlt) {
        XSLFSlide slide = ppt.createSlide();
        slide.getBackground().setFillColor(new Color(15, 25, 38));

        // Try to fetch image first — determines whether the body box is narrow
        // (image on the right) or full-width (no image).
        byte[] imageBytes = null;
        PictureType picType = null;
        if (imageUrl != null && !imageUrl.isBlank()) {
            imageBytes = fetchImageBytes(imageUrl);
            if (imageBytes != null) picType = detectPictureType(imageUrl, imageBytes);
        }
        boolean hasImage = imageBytes != null && picType != null;

        // Heading — full width
        XSLFTextBox h = slide.createTextBox();
        h.setAnchor(new Rectangle(40, 30, 880, 60));
        XSLFTextParagraph hp = h.addNewTextParagraph();
        XSLFTextRun hr = hp.addNewTextRun();
        hr.setText(heading == null ? "" : heading);
        hr.setFontSize(28d);
        hr.setBold(true);
        hr.setFontColor(new Color(74, 214, 255));

        // Body box — narrower when an image is on the right.
        int bodyWidth = hasImage ? 520 : 880;
        XSLFTextBox b = slide.createTextBox();
        b.setAnchor(new Rectangle(40, 110, bodyWidth, 400));
        if (bullets != null && !bullets.isEmpty()) {
            for (String bullet : bullets) {
                XSLFTextParagraph bp = b.addNewTextParagraph();
                bp.setBullet(true);
                bp.setIndentLevel(0);
                XSLFTextRun br = bp.addNewTextRun();
                br.setText(bullet);
                br.setFontSize(hasImage ? 14d : 18d);
                br.setFontColor(new Color(220, 232, 245));
            }
        } else if (prose != null && !prose.isBlank()) {
            for (String line : prose.split("\n")) {
                if (line.isBlank()) continue;
                XSLFTextParagraph bp = b.addNewTextParagraph();
                XSLFTextRun br = bp.addNewTextRun();
                br.setText(line);
                br.setFontSize(hasImage ? 13d : 16d);
                br.setFontColor(new Color(220, 232, 245));
            }
        }

        // Image — right column, scaled to fit.
        if (hasImage) {
            try {
                XSLFPictureData pd = ppt.addPicture(imageBytes, picType);
                XSLFPictureShape pic = slide.createPicture(pd);
                java.awt.Dimension natural = pd.getImageDimension();
                int nw = Math.max(1, natural.width);
                int nh = Math.max(1, natural.height);
                int maxW = 380, maxH = 360;

                // Preserve aspect ratio EXACTLY by computing the bound dimension
                // first (the one that hits the box edge) then deriving the other
                // from it — instead of multiplying both by a shared scale, which
                // accumulates rounding error and lets PowerPoint stretch-fill
                // the picture into a slightly-wrong rectangle.
                double aspect = (double) nw / (double) nh;
                int w, hh;
                if (aspect >= ((double) maxW / (double) maxH)) {
                    // Wider than the box's aspect — width is the constraint.
                    w  = Math.min(maxW, nw);
                    hh = (int) Math.round(w / aspect);
                } else {
                    // Taller than the box's aspect — height is the constraint.
                    hh = Math.min(maxH, nh);
                    w  = (int) Math.round(hh * aspect);
                }
                if (w  < 1) w  = 1;
                if (hh < 1) hh = 1;

                // Center within the right column.
                int xRight = 540 + (maxW - w) / 2;       // right column starts at x=540
                int yTop   = 110 + (maxH - hh) / 2;
                pic.setAnchor(new Rectangle(xRight, yTop, w, hh));

                // Tiny caption under the image if alt is meaningful
                if (imageAlt != null && !imageAlt.isBlank()) {
                    XSLFTextBox cap = slide.createTextBox();
                    cap.setAnchor(new Rectangle(xRight, yTop + hh + 4, w, 22));
                    XSLFTextParagraph cp = cap.addNewTextParagraph();
                    cp.setTextAlign(TextAlign.CENTER);
                    XSLFTextRun cr = cp.addNewTextRun();
                    cr.setText(imageAlt.length() > 60 ? imageAlt.substring(0, 60) + "…" : imageAlt);
                    cr.setFontSize(10d);
                    cr.setFontColor(new Color(155, 188, 221));
                }
            } catch (Exception e) {
                log.warn("[Formatter] pptx image embed failed for {}: {}", imageUrl, e.getMessage());
            }
        }
    }

    /** Detect PNG vs JPEG vs GIF from magic bytes (more reliable than URL extension
     *  since CDNs often serve mismatched content-types). Falls back to PNG. */
    private static PictureType detectPictureType(String url, byte[] bytes) {
        if (bytes == null || bytes.length < 4) return PictureType.PNG;
        // PNG: 89 50 4E 47
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) return PictureType.PNG;
        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) return PictureType.JPEG;
        // GIF: 47 49 46 38
        if (bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x38) return PictureType.GIF;
        // BMP: 42 4D
        if (bytes[0] == 0x42 && bytes[1] == 0x4D) return PictureType.BMP;
        // Apache POI XSLF doesn't accept WEBP — let it fail back to URL-based detection
        String low = url == null ? "" : url.toLowerCase();
        if (low.endsWith(".jpg") || low.endsWith(".jpeg") || low.contains(".jpg?") || low.contains(".jpeg?")) return PictureType.JPEG;
        if (low.endsWith(".gif") || low.contains(".gif?")) return PictureType.GIF;
        if (low.endsWith(".bmp") || low.contains(".bmp?")) return PictureType.BMP;
        return PictureType.PNG;
    }

    /** Fetch image bytes with browser-realistic User-Agent (CDNs commonly 403
     *  generic Java HttpClient UAs). 8 MB cap. Returns null on any failure. */
    private static byte[] fetchImageBytes(String url) {
        if (url == null || url.isBlank()) return null;
        log.info("[Formatter] pptx fetching image: {}", url);
        try {
            // file:// URIs — what saveImageLocally now emits for workfolder
            // images. Without this branch we'd fall through to Paths.get(url)
            // which can't parse a "file:///C:/..." string on Windows, every
            // slide image would silently fail to embed.
            if (url.startsWith("file:")) {
                Path local = Paths.get(URI.create(url));
                if (Files.isRegularFile(local) && Files.size(local) <= 8 * 1024 * 1024) {
                    byte[] body = Files.readAllBytes(local);
                    log.info("[Formatter] pptx image read from local file ({} bytes): {}",
                            body.length, local);
                    return body;
                }
                log.warn("[Formatter] pptx local image not found / too big: {}", local);
                return null;
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                HttpClient http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(8))
                        .followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .GET().build();
                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() / 100 != 2) {
                    log.warn("[Formatter] pptx image HTTP {} for {}", resp.statusCode(), url);
                    return null;
                }
                byte[] body = resp.body();
                if (body == null || body.length == 0 || body.length > 8 * 1024 * 1024) return null;
                log.info("[Formatter] pptx image fetched OK ({} bytes): {}", body.length, url);
                return body;
            }
            Path p = Paths.get(url);
            if (Files.isRegularFile(p) && Files.size(p) <= 8 * 1024 * 1024) {
                return Files.readAllBytes(p);
            }
            return null;
        } catch (Exception e) {
            log.warn("[Formatter] pptx image fetch FAILED for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /** Bold-numbered item shape the synthesizer commonly emits: {@code **1. Name**}.
     *  Without recognising this, a deck like "Top 5 SUVs" with no H2 headings
     *  collapses into a single "Overview" slide that crams every item into
     *  one bullet list. */
    private static final java.util.regex.Pattern PPT_BOLD_NUM = java.util.regex.Pattern.compile(
            "^\\*\\*\\s*\\d+\\s*[.\\)\\-:]\\s*([^*]{3,80}?)\\s*\\*\\*\\s*$");

    private static List<Section> splitH2(String md) {
        List<Section> out = new ArrayList<>();
        if (md == null || md.isBlank()) return out;
        String current = null;
        StringBuilder buf = new StringBuilder();
        for (String line : md.split("\n")) {
            String t = line.trim();
            String newHeading = null;
            if (t.startsWith("## ")) {
                newHeading = t.substring(3).trim();
            } else if (t.startsWith("### ")) {
                newHeading = t.substring(4).trim();
            } else {
                java.util.regex.Matcher bm = PPT_BOLD_NUM.matcher(t);
                if (bm.matches()) newHeading = bm.group(1).trim();
            }
            if (newHeading != null) {
                if (current != null) out.add(toSection(current, buf.toString()));
                current = newHeading;
                buf.setLength(0);
            } else if (current != null) {
                buf.append(line).append("\n");
            }
        }
        if (current != null) out.add(toSection(current, buf.toString()));
        // If no section headings exist at all, treat the whole body as one slide.
        if (out.isEmpty()) out.add(toSection("Overview", md));
        return out;
    }

    private static Section toSection(String heading, String body) {
        List<String> bullets = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        String imageUrl = null;
        String imageAlt = null;
        for (String line : body.split("\n")) {
            String t = line.trim();
            // First image found wins; later images are ignored on this slide.
            if (imageUrl == null) {
                Matcher imgM = PPT_IMG.matcher(t);
                if (imgM.find()) {
                    imageAlt = imgM.group(1);
                    imageUrl = imgM.group(2);
                    continue;
                }
                Matcher bareM = PPT_BARE_IMG.matcher(t);
                if (bareM.find()) {
                    imageUrl = bareM.group();
                    // Use any text on the line as alt (rare since normalizer
                    // already promoted these, but keeps us robust).
                    imageAlt = t.replace(imageUrl, "")
                            .replaceAll("(?i)_?image(?:\\s+reference)?:?_?", "")
                            .replaceAll("[\\-–—•|:]+\\s*$", "")
                            .trim();
                    continue;
                }
            }
            if (t.startsWith("- ") || t.startsWith("* ")) {
                bullets.add(t.substring(2).trim());
            } else if (t.startsWith("### ")) {
                bullets.add(t.substring(4).trim()); // promote H3s to bullets too
            } else if (!t.isEmpty() && bullets.isEmpty()) {
                // Collect prose only when there are no bullets — slides with both look bad.
                prose.append(t).append("\n");
            }
        }
        return new Section(heading, bullets, prose.toString().trim(), imageUrl, imageAlt);
    }

    // ─── Utilities ───────────────────────────────────────────────────────

    private static Path sibling(Path original, String newExtension) {
        String name = original.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return original.resolveSibling(stem + newExtension);
    }

    // ─── Image-reference normalizer ──────────────────────────────────────
    //
    // The synthesizer LLM emits image references in 4+ different shapes:
    //   1. ![alt](url)                                 ← canonical, want
    //   2. [alt](url-ending-.jpg/.png/...)              ← markdown link to image
    //   3. "_Image reference:_ caption –\n<url>"        ← caption on prev line, bare URL
    //   4. Bare URL on its own line                     ← no caption at all
    //   5. "Image: url" / "Photo: url" / "Picture: url" ← inline label + URL
    // All five are rewritten to shape 1 here so PDF/Word/PPT writers only need
    // to handle one form. This is the single source of truth for "what is an image."

    private static final java.util.regex.Pattern P_MD_IMAGE =
            java.util.regex.Pattern.compile("!\\[[^\\]]*\\]\\([^)]+\\)");
    private static final java.util.regex.Pattern P_MD_LINK_IMG =
            java.util.regex.Pattern.compile(
                    "(?<!!)\\[([^\\]]*)\\]\\((https?://[^)]+?\\.(?:jpe?g|png|gif|webp|bmp|svg)(?:\\?[^)]*)?)\\)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern P_BARE_IMG_URL =
            java.util.regex.Pattern.compile(
                    "https?://[^\\s\"')<>]+?\\.(?:jpe?g|png|gif|webp|bmp|svg)(?:\\?[^\\s\"')<>]*)?",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern P_CAPTION_LABEL =
            java.util.regex.Pattern.compile(
                    "^_?(?:image(?:\\s+reference)?|photo|picture|figure)\\b[\\s_:]*",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    static String normalizeImageReferences(String md) {
        if (md == null || md.isEmpty()) return md == null ? "" : md;
        String[] lines = md.split("\n", -1);
        StringBuilder out = new StringBuilder(md.length() + 256);
        String pendingCaption = null;
        int promoted = 0;

        for (String raw : lines) {
            String line = raw;
            String trimmed = line.trim();

            // Pass 1: rewrite [alt](url-with-image-ext) → ![alt](url) IN PLACE.
            //         (Don't touch real ![alt](url) — negative lookbehind in P_MD_LINK_IMG.)
            java.util.regex.Matcher linkM = P_MD_LINK_IMG.matcher(line);
            if (linkM.find()) {
                StringBuilder rebuilt = new StringBuilder();
                int last = 0;
                linkM.reset();
                while (linkM.find()) {
                    rebuilt.append(line, last, linkM.start())
                           .append("!").append(line, linkM.start(), linkM.end());
                    last = linkM.end();
                    promoted++;
                }
                rebuilt.append(line, last, line.length());
                line = rebuilt.toString();
                trimmed = line.trim();
            }

            // Pass 2: caption-only line followed by bare-URL line.
            //         Stash the caption; consume on the next bare URL.
            boolean isCaptionOnly = !trimmed.isEmpty()
                    && P_CAPTION_LABEL.matcher(trimmed).find()
                    && !P_BARE_IMG_URL.matcher(trimmed).find()
                    && !P_MD_IMAGE.matcher(trimmed).find();
            if (isCaptionOnly) {
                pendingCaption = trimmed
                        .replaceAll("(?i)_?image(?:\\s+reference)?:?_?", "")
                        .replaceAll("[\\-–—•|:]+\\s*$", "")
                        .trim();
                // Don't emit caption-only lines — they'll come back as the alt
                // text of the next image embed.
                continue;
            }

            // Pass 3: bare image URL anywhere on a line that ISN'T already a
            //         markdown image. Rewrite to ![alt](url).
            java.util.regex.Matcher bareM = P_BARE_IMG_URL.matcher(line);
            if (bareM.find() && !P_MD_IMAGE.matcher(line).find()) {
                String url = bareM.group();
                String before = line.substring(0, bareM.start());
                String after = line.substring(bareM.end());
                // Best-effort alt: pending caption from previous line wins;
                // else any prose before the URL on this line; else "image".
                String alt;
                if (pendingCaption != null && !pendingCaption.isEmpty()) {
                    alt = pendingCaption;
                } else {
                    alt = before
                            .replaceAll("(?i)_?image(?:\\s+reference)?:?_?", "")
                            .replaceAll("[\\-–—•|:]+\\s*$", "")
                            .trim();
                    if (alt.isEmpty()) alt = "image";
                }
                pendingCaption = null;

                // Emit any leading prose on its own line (rare), then the image.
                String beforeTrim = before.replaceAll("(?i)_?image(?:\\s+reference)?:?_?\\s*", "")
                        .replaceAll("[\\-–—•|:]+\\s*$", "")
                        .trim();
                if (!beforeTrim.isEmpty() && !beforeTrim.equalsIgnoreCase(alt)) {
                    out.append(beforeTrim).append('\n');
                }
                out.append("![").append(alt).append("](").append(url).append(")");
                if (!after.isBlank()) out.append(' ').append(after.trim());
                out.append('\n');
                promoted++;
                continue;
            }

            // Anything else: stale pending caption is dropped (don't bleed
            // across paragraphs). Emit the line untouched.
            pendingCaption = null;
            out.append(line).append('\n');
        }
        log.info("[Formatter] normalizeImageReferences: promoted {} image reference(s) to ![](...)", promoted);
        // Trim the single trailing newline we always add
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == '\n') out.setLength(len - 1);
        return out.toString();
    }

    /**
     * Run a HEAD probe on every {@code ![alt](url)} reference in {@code md} and
     * delete the ones that 4xx/5xx or time out. The synthesizer hallucinates
     * vendor-style image URLs (esp. WordPress {@code /wp-content/uploads/...})
     * that don't exist; without this, the PDF/PPTX writers fetch them at
     * render time, get 404, and silently skip — leaving the deliverable
     * imageless with no signal to anyone except the log file.
     *
     * <p>Probes run in parallel (8 threads, 5s timeout each) and are cached
     * within this single conversion pass so duplicate URLs only hit the
     * network once. Probe HEAD first; falls back to a tiny ranged GET if the
     * server doesn't allow HEAD.
     */
    private String stripBrokenImageRefs(String md) {
        if (md == null || md.isBlank()) return md;
        // Capture ![alt](url) WITH the alt group so we can use it to search for
        // a real replacement when the LLM-supplied URL is invalid.
        java.util.regex.Pattern imgRef =
                java.util.regex.Pattern.compile("!\\[([^\\]]*)\\]\\((https?://[^)]+?)\\)");
        java.util.regex.Matcher m = imgRef.matcher(md);

        // Collect all (alt, url) pairs and validate each URL in parallel.
        record Ref(int start, int end, String alt, String url) {}
        java.util.List<Ref> refs = new java.util.ArrayList<>();
        while (m.find()) refs.add(new Ref(m.start(), m.end(), m.group(1), m.group(2)));
        if (refs.isEmpty()) return md;

        java.util.Map<String, Boolean> alive = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Set<String> uniq = new java.util.LinkedHashSet<>();
        for (Ref r : refs) uniq.add(r.url);

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
                    Thread t = new Thread(r, "img-probe");
                    t.setDaemon(true);
                    return t;
                });
        try {
            java.util.List<java.util.concurrent.CompletableFuture<Void>> fs = new java.util.ArrayList<>();
            for (String u : uniq) {
                fs.add(java.util.concurrent.CompletableFuture.runAsync(
                        () -> alive.put(u, probeUrl(u)), pool));
            }
            java.util.concurrent.CompletableFuture
                    .allOf(fs.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Formatter] image probe pool error: {}", e.getMessage());
        }

        // For broken URLs, find a verified replacement via Serper image search.
        // CACHE KEYED ON ALT TEXT, NOT URL — the LLM frequently emits the same
        // fake URL across multiple products, but with different alt text
        // ("Cadillac Escalade", "Toyota Land Cruiser", etc.). Caching by URL
        // would search once and reuse the same image on every slide.
        // Section context (extracted below) is appended to short/generic alts
        // so "image" becomes "image | <Section Heading>" for a usable query.
        java.util.Map<Integer, String> sectionAtPos = sectionHeadingsByPosition(md);
        java.util.Map<String, String> replacementByQuery = new java.util.HashMap<>();
        java.util.Map<Integer, String> replacementByRef = new java.util.HashMap<>();
        for (int i = 0; i < refs.size(); i++) {
            Ref r = refs.get(i);
            if (Boolean.TRUE.equals(alive.get(r.url))) continue;
            String query = buildImageQuery(r.alt, nearestHeadingBefore(sectionAtPos, r.start));
            if (replacementByQuery.containsKey(query)) {
                replacementByRef.put(i, replacementByQuery.get(query));
                continue;
            }
            String repl = searchVerifiedImage(query, pool);
            replacementByQuery.put(query, repl);
            replacementByRef.put(i, repl);
        }
        pool.shutdownNow();

        int kept = 0, replaced = 0, dropped = 0;
        StringBuilder sb = new StringBuilder(md.length() + 256);
        int last = 0;
        for (int i = 0; i < refs.size(); i++) {
            Ref r = refs.get(i);
            sb.append(md, last, r.start);
            if (Boolean.TRUE.equals(alive.get(r.url))) {
                sb.append(md, r.start, r.end); // original ![alt](url) kept
                kept++;
            } else {
                String real = replacementByRef.get(i);
                if (real != null) {
                    sb.append("![").append(r.alt).append("](").append(real).append(")");
                    log.info("[Formatter] image swap [{}]: \"{}\" → {}", i, r.alt, real);
                    replaced++;
                } else {
                    log.warn("[Formatter] no verified replacement for \"{}\" (broken: {})",
                            r.alt, r.url);
                    dropped++;
                }
            }
            last = r.end;
        }
        sb.append(md, last, md.length());
        log.info("[Formatter] image URL validation: kept {}, replaced {}, dropped {} of {} refs",
                kept, replaced, dropped, refs.size());
        return sb.toString();
    }

    /**
     * Walk the markdown by H2 sections; for each section that does NOT already
     * contain a markdown image line, search for one via Serper images, verify
     * it with the same 5-check probe, and inject {@code ![heading](url)} right
     * under the section heading. Skips sections whose heading is the exec
     * summary, recommendation, conclusion, or other meta — those don't need
     * a hero image.
     */
    private String injectImagesForBareSections(String md, Path imagesDir) {
        if (md == null || md.isBlank()) return md;
        if (serperApiKey == null || serperApiKey.isBlank()) {
            log.info("[Formatter] Serper key not configured — skipping image injection");
            return md;
        }
        String[] lines = md.split("\n", -1);

        // Pass 1 — find every ITEM boundary, not just ## headings. The LLM
        // sometimes writes "**1. Razer Blade 18 (2026)**" instead of "## Razer
        // Blade 18 (2026)" — five products inside ONE H2 section. Treat each
        // bold-numbered line as its own item too so each gets an image.
        record Sec(int headingLine, String heading, int bodyStart, int bodyEnd, boolean hasImage) {}
        // Matches "**1. Name**" / "**1) Name**" / "**1 - Name**" with optional
        // trailing period. Also "### 1. Name" / "### Name".
        java.util.regex.Pattern boldNum = java.util.regex.Pattern.compile(
                "^\\*\\*\\s*(\\d+)\\s*[.\\)\\-:]\\s*([^*]{3,80}?)\\s*\\*\\*\\s*$");
        java.util.List<int[]> heads = new java.util.ArrayList<>();
        java.util.List<String> headsText = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("## ")) {
                heads.add(new int[]{i});
                headsText.add(t.substring(3).trim());
            } else if (t.startsWith("### ")) {
                heads.add(new int[]{i});
                headsText.add(t.substring(4).trim());
            } else {
                java.util.regex.Matcher bm = boldNum.matcher(t);
                if (bm.matches()) {
                    heads.add(new int[]{i});
                    // Use the captured name (group 2) without the "1." prefix
                    headsText.add(bm.group(2).trim());
                }
            }
        }
        if (heads.isEmpty()) return md;

        java.util.List<Sec> secs = new java.util.ArrayList<>();
        for (int s = 0; s < heads.size(); s++) {
            int hLine = heads.get(s)[0];
            int bodyStart = hLine + 1;
            int bodyEnd = (s + 1 < heads.size()) ? heads.get(s + 1)[0] : lines.length;
            boolean has = false;
            for (int i = bodyStart; i < bodyEnd; i++) {
                if (PPT_IMG.matcher(lines[i]).find()
                        || PPT_BARE_IMG.matcher(lines[i]).find()) { has = true; break; }
            }
            secs.add(new Sec(hLine, headsText.get(s), bodyStart, bodyEnd, has));
        }

        // Pass 2 — for image-less sections, find a verified image. Skip meta
        // sections that don't need a hero image.
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "img-inject"); t.setDaemon(true); return t;
                });
        java.util.Map<Integer, String> injectAt = new java.util.HashMap<>();
        try {
            int sectionsProcessed = 0;
            for (Sec sec : secs) {
                if (sec.hasImage) continue;
                if (isMetaSection(sec.heading)) continue;
                if (sectionLooksTooShortForImage(lines, sec.bodyStart, sec.bodyEnd)) continue;
                // Photo-biased query: "Photo of <heading>". Pushes Google/Bing
                // image search toward actual product photography and away from
                // logos, charts, infographics, and abstract decorative images.
                String query = buildPhotoQuery(sec.heading);
                String url = searchVerifiedImage(query, pool);
                if (url != null) {
                    // Save the picked image into the task's images/ folder so
                    // the user can audit it after the fact. Use a numbered slug
                    // (01-macbook-pro.jpg) so file order matches section order.
                    Path saved = saveImageLocally(imagesDir, sectionsProcessed + 1, sec.heading, url, query);
                    String mdRef = (saved != null)
                            ? "![" + sec.heading + "](" + saved.toAbsolutePath().toUri() + ")"
                            : "![" + sec.heading + "](" + url + ")";
                    injectAt.put(sec.headingLine, mdRef);
                    log.info("[Formatter] image inject for section \"{}\" → {} (local: {})",
                            sec.heading, url, saved);
                } else {
                    log.warn("[Formatter] image inject — no verified result for section \"{}\"", sec.heading);
                }
                sectionsProcessed++;
            }
            log.info("[Formatter] image injection: {} sections needed an image, {} successfully injected",
                    sectionsProcessed, injectAt.size());
        } finally {
            pool.shutdownNow();
        }

        if (injectAt.isEmpty()) return md;

        // Pass 3 — rebuild the markdown with injected image lines after each
        // qualifying heading.
        StringBuilder sb = new StringBuilder(md.length() + injectAt.size() * 200);
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append('\n');
            if (injectAt.containsKey(i)) {
                sb.append('\n').append(injectAt.get(i));
                if (i < lines.length - 1) sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Sections like "Executive Summary" / "Recommendation" / "Conclusion" /
     *  "Methodology" don't get a hero image — they're meta, not topic. */
    private static boolean isMetaSection(String heading) {
        if (heading == null) return true;
        String h = heading.toLowerCase().trim();
        return h.contains("executive summary")
            || h.contains("summary")
            || h.contains("recommendation")
            || h.contains("conclusion")
            || h.contains("methodology")
            || h.contains("introduction")
            || h.contains("overview")
            || h.contains("table of contents")
            || h.contains("references")
            || h.contains("appendix")
            || h.contains("next steps");
    }

    /** Skip sections whose body is just one or two lines — they're navigation
     *  or page breaks, not real content. Below ~80 chars of body it's not
     *  worth the Serper round-trip. */
    private static boolean sectionLooksTooShortForImage(String[] lines, int bodyStart, int bodyEnd) {
        int total = 0;
        for (int i = bodyStart; i < bodyEnd; i++) total += lines[i].length();
        return total < 80;
    }

    /** Map of {character offset → H2/H3 heading text} for every heading in {@code md}.
     *  Used to attach section context to image refs whose alt text is short
     *  or generic ("image" / "photo"). */
    private static java.util.Map<Integer, String> sectionHeadingsByPosition(String md) {
        java.util.Map<Integer, String> out = new java.util.TreeMap<>();
        if (md == null) return out;
        int pos = 0;
        for (String line : md.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("## ")) out.put(pos, t.substring(3).trim());
            else if (t.startsWith("### ")) out.put(pos, t.substring(4).trim());
            pos += line.length() + 1; // +1 for the \n we split on
        }
        return out;
    }

    /** Heading text whose start offset is the largest one ≤ {@code refStart}. */
    private static String nearestHeadingBefore(java.util.Map<Integer, String> headings, int refStart) {
        String best = "";
        for (var e : headings.entrySet()) {
            if (e.getKey() <= refStart) best = e.getValue();
            else break;
        }
        return best;
    }

    /** Build a search query that combines alt text + section heading context.
     *  Drops boilerplate words ("image", "photo", "product photo") so the query
     *  looks like a normal product name. Strips numbering ("#1 -", "2.1 ") and
     *  decorative dashes from the heading. */
    /**
     * Photo-biased query for section image search. Cleans the heading (strips
     * numbering / decorative dashes / trailing punctuation) and prepends
     * "Photo of" so the search engine returns actual product photography
     * instead of logos, infographics, comparison charts, or decorative art.
     *
     * <p>Example: a section titled "1. MacBook Pro 2026" becomes the query
     * {@code "Photo of MacBook Pro 2026"}.
     */
    private static String buildPhotoQuery(String heading) {
        if (heading == null) return "Photo";
        String h = heading.trim()
                .replaceAll("^[#0-9.\\s\\-–—()]+", "")  // strip "#1 -", "2.1 ", "(1) "
                .replaceAll("[\\-–—|:]+\\s*$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (h.isEmpty()) return "Photo";
        // Avoid double-prefixing if the heading already starts with photo / image / etc.
        String low = h.toLowerCase();
        if (low.startsWith("photo of ") || low.startsWith("photo: ")
                || low.startsWith("image of ") || low.startsWith("picture of ")) {
            return h;
        }
        return "Photo of " + h;
    }

    private static String buildImageQuery(String alt, String sectionHeading) {
        String a = alt == null ? "" : alt.trim();
        String h = sectionHeading == null ? "" : sectionHeading.trim()
                .replaceAll("^[#0-9.\\s\\-–—]+", "")  // strip "#1 -", "2.1", "1. "
                .replaceAll("[\\-–—|:]+\\s*$", "")
                .trim();
        // Drop boilerplate that doesn't help retrieval.
        a = a.replaceAll("(?i)\\b(image|photo|picture|product\\s+photo|press\\s+image|" +
                "official\\s+image|reference|figure)\\b", "")
                .replaceAll("\\s{2,}", " ").trim();
        if (a.isEmpty()) return h.isEmpty() ? "" : h;
        if (h.isEmpty() || a.toLowerCase().contains(h.toLowerCase())
                || h.toLowerCase().contains(a.toLowerCase())) return a;
        return h + " " + a;
    }

    /**
     * Search Google Images via Serper, verify each candidate, and return the
     * first one that passes the same {@link #probeUrl} gate. Returns null if
     * Serper isn't configured, no candidates are returned, or all candidates
     * fail verification.
     *
     * <p>This is what makes the deliverable robust against the LLM's image-URL
     * hallucinations: instead of trusting it to recall URLs, we search for them
     * and prove each candidate fetches as a real image with sane dimensions.
     */
    private String searchVerifiedImage(String topic, java.util.concurrent.ExecutorService pool) {
        if (topic == null || topic.isBlank()) return null;
        if (notifier != null) notifier.notify("🖼  image search: \"" + topic + "\"");

        // PRIMARY: local headless browser via Playwright. Free, no API key,
        // works offline of any paid service. Try Google Images first then
        // Bing Images as fallback (Google occasionally serves anti-bot, Bing
        // is friendlier to scraping).
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (playwright != null) {
            try {
                candidates.addAll(playwright.searchGoogleImages(topic, 16));
                log.info("[Formatter] Playwright Google Images for \"{}\": {} candidates",
                        topic, candidates.size());
            } catch (Exception e) {
                log.warn("[Formatter] Playwright Google Images failed for \"{}\": {}", topic, e.getMessage());
            }
            if (candidates.isEmpty()) {
                try {
                    candidates.addAll(playwright.searchBingImages(topic, 16));
                    log.info("[Formatter] Playwright Bing Images for \"{}\": {} candidates",
                            topic, candidates.size());
                } catch (Exception e) {
                    log.warn("[Formatter] Playwright Bing Images failed for \"{}\": {}", topic, e.getMessage());
                }
            }
        }

        // FALLBACK: Serper API. Only used if Playwright is unavailable AND a
        // Serper key happens to be configured. Free local search is the
        // intended path; Serper exists only as a safety net.
        if (candidates.isEmpty() && serperApiKey != null && !serperApiKey.isBlank()) {
            log.info("[Formatter] Local browser search empty — falling back to Serper for \"{}\"", topic);
            candidates.addAll(serperImageCandidates(topic, 16));
        }

        if (candidates.isEmpty()) {
            log.info("[Formatter] No image candidates from any source for \"{}\"", topic);
            return null;
        }
        log.info("[Formatter] {} total image candidates for \"{}\"", candidates.size(), topic);

        // Verify candidates in parallel using RELAXED mode — Serper results
        // come from Google's image index, they're real images. We only need
        // to confirm fetchability + magic bytes, not pass strict ImageIO
        // decode (which rejects too many real-world JPEGs/WEBPs).
        java.util.Map<String, Boolean> verdicts = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.List<java.util.concurrent.CompletableFuture<Void>> fs = new java.util.ArrayList<>();
        for (String c : candidates) {
            fs.add(java.util.concurrent.CompletableFuture.runAsync(
                    () -> verdicts.put(c, probeUrl(c, false)), pool));  // relaxed
        }
        try {
            // 35s instead of 25s — gives slow CDNs more breathing room.
            java.util.concurrent.CompletableFuture
                    .allOf(fs.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(35, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        for (String c : candidates) {
            if (Boolean.TRUE.equals(verdicts.get(c))) {
                log.info("[Formatter] picked verified candidate for \"{}\": {}", topic, c);
                if (notifier != null) {
                    String shortUrl = c.length() <= 90 ? c : c.substring(0, 87) + "…";
                    notifier.notify("🖼  picked: " + shortUrl);
                }
                return c;
            }
        }
        // Surface what failed so we can diagnose if Serper consistently picks
        // hot-link-blocked CDNs for this topic.
        log.warn("[Formatter] all {} Serper candidates failed verification for \"{}\"",
                candidates.size(), topic);
        return null;
    }

    /**
     * POST to {@code google.serper.dev/images} and pull TWO URLs per result:
     * the direct {@code imageUrl} (publisher-hosted, often hot-link blocked)
     * AND the {@code thumbnailUrl} (Google-hosted on gstatic, ALWAYS served).
     *
     * <p>Strategy: queue all {@code imageUrl}s first, then all
     * {@code thumbnailUrl}s, in the same hit-rank order. The verifier tries
     * publisher images first (they're full-resolution), and falls back to
     * the corresponding thumbnails when the publisher blocks. The gstatic
     * thumbnail is small (~200-400px) but it's REAL and always works —
     * dramatically better than dropping the result entirely.
     *
     * <p>Old code also fell back to {@code link} which is the publisher's
     * HTML PAGE, not an image. That was a useless candidate slot.
     */
    private java.util.List<String> serperImageCandidates(String query, int n) {
        java.util.List<String> direct = new java.util.ArrayList<>();
        java.util.List<String> thumbs = new java.util.ArrayList<>();
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8)).build();
            // Pull a healthier number of Serper hits — we extract two URLs each,
            // so 12 hits gives us 24 candidates after dedupe.
            int hitsToRequest = Math.max(8, Math.min(20, n));
            String body = "{\"q\":" + jsonString(query) + ",\"num\":" + hitsToRequest + "}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://google.serper.dev/images"))
                    .timeout(Duration.ofSeconds(12))
                    .header("X-API-KEY", serperApiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Formatter] Serper images HTTP {} for \"{}\"", resp.statusCode(), query);
                return java.util.Collections.emptyList();
            }
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
            com.fasterxml.jackson.databind.JsonNode arr = root.path("images");
            if (arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : arr) {
                    String img = item.path("imageUrl").asText("");
                    String thumb = item.path("thumbnailUrl").asText("");
                    if (img.startsWith("http") && !direct.contains(img)) direct.add(img);
                    if (thumb.startsWith("http") && !thumbs.contains(thumb)) thumbs.add(thumb);
                }
            }
            log.info("[Formatter] Serper for \"{}\": {} direct + {} thumbnails",
                    query, direct.size(), thumbs.size());
        } catch (Exception e) {
            log.warn("[Formatter] Serper images call failed: {}", e.getMessage());
        }
        // Order: every direct URL first, then every thumbnail. Verifier picks
        // the first one that passes — full-res publisher photos win when they
        // serve, gstatic thumbnails when they don't.
        java.util.List<String> ordered = new java.util.ArrayList<>(direct.size() + thumbs.size());
        ordered.addAll(direct);
        ordered.addAll(thumbs);
        if (ordered.size() > n) ordered = ordered.subList(0, n);
        return ordered;
    }

    /** Minimal JSON string escaper for the Serper POST body. */
    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if      (c == '"')  b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20)  b.append(String.format("\\u%04x", (int) c));
            else                b.append(c);
        }
        return b.append('"').toString();
    }

    /** Min raw byte size for an image to be considered "real" (not a 1×1 pixel
     *  placeholder, error PNG, or content-type mismatch). 2 KB. */
    private static final int MIN_IMAGE_BYTES = 2 * 1024;
    /** Min decoded dimensions. Below this is almost certainly a tracking pixel
     *  / placeholder / favicon, not a product photo. */
    private static final int MIN_IMAGE_SIDE = 100;
    /** Cap on how much we download to verify (saves bandwidth on 10MB hero
     *  images — first 256KB is enough to read magic bytes + decode dimensions). */
    private static final int VERIFY_BYTE_CAP = 256 * 1024;

    /**
     * REAL image verification — not just a HEAD probe. Two modes:
     * <ul>
     *   <li><b>Strict</b> (default, used to validate LLM-supplied URLs that
     *       might be hallucinated): HTTP 2xx + ≥2KB + magic bytes + ImageIO
     *       decode + ≥100×100 dimensions.</li>
     *   <li><b>Relaxed</b> (used to verify Serper image-search results):
     *       HTTP 2xx + ≥2KB + magic bytes. Skips ImageIO decode and dimension
     *       check — those reject too many real images (progressive JPEGs,
     *       CMYK, banner aspect ratios).</li>
     * </ul>
     *
     * Real Google Image search results are real images. We just need to
     * confirm they fetch and aren't a 1×1 pixel or HTML "not found" page.
     * Strict-mode rules are designed to catch synthesizer hallucinations,
     * which is a different problem.
     */
    private static boolean probeUrl(String url) { return probeUrl(url, true); }

    private static boolean probeUrl(String url, boolean strict) {
        if (url == null || url.isBlank()) return false;
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(4))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            // Ranged GET — cheap if the server honors Range; falls back to full
            // body capped via InputStream consumption otherwise.
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", ua)
                    .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*,*/*;q=0.8")
                    .header("Range", "bytes=0-" + (VERIFY_BYTE_CAP - 1))
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int sc = resp.statusCode();
            if (sc / 100 != 2 && sc != 206) {
                log.warn("[Formatter] verify FAIL HTTP {} for {}", sc, url);
                return false;
            }
            byte[] bytes = resp.body();
            if (bytes == null || bytes.length < MIN_IMAGE_BYTES) {
                log.warn("[Formatter] verify FAIL too-small ({} bytes) for {}",
                        bytes == null ? 0 : bytes.length, url);
                return false;
            }
            // Magic bytes must say it's actually an image. Some sites return
            // an HTML "not found" page with 200 OK and a generic content-type.
            String mime = sniffMime(bytes);
            if (mime == null) {
                log.warn("[Formatter] verify FAIL unrecognized magic bytes for {}", url);
                return false;
            }
            // Relaxed mode: magic bytes + size already passed → accept.
            // Skip ImageIO/dimension checks because they reject too many real
            // images (progressive JPEG, CMYK, banner aspect ratios, slow
            // partial-content responses where ImageIO can't decode the cap).
            if (!strict) {
                log.info("[Formatter] verify OK (relaxed): {} ({} bytes, {})", url, bytes.length, mime);
                return true;
            }
            // Strict mode — decode and check dimensions.
            try {
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(
                        new java.io.ByteArrayInputStream(bytes));
                if (bi == null) {
                    // Couldn't decode — don't auto-fail (WEBP via ImageIO often
                    // returns null even though the bytes are a real WEBP).
                    // Trust the magic bytes and the size check we already passed.
                    log.info("[Formatter] verify OK (magic-only, no ImageIO decode): {} ({} bytes, {})",
                            url, bytes.length, mime);
                    return true;
                }
                int w = bi.getWidth(), h = bi.getHeight();
                if (w < MIN_IMAGE_SIDE || h < MIN_IMAGE_SIDE) {
                    log.warn("[Formatter] verify FAIL too-small dims {}x{} for {}", w, h, url);
                    return false;
                }
                log.info("[Formatter] verify OK: {} ({} bytes, {}, {}x{})",
                        url, bytes.length, mime, w, h);
                return true;
            } catch (Exception ioe) {
                // Don't reject on ImageIO errors — ImageIO is famously bad at
                // many real-world JPEGs. If magic bytes say it's an image and
                // it's big enough, accept.
                log.info("[Formatter] verify OK (ImageIO failed but magic+size pass): {} ({} bytes, {}): {}",
                        url, bytes.length, mime, ioe.getMessage());
                return true;
            }
        } catch (Exception e) {
            log.warn("[Formatter] verify FAIL network for {}: {}", url, e.getMessage());
            return false;
        }
    }

    /** Sniff image MIME from leading bytes. Returns null if not a recognized image. */
    private static String sniffMime(byte[] b) {
        if (b == null || b.length < 4) return null;
        // PNG: 89 50 4E 47
        if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) return "image/png";
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        // GIF: 47 49 46 38
        if (b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38) return "image/gif";
        // WEBP: RIFF....WEBP
        if (b.length >= 12 && b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
                && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) return "image/webp";
        // BMP: 42 4D
        if (b[0] == 0x42 && b[1] == 0x4D) return "image/bmp";
        // SVG starts with "<?xml" or "<svg"
        if (b.length >= 5 && b[0] == '<') {
            String head = new String(b, 0, Math.min(b.length, 200), java.nio.charset.StandardCharsets.UTF_8)
                    .toLowerCase();
            if (head.contains("<svg")) return "image/svg+xml";
        }
        return null;
    }

    /**
     * Aggressively strip every image-shaped construct from the markdown so the
     * synthesizer's hallucinated URLs can't poison the deliverable. We don't
     * verify them, we don't try to swap them — we delete the whole line.
     * Section headings drive Serper-based image injection in the next pass.
     *
     * <p>Removes:
     * <ul>
     *   <li>{@code ![alt](url)} canonical markdown image lines</li>
     *   <li>{@code [alt](url-ending-in-image-ext)} link-as-image lines</li>
     *   <li>Bare image URLs on their own line ({@code .jpg/.png/.gif/.webp/...})</li>
     *   <li>Caption lines like {@code _Image reference:_ ...} / {@code Image: ...}
     *       / {@code Photo: ...}</li>
     *   <li>The remaining text on the line if it'd be empty after extraction</li>
     * </ul>
     */
    static String stripAllImageRefs(String md) {
        if (md == null || md.isEmpty()) return md == null ? "" : md;
        java.util.regex.Pattern lineImage = java.util.regex.Pattern.compile(
                "!\\[[^\\]]*\\]\\([^)]+\\)");
        java.util.regex.Pattern lineLinkImage = java.util.regex.Pattern.compile(
                "(?<!!)\\[[^\\]]*\\]\\(https?://[^)]+?\\.(?:jpe?g|png|gif|webp|bmp|svg)(?:\\?[^)]*)?\\)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern bareImageUrl = java.util.regex.Pattern.compile(
                "https?://[^\\s\"')<>]+?\\.(?:jpe?g|png|gif|webp|bmp|svg)(?:\\?[^\\s\"')<>]*)?",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern captionLine = java.util.regex.Pattern.compile(
                "^_?(?:image(?:\\s+reference)?|photo|picture|figure)\\b[\\s_:]*",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        StringBuilder out = new StringBuilder(md.length());
        int dropped = 0, scrubbed = 0;
        for (String raw : md.split("\n", -1)) {
            String t = raw.trim();
            // Drop entire lines that are predominantly image-shaped or caption-only.
            boolean dropLine =
                    (lineImage.matcher(t).find()      && lineImage.matcher(t).replaceAll("").trim().isEmpty())
                 || (lineLinkImage.matcher(t).find()  && lineLinkImage.matcher(t).replaceAll("").trim().isEmpty())
                 || (bareImageUrl.matcher(t).find()   && bareImageUrl.matcher(t).replaceAll("").trim().isEmpty())
                 || (captionLine.matcher(t).find()    && !lineImage.matcher(t).find()
                                                       && !bareImageUrl.matcher(t).find()
                                                       && t.length() < 200);
            if (dropLine) { dropped++; continue; }
            // Otherwise scrub inline image-shaped fragments out of the line but
            // keep any surrounding prose.
            String cleaned = lineImage.matcher(raw).replaceAll("");
            cleaned = lineLinkImage.matcher(cleaned).replaceAll("");
            cleaned = bareImageUrl.matcher(cleaned).replaceAll("");
            if (!cleaned.equals(raw)) scrubbed++;
            out.append(cleaned).append('\n');
        }
        if (dropped > 0 || scrubbed > 0) {
            log.info("[Formatter] stripAllImageRefs: dropped {} line(s), scrubbed {} line(s) of LLM image junk",
                    dropped, scrubbed);
        }
        // Trim a single trailing newline.
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == '\n') out.setLength(len - 1);
        return out.toString();
    }

    private static String stripLeadingH1(String md) {
        if (md == null) return "";
        String[] lines = md.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean removed = false;
        for (String line : lines) {
            if (!removed && line.startsWith("# ")) { removed = true; continue; }
            out.append(line).append("\n");
        }
        return out.toString().stripLeading();
    }

    /**
     * Download a picked image into the task's {@code images/} folder so the
     * user has a transparent record of what the bot chose for each section.
     * File names are numbered ({@code 01-macbook-pro-2026.jpg}) so directory
     * order matches section order. Also appends a line to {@code _search-log.txt}
     * mapping the search query to the chosen URL — that's the audit trail
     * the user wanted ("show me what the bot searched for").
     *
     * <p>Returns the saved path, or {@code null} when the directory wasn't
     * provided / the download failed (caller falls back to the remote URL).
     */
    private Path saveImageLocally(Path imagesDir, int idx, String heading, String url, String query) {
        if (imagesDir == null || url == null || url.isBlank()) return null;
        try {
            java.nio.file.Files.createDirectories(imagesDir);
            // Slugify the heading for the filename. Cap at 40 chars so paths
            // don't blow past Windows' 260-char limit on deeply nested users.
            String slug = heading == null ? "section" : heading.toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (slug.isBlank()) slug = "section";
            if (slug.length() > 40) slug = slug.substring(0, 40);

            // Pull bytes with a real UA + no-referrer (same trick the HTML uses
            // — most CDNs serve fine when the referer isn't a sketchy file://).
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(8))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                    .header("Referer", "")
                    .GET().build();
            java.net.http.HttpResponse<byte[]> resp = http.send(req,
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Formatter] image download HTTP {} for {}", resp.statusCode(), url);
                return null;
            }
            byte[] bytes = resp.body();
            if (bytes == null || bytes.length < 2_000) {
                log.warn("[Formatter] image too small ({} bytes) for {}", bytes == null ? 0 : bytes.length, url);
                return null;
            }
            String ext = guessImageExt(bytes, url);
            // Transcode WEBP → JPEG at save time. PDFBox's embedder rejects
            // anything beyond JPG/PNG/GIF — when it gets a webp it emits
            // "Image type UNKNOWN not supported" inline in the PDF. Decoding
            // here (via the TwelveMonkeys imageio plugin) guarantees every
            // file on disk is PDFBox-safe AND Chromium-safe.
            if ("webp".equals(ext)) {
                byte[] jpg = transcodeToJpeg(bytes);
                if (jpg != null) {
                    bytes = jpg;
                    ext = "jpg";
                } else {
                    log.warn("[Formatter] webp → jpeg transcode failed for {}; keeping raw webp", url);
                }
            }
            String name = String.format("%02d-%s.%s", idx, slug, ext);
            Path target = imagesDir.resolve(name);
            java.nio.file.Files.write(target, bytes);

            // Append to the per-task search log.
            try {
                Path logFile = imagesDir.resolve("_search-log.txt");
                String line = String.format("[%02d] section=\"%s\"  query=\"%s\"  url=%s  saved=%s%n",
                        idx, heading, query, url, name);
                java.nio.file.Files.writeString(logFile, line,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
            return target;
        } catch (Exception e) {
            log.warn("[Formatter] saveImageLocally failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /** Last-pass scrub for prose the LLM smuggles through despite the synthesize
     *  prompt forbidding it: "Hero image URL: …", bracketed "[embed failed: …]"
     *  debug strings, "Image: … (check latest…)" placeholders, and any second
     *  body-level H1 that {@link #stripLeadingH1} couldn't reach because it
     *  wasn't the very first line. */
    private static String scrubLeftoverPlaceholders(String md) {
        if (md == null || md.isEmpty()) return md;
        String[] lines = md.split("\n", -1);
        StringBuilder out = new StringBuilder(md.length());
        java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile(
                "(?i)^\\s*[*_>\\-]*\\s*\\**\\s*(hero\\s+image|image\\s+url|product\\s+image|"
              + "press\\s+image|official\\s+image|reference\\s+image|figure)\\s*[:\\-]\\s*.*$");
        java.util.regex.Pattern embedFail = java.util.regex.Pattern.compile(
                "\\[[^\\]]*\\bembed\\s+failed\\b[^\\]]*\\]\\s*\\S*");
        for (String raw : lines) {
            String line = raw;
            // Drop "Hero image URL: …" / "Image: …" placeholder lines outright.
            if (placeholder.matcher(line).matches()) continue;
            // Strip inline "[image — embed failed: …]" debug fragments left by
            // a previous PDFBox-fallback render that ended up back in the md.
            line = embedFail.matcher(line).replaceAll("").trim();
            // Drop the line entirely if scrubbing emptied it.
            if (line.isEmpty() && !raw.isEmpty()) continue;
            // Drop ALL body-level H1s — the title page already renders the doc
            // title; any in-body H1 is a duplicate the synthesizer left behind.
            if (line.startsWith("# ")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /** Decode arbitrary image bytes via {@code javax.imageio} (TwelveMonkeys
     *  plugins on the classpath give us WEBP/AVIF/HEIC support) and re-encode
     *  as JPEG so PDFBox's embedder accepts them. Returns null if no decoder
     *  on the classpath can read the bytes — caller falls back to writing the
     *  raw bytes untouched. */
    private static byte[] transcodeToJpeg(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(bytes));
            if (img == null) return null;
            // Strip alpha — JPEG can't carry it, otherwise ImageIO writes a
            // dim/black image. Paint onto an opaque RGB canvas first.
            java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(
                    img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = rgb.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(rgb, "jpg", out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("[Formatter] transcodeToJpeg failed: {}", e.getMessage());
            return null;
        }
    }

    /** Pick a sensible extension from magic bytes; fall back to URL suffix. */
    private static String guessImageExt(byte[] bytes, String url) {
        if (bytes != null && bytes.length >= 12) {
            int b0 = bytes[0] & 0xff, b1 = bytes[1] & 0xff, b2 = bytes[2] & 0xff, b3 = bytes[3] & 0xff;
            if (b0 == 0xFF && b1 == 0xD8) return "jpg";
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "png";
            if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return "gif";
            // RIFF....WEBP
            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                    && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50) return "webp";
        }
        if (url != null) {
            String low = url.toLowerCase();
            int q = low.indexOf('?');
            if (q > 0) low = low.substring(0, q);
            if (low.endsWith(".png"))  return "png";
            if (low.endsWith(".gif"))  return "gif";
            if (low.endsWith(".webp")) return "webp";
            if (low.endsWith(".jpeg") || low.endsWith(".jpg")) return "jpg";
        }
        return "jpg";
    }
}
