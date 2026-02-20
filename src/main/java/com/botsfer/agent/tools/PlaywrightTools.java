package com.botsfer.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * AI-callable tools backed by a real headless Chromium browser (via Playwright).
 * These can render JavaScript, interact with dynamic pages, search for images,
 * and download them — far more capable than simple HTTP fetch.
 */
@Component
public class PlaywrightTools {

    private static final Path BASE_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final PlaywrightService pw;
    private final ToolExecutionNotifier notifier;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public PlaywrightTools(PlaywrightService pw, ToolExecutionNotifier notifier) {
        this.pw = pw;
        this.notifier = notifier;
    }

    @Tool(description = "Browse to a URL using a real headless browser (renders JavaScript) and return " +
            "the visible text. Use this for JavaScript-heavy sites where fetchPageText doesn't work.")
    public String browsePage(
            @ToolParam(description = "The URL to browse, e.g. 'https://example.com'") String url) {
        notifier.notify("Browsing: " + url);
        try {
            return pw.getPageText(url);
        } catch (Exception e) {
            return "Browse failed: " + e.getMessage();
        }
    }

    @Tool(description = "Browse to a URL and extract all image URLs after JavaScript renders. " +
            "More reliable than extractImageUrls for dynamic/JS-heavy pages.")
    public String browseAndGetImages(
            @ToolParam(description = "The URL to scan for images") String url) {
        notifier.notify("Scanning images: " + url);
        try {
            List<String> images = pw.getImageUrls(url);
            if (images.isEmpty()) return "No images found on " + url;
            StringBuilder sb = new StringBuilder("Found " + images.size() + " images:\n");
            for (int i = 0; i < images.size(); i++) {
                sb.append(i + 1).append(". ").append(images.get(i)).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Failed to extract images: " + e.getMessage();
        }
    }

    @Tool(description = "Browse to a URL and extract all links after JavaScript renders. " +
            "More reliable than extractLinks for dynamic/JS-heavy pages.")
    public String browseAndGetLinks(
            @ToolParam(description = "The URL to scan for links") String url) {
        notifier.notify("Scanning links: " + url);
        try {
            List<String> links = pw.getLinkUrls(url);
            if (links.isEmpty()) return "No links found on " + url;
            StringBuilder sb = new StringBuilder("Found " + links.size() + " links:\n");
            for (int i = 0; i < links.size(); i++) {
                sb.append(i + 1).append(". ").append(links.get(i)).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Failed to extract links: " + e.getMessage();
        }
    }

    @Tool(description = "Take a full-page screenshot of a URL using the headless browser and save it " +
            "to a directive's data folder. Captures the entire rendered page as an image.")
    public String screenshotPage(
            @ToolParam(description = "The URL to screenshot") String url,
            @ToolParam(description = "Directive name to save screenshot into") String directiveName) {
        notifier.notify("Screenshotting: " + url);
        try {
            Path saved = pw.screenshotPage(url, directiveName);
            long size = Files.size(saved);
            return "Page screenshot saved: " + saved.toAbsolutePath() + " (" + formatSize(size) + ")";
        } catch (Exception e) {
            return "Screenshot failed: " + e.getMessage();
        }
    }

    @Tool(description = "Search for images on the web using a real browser, download them, and save " +
            "to a directive's folder. Uses Google/Bing image search with a real rendered browser " +
            "so it can find images that simple HTTP scraping misses.")
    public String browseSearchAndDownloadImages(
            @ToolParam(description = "Search query for images, e.g. 'condos for sale new york'") String query,
            @ToolParam(description = "Directive name to save images into") String directiveName,
            @ToolParam(description = "Maximum number of images to download (1-20)") int maxImages) {
        notifier.notify("Searching images: " + query);
        try {
            if (maxImages < 1) maxImages = 1;
            if (maxImages > 20) maxImages = 20;

            // Try Bing first (more scraper-friendly), fall back to Google
            List<String> imageUrls;
            try {
                imageUrls = pw.searchBingImages(query, maxImages * 2);
            } catch (Exception e) {
                imageUrls = List.of();
            }

            if (imageUrls.isEmpty()) {
                try {
                    imageUrls = pw.searchGoogleImages(query, maxImages * 2);
                } catch (Exception e) {
                    return "Image search failed: " + e.getMessage();
                }
            }

            if (imageUrls.isEmpty()) {
                return "No images found for: " + query;
            }

            // Download images
            String safeName = sanitizeName(directiveName);
            Path saveDir = BASE_DIR.resolve("directive_" + safeName);
            Files.createDirectories(saveDir);

            String timestamp = LocalDateTime.now().format(TS_FMT);
            int downloaded = 0;
            int errors = 0;
            StringBuilder report = new StringBuilder();

            for (int i = 0; i < imageUrls.size() && downloaded < maxImages; i++) {
                try {
                    String imgUrl = imageUrls.get(i);
                    String ext = guessExtension(imgUrl);
                    String filename = timestamp + "_img_" + (downloaded + 1) + ext;
                    Path target = saveDir.resolve(filename);

                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(imgUrl))
                            .timeout(Duration.ofSeconds(30))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .GET()
                            .build();

                    HttpResponse<InputStream> resp = httpClient.send(req,
                            HttpResponse.BodyHandlers.ofInputStream());

                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        try (InputStream in = resp.body()) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                        long size = Files.size(target);
                        if (size < 1000) { // skip tiny error pages
                            Files.delete(target);
                            errors++;
                            continue;
                        }
                        downloaded++;
                        report.append("  ").append(downloaded).append(". ").append(target.getFileName())
                                .append(" (").append(formatSize(size)).append(")\n");
                    } else {
                        errors++;
                    }
                } catch (Exception e) {
                    errors++;
                }
            }

            if (downloaded == 0) {
                return "Found " + imageUrls.size() + " image URLs but all downloads failed.";
            }
            return "Downloaded " + downloaded + " images to " + saveDir.toAbsolutePath() + "/\n" +
                    report.toString().trim() +
                    (errors > 0 ? "\n(" + errors + " failed)" : "");
        } catch (Exception e) {
            return "Image search failed: " + e.getMessage();
        }
    }

