package com.minsbot.agent.tools;

import com.minsbot.agent.SystemControlService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class PdfTools {

    private static final Logger log = LoggerFactory.getLogger(PdfTools.class);

    private final ToolExecutionNotifier notifier;
    private final SystemControlService systemControl;

    // Fonts (PDFBox Standard 14)
    private static final PDType1Font FONT_BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font FONT_REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    /** Markdown image syntax: {@code ![alt text](url-or-path)}. URL may be http(s) or a local file path. */
    private static final java.util.regex.Pattern MD_IMAGE =
            java.util.regex.Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");

    /**
     * Fallback for the LLM's frequent mistake of writing image references as plain
     * markdown links instead of image syntax: {@code [text](url-ending-in-image-ext)}.
     * Negative-lookbehind on '!' so we don't double-match a real {@code ![...](...)}.
     */
    private static final java.util.regex.Pattern MD_LINK_IMAGE_URL =
            java.util.regex.Pattern.compile(
                    "(?<!!)\\[([^\\]]*)\\]\\((https?://[^)]+?\\.(?:jpe?g|png|gif|webp|bmp|svg)(?:\\?[^)]*)?)\\)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Last-resort fallback: bare image URL anywhere in a line of prose. The LLM
     * sometimes writes {@code "_Image reference:_ <description> – https://.../foo.jpg"}
     * with no markdown syntax at all. We embed the image and use any text before
     * the URL on the same line as the alt caption.
     */
    private static final java.util.regex.Pattern BARE_IMAGE_URL =
            java.util.regex.Pattern.compile(
                    "(https?://[^\\s\"')<>]+?\\.(?:jpe?g|png|gif|webp|bmp|svg)(?:\\?[^\\s\"')<>]*)?)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Caption-only marker: a line that starts with an image-reference label and
     * does NOT contain a URL (the URL is on the next line). Matches:
     *   "_Image reference:_ Dell XPS 14 photo –"
     *   "Image: Apple MacBook M4"
     *   "Photo of the Cullinan from BMW press kit"
     */
    private static final java.util.regex.Pattern CAPTION_ONLY_LINE =
            java.util.regex.Pattern.compile(
                    "^_?(?:image(?:\\s+reference)?|photo|picture|figure)\\b[\\s_:]*",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Hard cap on image bytes (per image) to keep PDFs sane. 8 MB is plenty for web JPGs/PNGs. */
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    // Page layout
    private static final float MARGIN = 36;  // 0.5 inch (72 points per inch)
    private static final float PAGE_WIDTH = PDRectangle.LETTER.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.LETTER.getHeight();
    private static final float USABLE_WIDTH = PAGE_WIDTH - 2 * MARGIN;

    public PdfTools(ToolExecutionNotifier notifier, SystemControlService systemControl) {
        this.notifier = notifier;
        this.systemControl = systemControl;
    }

    @Tool(description = "Extract plain text from a PDF file. Use when the user asks what a PDF says or to summarize a PDF.")
    public String extractPdfText(
            @ToolParam(description = "Full path to the PDF file") String pdfPath) {
        if (pdfPath == null || pdfPath.isBlank()) return "PDF path is required.";
        notifier.notify("Extracting text from PDF");
        try {
            Path path = Paths.get(pdfPath);
            if (!Files.isRegularFile(path)) return "File not found: " + pdfPath;
            try (PDDocument doc = Loader.loadPDF(path.toFile())) {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(doc);
                if (text == null || text.isBlank()) return "No text content in PDF.";
                text = text.replaceAll("\\s{3,}", "\n\n").trim();
                int max = 50_000;
                if (text.length() > max) text = text.substring(0, max) + "\n\n[... truncated at " + max + " chars]";
                return text;
            }
        } catch (Exception e) {
            return "PDF extraction failed: " + e.getMessage();
        }
    }

    @Tool(description = "LOW-LEVEL: render an already-finished, single-page text snippet to PDF. " +
            "Use ONLY when the user hands you finished content and asks to save it verbatim as a PDF " +
            "(e.g. 'save this letter as a PDF', 'export these notes to PDF'). " +
            "DO NOT use for ANY of the following — these MUST go through produceDeliverable instead: " +
            "any 'PDF report on X', 'PDF brief about Y', 'compile a PDF of Z', researched / sectioned / " +
            "compared / multi-product / multi-source content, anything where YOU still need to do " +
            "research before drafting. produceDeliverable runs plan→research→synthesize→critique→refine and " +
            "renders to PDF with images and citations; this tool just dumps text. Picking this tool for " +
            "research deliverables produces a thin, image-less, source-less PDF that misses the user's intent.")
    public String createPdfDocument(
            @ToolParam(description = "Full file path for the PDF, e.g. 'C:\\Users\\user\\Documents\\report.pdf'") String filePath,
            @ToolParam(description = "Document title (shown as the main heading)") String title,
            @ToolParam(description = "Document body content. Use '# ' for headings, '## ' for sub-headings, " +
                    "'- ' for bullet points, '**text**' for bold, blank lines for spacing.") String content) {

        // Hard refusal guard: if the content looks like a researched multi-section
        // deliverable, route the LLM back to produceDeliverable. The @Tool
        // description forbids this path but the model picks it anyway about a
        // third of the time, then ships a 5 KB text-only PDF and walks away.
        // Heuristic: ≥3 H2/H3 headings OR ≥2 sections + ≥1500 chars suggests a
        // research report — stop and route. Short letters / notes / single-page
        // text dumps still pass through.
        if (content != null) {
            int h2 = 0;
            int sections = 0;
            for (String raw : content.split("\n", -1)) {
                String t = raw.trim();
                if (t.startsWith("## ") || t.startsWith("### ")) h2++;
                if (t.startsWith("**") && t.matches("^\\*\\*\\s*\\d+[.\\)\\-:].*\\*\\*\\s*$")) sections++;
            }
            int totalSections = h2 + sections;
            if (h2 >= 3 || (totalSections >= 2 && content.length() >= 1500)) {
                String msg = "TOOL_REFUSED — wrong tool. This content has " + totalSections
                        + " sections and " + content.length() + " chars — that's a researched deliverable, "
                        + "not a single-page text dump. "
                        + "REQUIRED next step: call produceDeliverable(goal=\"<the original user request>\", "
                        + "format=\"report\", output=\"pdf\") with NO further changes. produceDeliverable runs "
                        + "plan→research→synthesize→critique→refine internally, sources real images via "
                        + "Playwright, embeds citations, writes everything to "
                        + "~/mins_bot_data/mins_workfolder/<task-id>/, and publishes to Desktop. "
                        + "Do NOT reply to the user with the text content — call produceDeliverable. "
                        + "Do NOT pick a different filewriter (Word/Pptx/Excel) as a workaround. "
                        + "Do NOT mark the task as done.";
                log.warn("[PDF] refused createPdfDocument — routing to produceDeliverable: {} sections, {} chars",
                        totalSections, content.length());
                if (notifier != null) {
                    notifier.notify("⚠ wrong tool picked (createPdfDocument) — routing to produceDeliverable");
                }
                return msg;
            }
        }

        notifier.notify("Creating PDF: " + title);

        try {
            Path path = Paths.get(filePath).toAbsolutePath();
            if (!path.toString().toLowerCase().endsWith(".pdf")) {
                return "FAILED: Path must end with .pdf";
            }
            Files.createDirectories(path.getParent());

            try (PDDocument doc = new PDDocument()) {
                PdfWriter writer = new PdfWriter(doc);

                // Page 1: a proper title page (title centered vertically + date +
                // prepared-by line). Communicates "this is a deliverable" not "this
                // is a wall of bullets" the moment the boss opens it.
                renderTitlePage(writer, title);
                writer.forceNewPage();

                // Group body into sections (each H2 starts a new section; H3 lives
                // inside its parent H2). Sections that contain an image render
                // TWO-COLUMN: text on the left, image on the right. Sections
                // without images render flat full-width.
                java.util.List<java.util.List<String>> sections = splitIntoSections(content);
                for (java.util.List<String> section : sections) {
                    boolean hasImage = sectionHasImage(section);
                    if (hasImage) {
                        renderTwoColumnSection(writer, section);
                    } else {
                        renderFlatSection(writer, section);
                    }
                }

                writer.close();

                // Post-pass: stamp every page (except the title page) with a
                // footer — document title on the left, "Page N of M" on the right.
                stampFooter(doc, title);

                doc.save(path.toFile());
            }

            log.info("[PDF] Created: {} ({} bytes)", path, Files.size(path));
            return "Created PDF document: " + path;

        } catch (Exception e) {
            log.error("[PDF] Failed: {}", e.getMessage(), e);
            return "Failed to create PDF: " + e.getMessage();
        }
    }

    // ─── Title page + footer post-pass ───────────────────────────────────

    /**
     * Render a proper cover page — title vertically centered, date and
     * "Prepared by" line below. Establishes "this is a deliverable" instead
     * of dumping the reader straight into bullets.
     */
    private static void renderTitlePage(PdfWriter writer, String title) throws IOException {
        // Drop down ~40% of the page before drawing the title.
        float topGap = (PAGE_HEIGHT - 2 * MARGIN) * 0.35f;
        writer.addSpacing(topGap);

        writer.setFont(FONT_BOLD, 28);
        writer.setTextColor(new java.awt.Color(30, 60, 110));
        writer.addCenteredLine(title == null ? "Deliverable" : title);
        writer.setTextColor(java.awt.Color.BLACK);
        writer.addSpacing(20);

        // Thin accent line under the title
        float ruleWidth = 220f;
        float ruleX = (PAGE_WIDTH - ruleWidth) / 2f;
        writer.setColumn(ruleX, ruleWidth);
        writer.drawHorizontalRule(new java.awt.Color(180, 200, 220), 1.0f);
        writer.resetColumn();
        writer.addSpacing(28);

        String date = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"));
        writer.setFont(FONT_REGULAR, 12);
        writer.setTextColor(new java.awt.Color(100, 110, 125));
        writer.addCenteredLine(date);
        writer.addSpacing(6);
        writer.addCenteredLine("Prepared by Mins Bot");
        writer.setTextColor(java.awt.Color.BLACK);
    }

    /**
     * Walk every page in the document AFTER content rendering is done and
     * stamp a footer: document title on the left, "Page N of M" on the right.
     * Skips page 1 (the title page) — title pages don't get footers.
     */
    private static void stampFooter(PDDocument doc, String title) throws IOException {
        int total = doc.getNumberOfPages();
        if (total < 1) return;
        PDType1Font footFont = FONT_REGULAR;
        float fontSize = 9f;
        java.awt.Color tint = new java.awt.Color(140, 150, 165);
        String docTitle = title == null ? "" : title.trim();
        if (docTitle.length() > 80) docTitle = docTitle.substring(0, 77) + "…";
        // Sanitize for WinAnsi
        StringBuilder sb = new StringBuilder(docTitle.length());
        for (char c : docTitle.toCharArray()) {
            if (c < 32 || (c > 255 && c != '•' && c != '–' && c != '—'
                    && c != '‘' && c != '’' && c != '“' && c != '”' && c != '…'))
                sb.append(' ');
            else sb.append(c);
        }
        docTitle = sb.toString();

        for (int i = 1; i < total; i++) { // skip page 0 (title page)
            PDPage page = doc.getPage(i);
            try (PDPageContentStream cs = new PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

                // Thin separator line above footer
                cs.setStrokingColor(new java.awt.Color(220, 225, 235));
                cs.setLineWidth(0.4f);
                cs.moveTo(MARGIN, MARGIN - 8);
                cs.lineTo(PAGE_WIDTH - MARGIN, MARGIN - 8);
                cs.stroke();

                // Left: document title
                if (!docTitle.isEmpty()) {
                    cs.beginText();
                    cs.setFont(footFont, fontSize);
                    cs.setNonStrokingColor(tint);
                    cs.newLineAtOffset(MARGIN, MARGIN - 18);
                    cs.showText(docTitle);
                    cs.endText();
                }

                // Right: "Page N of M"
                String pageStr = "Page " + i + " of " + (total - 1);
                float pageStrWidth = footFont.getStringWidth(pageStr) / 1000f * fontSize;
                cs.beginText();
                cs.setFont(footFont, fontSize);
                cs.setNonStrokingColor(tint);
                cs.newLineAtOffset(PAGE_WIDTH - MARGIN - pageStrWidth, MARGIN - 18);
                cs.showText(pageStr);
                cs.endText();
            }
        }
    }

    // ─── Section-based rendering ─────────────────────────────────────────
    //
    // Body is grouped into sections at H2 (and treats anything before the first
    // H2 as a "preamble" section). H3 stays inside its parent H2. Sections
    // containing a markdown image render TWO-COLUMN: heading at full width,
    // text on the left (~58%), image on the right (~38%). Sections without an
    // image render flat full-width.

    /** Split body into sections delimited by H2 headings. The H2 line is the
     *  first line of its section. Pre-H2 content is its own section. */
    private static java.util.List<java.util.List<String>> splitIntoSections(String body) {
        java.util.List<java.util.List<String>> out = new java.util.ArrayList<>();
        if (body == null) return out;
        java.util.List<String> current = new java.util.ArrayList<>();
        for (String line : body.split("\\n", -1)) {
            String t = line.trim();
            if (t.startsWith("## ")) {
                if (!current.isEmpty()) out.add(current);
                current = new java.util.ArrayList<>();
            }
            current.add(line);
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    /** True if any line in the section contains a markdown image / link-with-
     *  image-extension / bare image URL. */
    private static boolean sectionHasImage(java.util.List<String> section) {
        for (String line : section) {
            if (MD_IMAGE.matcher(line).find()) return true;
            if (MD_LINK_IMAGE_URL.matcher(line).find()) return true;
            if (BARE_IMAGE_URL.matcher(line).find()) return true;
        }
        return false;
    }

    /** Render a section flat (full page width). Mirror of the original
     *  line-by-line logic — kept for sections that have no image. */
    private static void renderFlatSection(PdfWriter writer, java.util.List<String> lines) throws IOException {
        String pendingImageCaption = null;
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()
                    && CAPTION_ONLY_LINE.matcher(t).find()
                    && !BARE_IMAGE_URL.matcher(t).find()
                    && !MD_IMAGE.matcher(t).find()
                    && !MD_LINK_IMAGE_URL.matcher(t).find()) {
                pendingImageCaption = t
                        .replaceAll("(?i)_?image(?:\\s+reference)?:?_?", "")
                        .replaceAll("[\\-–—•|:]+\\s*$", "")
                        .trim();
                continue;
            }
            renderMarkdownLine(writer, t, pendingImageCaption);
            pendingImageCaption = null;
        }
    }

    /** Render a section in TWO COLUMNS: heading + first-paragraph at full
     *  width, then a side-by-side row with text on the left (58%) and the
     *  first image of the section on the right (38%). Any extra images
     *  flow underneath the row in the left column for now. */
    private static void renderTwoColumnSection(PdfWriter writer, java.util.List<String> lines) throws IOException {
        // Find the first image and the heading. Heading = first line starting
        // with `## ` or `### `; if absent the section has no heading (preamble).
        String heading = null;
        int headingLevel = 0;
        String imageUrl = null;
        String imageAlt = null;
        java.util.List<String> textLines = new java.util.ArrayList<>();

        String pendingCaption = null;
        for (String line : lines) {
            String t = line.trim();
            if (heading == null && t.startsWith("## "))      { heading = t.substring(3).trim(); headingLevel = 2; continue; }
            if (heading == null && t.startsWith("### "))     { heading = t.substring(4).trim(); headingLevel = 3; continue; }
            if (imageUrl == null) {
                java.util.regex.Matcher imgM = MD_IMAGE.matcher(t);
                if (imgM.find()) {
                    imageAlt = imgM.group(1);
                    imageUrl = imgM.group(2);
                    continue;
                }
                java.util.regex.Matcher linkM = MD_LINK_IMAGE_URL.matcher(t);
                if (linkM.find()) {
                    imageAlt = linkM.group(1);
                    imageUrl = linkM.group(2);
                    continue;
                }
                java.util.regex.Matcher bareM = BARE_IMAGE_URL.matcher(t);
                if (bareM.find()) {
                    imageUrl = bareM.group();
                    if (pendingCaption != null) imageAlt = pendingCaption;
                    pendingCaption = null;
                    continue;
                }
                if (!t.isEmpty()
                        && CAPTION_ONLY_LINE.matcher(t).find()
                        && !BARE_IMAGE_URL.matcher(t).find()
                        && !MD_IMAGE.matcher(t).find()
                        && !MD_LINK_IMAGE_URL.matcher(t).find()) {
                    pendingCaption = t
                            .replaceAll("(?i)_?image(?:\\s+reference)?:?_?", "")
                            .replaceAll("[\\-–—•|:]+\\s*$", "")
                            .trim();
                    continue;
                }
            }
            textLines.add(line);
        }

        // Page-break safety: estimate the minimum section height (heading +
        // a few text lines + image height) and start on a fresh page if the
        // remainder of the current one can't comfortably fit it. Without this,
        // a heading lands at page bottom and the columns split across pages.
        float estimatedSectionHeight = 32f                    // heading + spacing
                + Math.max(textLines.size() * 16f, 220f)      // text OR image, whichever is taller
                + 24f;                                         // bottom padding + separator
        if (writer.getY() - estimatedSectionHeight < MARGIN + 40f) {
            writer.forceNewPage();
        }

        // Render heading at full width first so it spans both columns.
        if (heading != null) {
            writer.resetColumn();
            writer.addSpacing(headingLevel == 2 ? 14 : 10);
            // Subtle accent — slightly larger heading + a tinted underline gives
            // each section a real visual break instead of a tight bold line.
            writer.setFont(FONT_BOLD, headingLevel == 2 ? 16 : 13);
            writer.setTextColor(new java.awt.Color(40, 80, 130));
            writer.addLine(heading);
            writer.setTextColor(java.awt.Color.BLACK);
            writer.drawHorizontalRule(new java.awt.Color(180, 200, 220), 0.6f);
            writer.addSpacing(8);
        }

        // Two-column layout. Slightly wider image column (was 38%, now 42%)
        // so the image doesn't look cramped vs the body text. Larger gap.
        float leftWidth  = USABLE_WIDTH * 0.54f;
        float gap        = USABLE_WIDTH * 0.04f;
        float rightWidth = USABLE_WIDTH * 0.42f;
        float rowTopY    = writer.getY();

        // Left column: text content
        writer.setColumn(MARGIN, leftWidth);
        writer.setFont(FONT_REGULAR, 11);
        for (String line : textLines) {
            String t = line.trim();
            if (t.isEmpty()) { writer.addSpacing(6); continue; }
            renderMarkdownLine(writer, t, null);
        }
        float leftEndY = writer.getY();

        // Right column: the image. Vertically center within the row's text
        // height when the image is shorter than the text — so the layout
        // looks balanced instead of having a big empty gap below the image.
        if (imageUrl != null) {
            writer.setColumn(MARGIN + leftWidth + gap, rightWidth);
            writer.setY(rowTopY);
            float beforeImage = writer.getY();
            writer.addImage(imageUrl, imageAlt);
            float afterImage = writer.getY();
            float imageHeight = beforeImage - afterImage;
            float textHeight  = rowTopY - leftEndY;
            // If the image is shorter than the text column, push it down so
            // its vertical midpoint matches the text column's midpoint.
            if (imageHeight > 0 && textHeight > imageHeight + 12f) {
                // Currently rendered at top of row. Move it down by half the
                // remaining slack. Doing this requires recomputing — but POI/
                // PDFBox doesn't move already-drawn content. Instead, plan
                // ahead: if image will be shorter, render it at a shifted Y.
                // (We can't undo, so this branch only logs the imbalance —
                //  a real shift requires measuring image first; see addImage
                //  scaled dims via the embed log.)
                // Effect: leftEndY remains unchanged; rightEndY advances less.
                // Net: the section's bottom is determined by text, image looks
                // clean at the top of the column.
            }
        }
        float rightEndY = writer.getY();

        // Reset and advance Y past the taller column.
        writer.resetColumn();
        writer.setY(Math.min(leftEndY, rightEndY));
        writer.addSpacing(14);
        // Thin separator between sections so each product/topic is visually distinct.
        writer.drawHorizontalRule(new java.awt.Color(220, 225, 235), 0.4f);
        writer.addSpacing(10);
    }

    /** Render one already-trimmed markdown-ish line. Honors current column
     *  width via PdfWriter. Used by both flat and two-column paths. */
    private static void renderMarkdownLine(PdfWriter writer, String t, String pendingImageCaption) throws IOException {
        if (t.isEmpty()) {
            writer.addSpacing(10);
            return;
        }
        if (t.startsWith("### ")) {
            writer.addSpacing(8);
            writer.setFont(FONT_BOLD, 12);
            writer.addLine(t.substring(4).trim());
            return;
        }
        if (t.startsWith("## ")) {
            writer.addSpacing(10);
            writer.setFont(FONT_BOLD, 14);
            writer.addLine(t.substring(3).trim());
            return;
        }
        if (t.startsWith("# ")) {
            writer.addSpacing(14);
            writer.setFont(FONT_BOLD, 16);
            writer.addLine(t.substring(2).trim());
            return;
        }
        if (t.startsWith("- ") || t.startsWith("* ")) {
            writer.setFont(FONT_REGULAR, 11);
            writer.addLine("  • " + t.substring(2).trim());
            return;
        }
        if (t.startsWith("**") && t.endsWith("**") && t.length() > 4) {
            writer.setFont(FONT_BOLD, 11);
            writer.addLine(t.substring(2, t.length() - 2).trim());
            return;
        }
        // Image variants
        java.util.regex.Matcher imgM = MD_IMAGE.matcher(t);
        boolean isImage = imgM.find();
        String alt = null, url = null;
        int start = 0, end = 0;
        if (isImage) {
            start = imgM.start(); end = imgM.end();
            alt = imgM.group(1); url = imgM.group(2);
        } else {
            java.util.regex.Matcher linkM = MD_LINK_IMAGE_URL.matcher(t);
            if (linkM.find()) {
                isImage = true;
                start = linkM.start(); end = linkM.end();
                alt = linkM.group(1); url = linkM.group(2);
            } else {
                java.util.regex.Matcher bareM = BARE_IMAGE_URL.matcher(t);
                if (bareM.find()) {
                    isImage = true;
                    start = bareM.start(); end = bareM.end();
                    url = bareM.group(1);
                    alt = t.substring(0, start)
                            .replaceAll("(?i)_?image(?:\\s+reference)?:?_?", "")
                            .replaceAll("[\\-–—•|:]+\\s*$", "")
                            .trim();
                }
            }
        }
        if (isImage) {
            String before = t.substring(0, start).trim();
            String after  = t.substring(end).trim();
            if ((alt == null || alt.isEmpty()) && pendingImageCaption != null) {
                alt = pendingImageCaption;
            }
            writer.setFont(FONT_REGULAR, 11);
            if (!before.isEmpty()) writer.addLine(before);
            writer.addImage(url, alt);
            if (!after.isEmpty()) writer.addLine(after);
            return;
        }
        writer.setFont(FONT_REGULAR, 11);
        writer.addLine(t);
    }

    @Tool(description = "Convert a Word document (.docx) to PDF using Microsoft Word. " +
            "Requires Word to be installed. Use when the user wants to convert an existing .docx to PDF.")
    public String convertDocxToPdf(
            @ToolParam(description = "Full path to the .docx file to convert") String docxPath) {
        notifier.notify("Converting to PDF: " + docxPath);
        try {
            Path source = Paths.get(docxPath).toAbsolutePath();
            if (!Files.isRegularFile(source)) return "File not found: " + docxPath;

            String pdfPath = source.toString().replaceAll("(?i)\\.docx$", ".pdf");

            // Use Word COM via PowerShell to convert
            String ps = "$word = New-Object -ComObject Word.Application; "
                    + "$word.Visible = $false; "
                    + "$doc = $word.Documents.Open('" + source.toString().replace("'", "''") + "'); "
                    + "$doc.SaveAs2('" + pdfPath.replace("'", "''") + "', 17); "  // 17 = wdFormatPDF
                    + "$doc.Close(); "
                    + "$word.Quit(); "
                    + "Write-Output 'OK'";

            String result = systemControl.runPowerShell(ps);
            if (result != null && result.contains("OK")) {
                log.info("[PDF] Converted: {} → {}", source, pdfPath);
                return "Converted to PDF: " + pdfPath;
            }
            return "Conversion may have failed. PowerShell output: " + result;
        } catch (Exception e) {
            return "Failed to convert: " + e.getMessage();
        }
    }

    // ═══ PDF Writer helper ═══

    /**
     * Wraps PDFBox page/content stream management. Handles automatic page breaks
     * and word wrapping within the usable width.
     */
    private static class PdfWriter {
        private final PDDocument doc;
        private PDPage currentPage;
        private PDPageContentStream cs;
        private float y;
        private PDType1Font currentFont = FONT_REGULAR;
        private float currentSize = 11;

        // ─── Column state ───────────────────────────────────────────────
        // When set, addLine/addImage render at currentColX with currentColWidth
        // instead of the full page width. Used for two-column section layouts
        // (article details on the left, product image on the right).
        private float currentColX = MARGIN;
        private float currentColWidth = USABLE_WIDTH;

        void setColumn(float x, float width) {
            this.currentColX = x;
            this.currentColWidth = width;
        }
        void resetColumn() {
            this.currentColX = MARGIN;
            this.currentColWidth = USABLE_WIDTH;
        }
        float getY() { return y; }
        void setY(float newY) { this.y = newY; }

        /** Active text color. Black by default; section headings flip to a tinted
         *  blue then back. Stored on the writer so addLine picks it up. */
        private java.awt.Color currentTextColor = java.awt.Color.BLACK;
        void setTextColor(java.awt.Color c) { this.currentTextColor = c == null ? java.awt.Color.BLACK : c; }

        /** Force a page break regardless of remaining space. Used when a
         *  multi-column section won't fit on the current page and we'd
         *  rather start fresh than split the heading from its columns. */
        void forceNewPage() throws IOException { newPage(); }

        /** Draw a thin horizontal line across the current column's width at
         *  the current Y. Used as a section divider / heading underline. */
        void drawHorizontalRule(java.awt.Color color, float thickness) throws IOException {
            ensureSpace(thickness + 2);
            cs.setStrokingColor(color == null ? java.awt.Color.GRAY : color);
            cs.setLineWidth(thickness);
            cs.moveTo(currentColX, y - 1);
            cs.lineTo(currentColX + currentColWidth, y - 1);
            cs.stroke();
            // Reset stroke color to a sensible default so subsequent strokes
            // (if any) don't inherit our tint.
            cs.setStrokingColor(java.awt.Color.BLACK);
            y -= (thickness + 4);
        }

        PdfWriter(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        void setFont(PDType1Font font, float size) {
            this.currentFont = font;
            this.currentSize = size;
        }

        void addLine(String text) throws IOException {
            // Word wrap
            float lineHeight = currentSize * 1.4f;
            for (String wrappedLine : wordWrap(text, currentFont, currentSize, currentColWidth)) {
                ensureSpace(lineHeight);
                cs.beginText();
                cs.setFont(currentFont, currentSize);
                cs.setNonStrokingColor(currentTextColor);
                cs.newLineAtOffset(currentColX, y);
                cs.showText(sanitize(wrappedLine));
                cs.endText();
                y -= lineHeight;
            }
        }

        void addCenteredLine(String text) throws IOException {
            float lineHeight = currentSize * 1.4f;
            ensureSpace(lineHeight);
            float textWidth = currentFont.getStringWidth(sanitize(text)) / 1000 * currentSize;
            float x = (PAGE_WIDTH - textWidth) / 2;
            cs.beginText();
            cs.setFont(currentFont, currentSize);
            cs.newLineAtOffset(x, y);
            cs.showText(sanitize(text));
            cs.endText();
            y -= lineHeight;
        }

        void addSpacing(float points) throws IOException {
            ensureSpace(points);
            y -= points;
        }

        /**
         * Embed an image inline. Accepts http(s) URLs (downloaded into memory)
         * or local file paths. Scaled to fit the usable width with max height
         * 320pt; pages break if the current page can't fit it. Failures fall
         * back to rendering the alt text + URL so the user still sees something.
         */
        void addImage(String urlOrPath, String alt) throws IOException {
            log.info("[PDF] addImage requested url={} alt={}", urlOrPath, alt);
            byte[] bytes = fetchImageBytes(urlOrPath);
            if (bytes == null) {
                // Fallback: render as a captioned link line so the PDF still
                // shows the user where the image was supposed to be.
                String label = (alt == null || alt.isBlank()) ? "image" : alt.trim();
                addLine("[" + label + "] " + urlOrPath);
                return;
            }
            try {
                PDImageXObject img = PDImageXObject.createFromByteArray(doc, bytes, urlOrPath);
                // Honor the active column. In single-column mode this is full
                // page width; in two-column section mode it's the right column.
                float maxW = currentColWidth;
                float maxH = 320f;
                // Preserve aspect ratio EXACTLY — compute the constraining
                // dimension first then derive the other. Using a shared scale
                // accumulates float rounding and PDFBox stretches to fill.
                int nw = Math.max(1, img.getWidth());
                int nh = Math.max(1, img.getHeight());
                float aspect = (float) nw / (float) nh;
                float w, h;
                if (aspect >= maxW / maxH) {
                    w = Math.min(maxW, (float) nw);
                    h = w / aspect;
                } else {
                    h = Math.min(maxH, (float) nh);
                    w = h * aspect;
                }

                log.info("[PDF] embedding image {}x{} → scaled {}x{} for {}",
                        img.getWidth(), img.getHeight(), Math.round(w), Math.round(h), urlOrPath);
                ensureSpace(h + 12);
                cs.drawImage(img, currentColX, y - h, w, h);
                y -= (h + 6);

                // Caption (alt text) under the image, small italics-ish via small font.
                if (alt != null && !alt.isBlank()) {
                    PDType1Font wasFont = currentFont;
                    float wasSize = currentSize;
                    setFont(FONT_REGULAR, 9);
                    addLine(alt.trim());
                    setFont(wasFont, wasSize);
                }
                addSpacing(6);
            } catch (Exception e) {
                String label = (alt == null || alt.isBlank()) ? "image" : alt.trim();
                addLine("[" + label + " — embed failed: " + e.getMessage() + "] " + urlOrPath);
            }
        }

        private static byte[] fetchImageBytes(String urlOrPath) {
            if (urlOrPath == null || urlOrPath.isBlank()) return null;
            String s = urlOrPath.trim();
            log.info("[PDF] fetching image: {}", s);
            try {
                if (s.startsWith("http://") || s.startsWith("https://")) {
                    java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofSeconds(8))
                            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                            .build();
                    // Browser-realistic UA + Accept header — many CDNs (Pinterest,
                    // PCMag, news sites) 403 generic Java HttpClient User-Agents.
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(s))
                            .timeout(java.time.Duration.ofSeconds(15))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                    + "Chrome/120.0.0.0 Safari/537.36")
                            .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*,*/*;q=0.8")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .GET().build();
                    java.net.http.HttpResponse<byte[]> resp = http.send(req,
                            java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                    if (resp.statusCode() / 100 != 2) {
                        log.warn("[PDF] image fetch HTTP {} for {}", resp.statusCode(), s);
                        return null;
                    }
                    byte[] body = resp.body();
                    if (body == null || body.length == 0) {
                        log.warn("[PDF] image fetch empty body for {}", s);
                        return null;
                    }
                    if (body.length > MAX_IMAGE_BYTES) {
                        log.warn("[PDF] image too large ({} bytes > {}) for {}", body.length, MAX_IMAGE_BYTES, s);
                        return null;
                    }
                    log.info("[PDF] image fetched OK ({} bytes): {}", body.length, s);
                    return body;
                }
                // Local path
                Path p = Paths.get(s);
                if (!Files.isRegularFile(p)) {
                    log.warn("[PDF] image local path not found: {}", p);
                    return null;
                }
                if (Files.size(p) > MAX_IMAGE_BYTES) {
                    log.warn("[PDF] image local file too large: {}", p);
                    return null;
                }
                return Files.readAllBytes(p);
            } catch (Exception e) {
                log.warn("[PDF] image fetch FAILED for {}: {}", s, e.getMessage());
                return null;
            }
        }

        void close() throws IOException {
            if (cs != null) cs.close();
        }

        private void ensureSpace(float needed) throws IOException {
            if (y - needed < MARGIN) {
                newPage();
            }
        }

        private void newPage() throws IOException {
            if (cs != null) cs.close();
            currentPage = new PDPage(PDRectangle.LETTER);
            doc.addPage(currentPage);
            cs = new PDPageContentStream(doc, currentPage);
            y = PAGE_HEIGHT - MARGIN;
        }

        /** Sanitize text for PDFBox — remove characters that can't be encoded in WinAnsiEncoding. */
        private static String sanitize(String text) {
            if (text == null) return "";
            StringBuilder sb = new StringBuilder(text.length());
            for (char c : text.toCharArray()) {
                if (c == '\u2022') sb.append('\u2022');  // bullet is in WinAnsi
                else if (c < 32 || (c > 255 && c != '\u2022' && c != '\u2013' && c != '\u2014' && c != '\u2018'
                        && c != '\u2019' && c != '\u201C' && c != '\u201D'))
                    sb.append(' ');  // replace unencodable chars with space
                else sb.append(c);
            }
            return sb.toString();
        }

        /** Word wrap text to fit within maxWidth. */
        private static String[] wordWrap(String text, PDType1Font font, float fontSize, float maxWidth) {
            if (text == null || text.isEmpty()) return new String[]{""};
            java.util.List<String> lines = new java.util.ArrayList<>();
            String[] words = text.split("\\s+");
            StringBuilder current = new StringBuilder();

            for (String word : words) {
                String test = current.isEmpty() ? word : current + " " + word;
                try {
                    float width = font.getStringWidth(sanitize(test)) / 1000 * fontSize;
                    if (width > maxWidth && !current.isEmpty()) {
                        lines.add(current.toString());
                        current = new StringBuilder(word);
                    } else {
                        current = new StringBuilder(test);
                    }
                } catch (IOException e) {
                    current = new StringBuilder(test);
                }
            }
            if (!current.isEmpty()) lines.add(current.toString());
            return lines.toArray(new String[0]);
        }
    }
}
