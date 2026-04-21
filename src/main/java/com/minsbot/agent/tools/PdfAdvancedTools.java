package com.minsbot.agent.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF manipulation tools: merge multiple PDFs, split one into ranges,
 * extract pages, rotate. Uses Apache PDFBox (already a dependency).
 */
@Component
public class PdfAdvancedTools {

    private static final Logger log = LoggerFactory.getLogger(PdfAdvancedTools.class);

    private final ToolExecutionNotifier notifier;

    public PdfAdvancedTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    // ─── Merge ───────────────────────────────────────────────────

    @Tool(description = "Merge multiple PDF files into a single PDF. "
            + "Use when the user says 'merge these PDFs', 'combine these PDF files', 'join PDFs'. "
            + "Pass the absolute paths pipe-separated (|), in the order you want them merged.")
    public String mergePdfs(
            @ToolParam(description = "Pipe-separated absolute paths to the input PDF files, in merge order, e.g. 'C:/a.pdf|C:/b.pdf|C:/c.pdf'") String inputPaths,
            @ToolParam(description = "Absolute path for the merged output PDF") String outputPath) {
        if (inputPaths == null || inputPaths.isBlank()) return "Provide at least two PDF paths, pipe-separated.";
        if (outputPath == null || outputPath.isBlank()) return "Provide an output path.";
        String[] parts = inputPaths.split("\\|");
        if (parts.length < 2) return "Need at least 2 PDFs to merge.";

        notifier.notify("Merging " + parts.length + " PDFs...");
        try {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (String p : parts) {
                File f = new File(p.trim());
                if (!f.isFile()) return "File not found: " + p.trim();
                merger.addSource(f);
            }
            Path out = Path.of(outputPath.trim());
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            merger.setDestinationFileName(out.toAbsolutePath().toString());
            merger.mergeDocuments(org.apache.pdfbox.io.IOUtils.createMemoryOnlyStreamCache());
            return "✅ Merged " + parts.length + " PDFs → " + out + " (" + Files.size(out) + " bytes)";
        } catch (Exception e) {
            log.warn("[PDF] merge failed: {}", e.getMessage());
            return "Merge failed: " + e.getMessage();
        }
    }

    // ─── Split ───────────────────────────────────────────────────

    @Tool(description = "Split a PDF into one or more new PDFs by page ranges. "
            + "Example ranges: '1-3,4-7,8-10' creates 3 files containing those page sets. "
            + "Use when the user says 'split this PDF into chapters', 'extract pages 1-10 as a new PDF'.")
    public String splitPdf(
            @ToolParam(description = "Absolute path to the input PDF") String inputPath,
            @ToolParam(description = "Comma-separated page ranges, 1-indexed, e.g. '1-3,4-7,8-10' or '1-5,8,10-12'") String rangesSpec,
            @ToolParam(description = "Directory where split output files will be saved") String outputDir) {
        if (inputPath == null || !new File(inputPath).isFile()) return "Input PDF not found.";
        if (rangesSpec == null || rangesSpec.isBlank()) return "Provide page ranges (e.g. '1-3,4-7').";
        if (outputDir == null || outputDir.isBlank()) return "Provide an output directory.";

        notifier.notify("Splitting PDF...");
        try (PDDocument src = Loader.loadPDF(new File(inputPath))) {
            int total = src.getNumberOfPages();
            Path dir = Path.of(outputDir.trim());
            Files.createDirectories(dir);

            String base = new File(inputPath).getName().replaceFirst("\\.[^.]+$", "");
            List<String> created = new ArrayList<>();
            int idx = 0;
            for (String chunk : rangesSpec.split(",")) {
                chunk = chunk.trim();
                if (chunk.isEmpty()) continue;
                int[] range = parseRange(chunk, total);
                if (range == null) return "Bad range: '" + chunk + "' (must be 1-indexed, within 1.." + total + ")";
                idx++;
                try (PDDocument out = new PDDocument()) {
                    for (int p = range[0]; p <= range[1]; p++) {
                        out.importPage(src.getPage(p - 1));
                    }
                    Path outPath = dir.resolve(base + "_part" + idx + "_p" + range[0] + "-" + range[1] + ".pdf");
                    out.save(outPath.toFile());
                    created.add(outPath.toString());
                }
            }
            if (created.isEmpty()) return "No output files produced — check the range spec.";
            StringBuilder sb = new StringBuilder("✅ Split into " + created.size() + " file(s):\n");
            for (String c : created) sb.append("• ").append(c).append("\n");
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[PDF] split failed: {}", e.getMessage());
            return "Split failed: " + e.getMessage();
        }
    }

    // ─── Extract specific pages ─────────────────────────────────

