package com.minsbot.agent.tools;

import com.minsbot.KnowledgeBaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * AI-callable tools to search, list, and read from the user's knowledge base
 * (documents uploaded via the Knowledge Base tab, stored in ~/mins_bot_data/knowledge_base/).
 */
@Component
public class KnowledgeBaseTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTools.class);
    private final ToolExecutionNotifier notifier;

    public KnowledgeBaseTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Search the user's knowledge base for documents containing a keyword or phrase. "
            + "Returns matching file names and relevant snippets. Use when the user asks about topics "
            + "that may be in their uploaded documents, notes, or reference files.")
    public String searchKnowledgeBase(
            @ToolParam(description = "Search query — keyword or phrase to find in uploaded documents") String query) {
        notifier.notify("Searching knowledge base for: " + query);
        if (query == null || query.isBlank()) return "No search query provided.";

        Path kbDir = KnowledgeBaseController.KB_DIR;
        if (!Files.isDirectory(kbDir)) return "Knowledge base is empty. No documents uploaded yet.";

        String lowerQuery = query.toLowerCase();
        List<String> results = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(kbDir)) {
            for (Path file : stream) {
                if (Files.isDirectory(file)) continue;
                try {
                    String content = Files.readString(file);
                    String lower = content.toLowerCase();
                    if (!lower.contains(lowerQuery)) continue;

                    // Find matching snippet (up to 300 chars around first match)
                    int idx = lower.indexOf(lowerQuery);
                    int start = Math.max(0, idx - 100);
                    int end = Math.min(content.length(), idx + query.length() + 200);
                    String snippet = content.substring(start, end).replace("\n", " ").trim();
                    if (start > 0) snippet = "..." + snippet;
                    if (end < content.length()) snippet = snippet + "...";

                    results.add("**" + file.getFileName() + "**: " + snippet);
                } catch (IOException e) {
                    // Binary file or read error — skip
                }
            }
        } catch (IOException e) {
            return "Error searching knowledge base: " + e.getMessage();
        }

        if (results.isEmpty()) return "No matches found for '" + query + "' in the knowledge base.";
        StringBuilder sb = new StringBuilder("Found " + results.size() + " match(es):\n\n");
        for (String r : results) {
            sb.append(r).append("\n\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "List all documents in the user's knowledge base. Shows file names and sizes.")
    public String listKnowledgeBase() {
        notifier.notify("Listing knowledge base documents");
        Path kbDir = KnowledgeBaseController.KB_DIR;
        if (!Files.isDirectory(kbDir)) return "Knowledge base is empty. No documents uploaded yet.";

        List<String> items = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(kbDir)) {
            for (Path file : stream) {
                if (Files.isDirectory(file)) continue;
                long size = Files.size(file);
                items.add("- " + file.getFileName() + " (" + humanSize(size) + ")");
            }
        } catch (IOException e) {
            return "Error listing knowledge base: " + e.getMessage();
        }

        if (items.isEmpty()) return "Knowledge base is empty. No documents uploaded yet.";
        items.sort(String::compareToIgnoreCase);
        return "Knowledge base (" + items.size() + " documents):\n" + String.join("\n", items);
    }

    @Tool(description = "Read the full content of a specific document from the knowledge base. "
            + "Use after searching to read the complete document for detailed answers.")
    public String readKnowledgeBaseDocument(
            @ToolParam(description = "Exact file name of the document to read") String fileName) {
        notifier.notify("Reading KB doc: " + fileName);
        if (fileName == null || fileName.isBlank()) return "No file name provided.";

        Path file = KnowledgeBaseController.KB_DIR.resolve(fileName);
        if (!Files.exists(file) || Files.isDirectory(file)) {
            return "Document '" + fileName + "' not found in the knowledge base.";
        }
        // Block path traversal
        if (!file.normalize().startsWith(KnowledgeBaseController.KB_DIR)) {
            return "Invalid file name.";
        }

        try {
            String content = Files.readString(file);
            if (content.length() > 30000) {
                content = content.substring(0, 30000) + "\n\n... (truncated at 30,000 characters)";
            }
            return "Content of **" + fileName + "**:\n\n" + content;
        } catch (IOException e) {
            return "Error reading document: " + e.getMessage();
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
