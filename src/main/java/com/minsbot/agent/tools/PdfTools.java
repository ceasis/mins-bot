package com.minsbot.agent.tools;

import com.minsbot.agent.SystemControlService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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

    @Tool(description = "Create a PDF document with a title and body content. " +
            "Use this when the user asks to save results as PDF, create a PDF report, or export to PDF. " +
            "The content parameter supports: lines starting with '# ' become large headings, " +
            "'## ' become medium headings, '- ' become bullet points, '**text**' becomes bold, " +
            "and everything else is normal text. Blank lines add spacing.")
    public String createPdfDocument(
            @ToolParam(description = "Full file path for the PDF, e.g. 'C:\\Users\\user\\Documents\\report.pdf'") String filePath,
            @ToolParam(description = "Document title (shown as the main heading)") String title,
            @ToolParam(description = "Document body content. Use '# ' for headings, '## ' for sub-headings, " +
                    "'- ' for bullet points, '**text**' for bold, blank lines for spacing.") String content) {

        notifier.notify("Creating PDF: " + title);

        try {
            Path path = Paths.get(filePath).toAbsolutePath();
            if (!path.toString().toLowerCase().endsWith(".pdf")) {
                return "FAILED: Path must end with .pdf";
            }
            Files.createDirectories(path.getParent());

            try (PDDocument doc = new PDDocument()) {
                PdfWriter writer = new PdfWriter(doc);

                // Title — large, bold, centered
                writer.setFont(FONT_BOLD, 22);
                writer.addCenteredLine(title);
                writer.addSpacing(20);

                // Parse content
                if (content != null) {
                    for (String line : content.split("\\n")) {
                        String t = line.trim();

                        if (t.isEmpty()) {
                            writer.addSpacing(10);

                        } else if (t.startsWith("### ")) {
                            writer.addSpacing(8);
                            writer.setFont(FONT_BOLD, 12);
                            writer.addLine(t.substring(4).trim());

                        } else if (t.startsWith("## ")) {
                            writer.addSpacing(10);
                            writer.setFont(FONT_BOLD, 14);
                            writer.addLine(t.substring(3).trim());

                        } else if (t.startsWith("# ")) {
                            writer.addSpacing(14);
                            writer.setFont(FONT_BOLD, 16);
                            writer.addLine(t.substring(2).trim());

                        } else if (t.startsWith("- ") || t.startsWith("* ")) {
                            writer.setFont(FONT_REGULAR, 11);
                            writer.addLine("  \u2022 " + t.substring(2).trim());

                        } else if (t.startsWith("**") && t.endsWith("**") && t.length() > 4) {
                            writer.setFont(FONT_BOLD, 11);
                            writer.addLine(t.substring(2, t.length() - 2).trim());

                        } else {
                            writer.setFont(FONT_REGULAR, 11);
                            writer.addLine(t);
                        }
                    }
                }

                writer.close();
                doc.save(path.toFile());
            }

            log.info("[PDF] Created: {} ({} bytes)", path, Files.size(path));
            return "Created PDF document: " + path;

        } catch (Exception e) {
            log.error("[PDF] Failed: {}", e.getMessage(), e);
            return "Failed to create PDF: " + e.getMessage();
        }
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
            for (String wrappedLine : wordWrap(text, currentFont, currentSize, USABLE_WIDTH)) {
                ensureSpace(lineHeight);
                cs.beginText();
                cs.setFont(currentFont, currentSize);
                cs.newLineAtOffset(MARGIN, y);
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