    @Tool(description = "Extract specific pages from a PDF into a new single PDF. "
            + "Use when the user says 'extract pages 5-8 from this PDF', 'keep only these pages'.")
    public String extractPages(
            @ToolParam(description = "Absolute path to the source PDF") String inputPath,
            @ToolParam(description = "Page spec — a single range like '5-8' or a list like '1,3,5-7'") String pageSpec,
            @ToolParam(description = "Absolute path for the output PDF") String outputPath) {
        if (inputPath == null || !new File(inputPath).isFile()) return "Input PDF not found.";
        if (pageSpec == null || pageSpec.isBlank()) return "Provide page spec (e.g. '5-8' or '1,3,5-7').";
        if (outputPath == null || outputPath.isBlank()) return "Provide output path.";

        notifier.notify("Extracting pages...");
        try (PDDocument src = Loader.loadPDF(new File(inputPath));
             PDDocument out = new PDDocument()) {
            int total = src.getNumberOfPages();
            int extracted = 0;
            for (String chunk : pageSpec.split(",")) {
                chunk = chunk.trim();
                if (chunk.isEmpty()) continue;
                int[] range = parseRange(chunk, total);
                if (range == null) return "Bad page spec: '" + chunk + "'";
                for (int p = range[0]; p <= range[1]; p++) {
                    out.importPage(src.getPage(p - 1));
                    extracted++;
                }
            }
            if (extracted == 0) return "No pages extracted.";
            Path outP = Path.of(outputPath.trim());
            if (outP.getParent() != null) Files.createDirectories(outP.getParent());
            out.save(outP.toFile());
            return "✅ Extracted " + extracted + " page(s) → " + outP;
        } catch (Exception e) {
            return "Extract failed: " + e.getMessage();
        }
    }

    // ─── Rotate ─────────────────────────────────────────────────

    @Tool(description = "Rotate all (or selected) pages of a PDF by 90, 180, or 270 degrees. "
            + "Use when the user says 'rotate this PDF', 'fix the orientation of my scan'.")
    public String rotatePdf(
            @ToolParam(description = "Absolute path to the source PDF") String inputPath,
            @ToolParam(description = "Rotation in degrees: 90, 180, or 270") int degrees,
            @ToolParam(description = "Absolute path for the rotated output PDF") String outputPath) {
        if (inputPath == null || !new File(inputPath).isFile()) return "Input PDF not found.";
        if (degrees != 90 && degrees != 180 && degrees != 270) return "Degrees must be 90, 180, or 270.";
        notifier.notify("Rotating PDF by " + degrees + "°...");
        try (PDDocument doc = Loader.loadPDF(new File(inputPath))) {
            for (PDPage page : doc.getPages()) {
                int current = page.getRotation();
                page.setRotation((current + degrees) % 360);
            }
            Path out = Path.of(outputPath.trim());
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            doc.save(out.toFile());
            return "✅ Rotated " + doc.getNumberOfPages() + " pages by " + degrees + "° → " + out;
        } catch (Exception e) {
            return "Rotate failed: " + e.getMessage();
        }
    }

    @Tool(description = "Get metadata about a PDF: page count, producer, author, title, creation date, encrypted status.")
    public String pdfInfo(
            @ToolParam(description = "Absolute path to the PDF") String inputPath) {
        if (inputPath == null || !new File(inputPath).isFile()) return "PDF not found.";
        try (PDDocument doc = Loader.loadPDF(new File(inputPath))) {
            var info = doc.getDocumentInformation();
            StringBuilder sb = new StringBuilder();
            sb.append("Pages: ").append(doc.getNumberOfPages()).append("\n");
            sb.append("Encrypted: ").append(doc.isEncrypted()).append("\n");
            if (info != null) {
                if (info.getTitle() != null)    sb.append("Title: ").append(info.getTitle()).append("\n");
                if (info.getAuthor() != null)   sb.append("Author: ").append(info.getAuthor()).append("\n");
                if (info.getProducer() != null) sb.append("Producer: ").append(info.getProducer()).append("\n");
                if (info.getCreator() != null)  sb.append("Creator: ").append(info.getCreator()).append("\n");
                if (info.getCreationDate() != null) sb.append("Created: ").append(info.getCreationDate().getTime()).append("\n");
            }
            sb.append("Size: ").append(Files.size(Path.of(inputPath))).append(" bytes");
            return sb.toString();
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    // ─── Internals ──────────────────────────────────────────────

    /** Parse '5' → {5,5}, '3-7' → {3,7}. Returns null if invalid. */
    private static int[] parseRange(String s, int maxPage) {
        try {
            s = s.trim();
            if (s.contains("-")) {
                String[] parts = s.split("-", 2);
                int a = Integer.parseInt(parts[0].trim());
                int b = Integer.parseInt(parts[1].trim());
                if (a < 1 || b > maxPage || a > b) return null;
                return new int[]{a, b};
            }
            int p = Integer.parseInt(s);
            if (p < 1 || p > maxPage) return null;
            return new int[]{p, p};
        } catch (Exception e) {
            return null;
        }
    }
}
