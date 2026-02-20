package com.botsfer.agent.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class PdfTools {

    private final ToolExecutionNotifier notifier;

    public PdfTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
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
}
