package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Create real Word documents (.docx) programmatically.
 *
 * <p>A .docx file is an Office Open XML package (zip archive containing XML).
 * Uses the minimal OOXML structure proven to open in Word, LibreOffice, and Google Docs.
 * No external libraries needed (no Apache POI).</p>
 */
@Component
public class WordDocTools {

    private static final Logger log = LoggerFactory.getLogger(WordDocTools.class);

    private final ToolExecutionNotifier notifier;

    public WordDocTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Create a Word document (.docx) with a title and body content. " +
            "Use this when the user asks to prepare, create, or write a Word document or report. " +
            "The content parameter supports simple formatting: lines starting with '# ' become headings, " +
            "'## ' become sub-headings, '- ' become bullet points, and everything else is normal paragraphs. " +
            "Blank lines create paragraph spacing.")
    public String createWordDocument(
            @ToolParam(description = "Full file path for the document, e.g. 'C:\\Users\\user\\Documents\\report.docx'") String filePath,
            @ToolParam(description = "Document title (shown as the main heading)") String title,
            @ToolParam(description = "Document body content. Use '# ' for headings, '## ' for sub-headings, " +
                    "'- ' for bullet points, blank lines for spacing.") String content) {

        notifier.notify("Creating Word document: " + title);
        return writeDocx(filePath, title, content);
    }

    @Tool(description = "Create a Word document from pre-formatted sections. Each section has a heading and body text. " +
            "Use this for structured reports like travel research, comparisons, or multi-topic documents.")
    public String createWordReport(
            @ToolParam(description = "Full file path ending in .docx") String filePath,
            @ToolParam(description = "Report title") String title,
            @ToolParam(description = "Report sections as text. Separate sections with '---'. " +
                    "Each section: first line is the heading, rest is body text. " +
                    "Example: 'Flight Options\\nCebu Pacific $150\\nPAL $200\\n---\\nHotel Options\\nHotel A $80/night'") String sections) {

        notifier.notify("Creating Word report: " + title);

        // Convert sections format to markdown-style
        StringBuilder markdown = new StringBuilder();
        String[] parts = sections.split("---");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String[] lines = trimmed.split("\\n");
            if (lines.length > 0) {
                markdown.append("# ").append(lines[0].trim()).append("\n");
                for (int i = 1; i < lines.length; i++) {
                    markdown.append(lines[i]).append("\n");
                }
                markdown.append("\n");
            }
        }

        return writeDocx(filePath, title, markdown.toString());
    }

    // ═══ Core docx writer ═══

    private String writeDocx(String filePath, String title, String content) {
        try {
            Path path = Paths.get(filePath).toAbsolutePath();
            if (!path.toString().toLowerCase().endsWith(".docx")) {
                return "FAILED: Path must end with .docx";
            }
            Files.createDirectories(path.getParent());

            String documentXml = buildDocumentXml(title, content);

            try (OutputStream fos = Files.newOutputStream(path);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                writeEntry(zos, "[Content_Types].xml", CONTENT_TYPES);
                writeEntry(zos, "_rels/.rels", RELS);
                writeEntry(zos, "word/document.xml", documentXml);
            }

            log.info("[WordDoc] Created: {} ({} bytes)", path, Files.size(path));
            return "Created Word document: " + path;

        } catch (Exception e) {
            log.error("[WordDoc] Failed: {}", e.getMessage(), e);
            return "Failed to create Word document: " + e.getMessage();
        }
    }

    // ═══ Document XML builder ═══

    private String buildDocumentXml(String title, String content) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">");
        xml.append("<w:body>");

        // Title — big, bold, centered
        xml.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>")
                .append("<w:r><w:rPr><w:b/><w:sz w:val=\"52\"/><w:szCs w:val=\"52\"/></w:rPr>")
                .append("<w:t>").append(esc(title)).append("</w:t></w:r></w:p>");

        // Spacer after title
        xml.append("<w:p/>");

        // Parse content lines
        if (content != null) {
            for (String line : content.split("\\n")) {
                String t = line.trim();

                if (t.isEmpty()) {
                    xml.append("<w:p/>");

                } else if (t.startsWith("### ")) {
                    // Heading 3 — bold, dark, 13pt
                    heading(xml, t.substring(4).trim(), "26", "444444");

                } else if (t.startsWith("## ")) {
                    // Heading 2 — bold, blue, 14pt
                    heading(xml, t.substring(3).trim(), "28", "2E75B6");

                } else if (t.startsWith("# ")) {
                    // Heading 1 — bold, dark blue, 16pt
                    heading(xml, t.substring(2).trim(), "32", "1F4E79");

                } else if (t.startsWith("- ") || t.startsWith("* ")) {
                    // Bullet — indented with bullet character
                    String text = t.substring(2).trim();
                    xml.append("<w:p><w:pPr><w:ind w:left=\"720\"/></w:pPr>")
                            .append("<w:r><w:t xml:space=\"preserve\">\u2022 ")
                            .append(esc(text)).append("</w:t></w:r></w:p>");

                } else if (t.startsWith("**") && t.endsWith("**") && t.length() > 4) {
                    // Bold paragraph
                    String text = t.substring(2, t.length() - 2).trim();
                    xml.append("<w:p><w:r><w:rPr><w:b/></w:rPr>")
                            .append("<w:t>").append(esc(text)).append("</w:t></w:r></w:p>");

                } else {
                    // Normal paragraph
                    xml.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                            .append(esc(t)).append("</w:t></w:r></w:p>");
                }
            }
        }

        // Page setup — Letter size, 1" margins
        xml.append("<w:sectPr>")
                .append("<w:pgSz w:w=\"12240\" w:h=\"15840\"/>")
                .append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"")
                .append(" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>")
                .append("</w:sectPr>");

        xml.append("</w:body></w:document>");
        return xml.toString();
    }

    private void heading(StringBuilder xml, String text, String size, String color) {
        xml.append("<w:p><w:pPr><w:spacing w:before=\"240\" w:after=\"120\"/></w:pPr>")
                .append("<w:r><w:rPr><w:b/><w:sz w:val=\"").append(size).append("\"/>")
                .append("<w:szCs w:val=\"").append(size).append("\"/>")
                .append("<w:color w:val=\"").append(color).append("\"/></w:rPr>")
                .append("<w:t>").append(esc(text)).append("</w:t></w:r></w:p>");
    }

    // ═══ Helpers ═══

    private void writeEntry(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    // ═══ Minimal OOXML package (no styles.xml, no numbering.xml — just the essentials) ═══

    private static final String CONTENT_TYPES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>";

    private static final String RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>";
}