    @Tool(description = "Click an element on a web page (by CSS selector) and return the resulting " +
            "page text. Useful for navigating multi-page results or expanding content.")
    public String browseAndClick(
            @ToolParam(description = "The URL to navigate to first") String url,
            @ToolParam(description = "CSS selector of the element to click, e.g. 'a.next-page', 'button#load-more'") String selector) {
        notifier.notify("Clicking on: " + url);
        try {
            return pw.clickElement(url, selector);
        } catch (Exception e) {
            return "Click failed: " + e.getMessage();
        }
    }

    @Tool(description = "Fill a form field on a web page and optionally press Enter to submit. " +
            "Useful for search forms, login fields, etc.")
    public String browseAndFill(
            @ToolParam(description = "The URL to navigate to") String url,
            @ToolParam(description = "CSS selector of the input field, e.g. 'input[name=q]', '#search-box'") String selector,
            @ToolParam(description = "The text to type into the field") String value,
            @ToolParam(description = "Whether to press Enter after filling (submit the form)") boolean submit) {
        notifier.notify("Filling form on: " + url);
        try {
            return pw.fillAndSubmit(url, selector, value, submit);
        } catch (Exception e) {
            return "Fill failed: " + e.getMessage();
        }
    }

    private String guessExtension(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png")) return ".png";
        if (lower.contains(".gif")) return ".gif";
        if (lower.contains(".webp")) return ".webp";
        if (lower.contains(".bmp")) return ".bmp";
        return ".jpg";
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        String safe = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return safe.isEmpty() ? "unnamed" : (safe.length() > 60 ? safe.substring(0, 60) : safe);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
