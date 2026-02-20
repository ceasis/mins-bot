package com.minsbot.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Programmatic web browsing tools — the bot's own "headless browser".
 * Fetches pages, extracts text/images/links, searches for images, and downloads them.
 * No visible browser window is opened.
 */
@Component
public class WebScraperTools {

    private static final Path BASE_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final int MAX_PAGE_SIZE = 500_000; // 500 KB text limit
    private static final int MAX_IMAGES_PER_SEARCH = 20;

    private static final Pattern IMG_PATTERN =
            Pattern.compile("<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_PATTERN =
            Pattern.compile("<a[^>]+href\\s*=\\s*[\"']([^\"'#][^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_PATTERN =
            Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN =
            Pattern.compile("\\s{3,}");

    private final ToolExecutionNotifier notifier;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public WebScraperTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Fetch a web page and return its readable text content (HTML stripped). " +
            "Use this to read articles, search results, or any web page without opening a browser.")
    public String fetchPageText(
            @ToolParam(description = "The full URL to fetch, e.g. 'https://example.com'") String url) {
        notifier.notify("Fetching page: " + url);
        try {
            String html = fetchHtml(url);
            // Remove script/style blocks
            String cleaned = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
            cleaned = cleaned.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
            cleaned = cleaned.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
            // Convert <br>, <p>, <div>, <li> to newlines
            cleaned = cleaned.replaceAll("(?i)<br\\s*/?>", "\n");
            cleaned = cleaned.replaceAll("(?i)</(p|div|li|tr|h[1-6])>", "\n");
            // Strip remaining tags
            cleaned = TAG_PATTERN.matcher(cleaned).replaceAll(" ");
            // Decode common entities
            cleaned = cleaned.replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&#39;", "'").replace("&nbsp;", " ");
            // Collapse whitespace
            cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll("\n");
            cleaned = cleaned.trim();

            if (cleaned.length() > 8000) {
                cleaned = cleaned.substring(0, 8000) + "\n... (truncated, " + cleaned.length() + " chars total)";
            }
            return cleaned.isEmpty() ? "Page fetched but no readable text found." : cleaned;
        } catch (Exception e) {
            return "Failed to fetch page: " + e.getMessage();
        }
    }

    @Tool(description = "Extract all image URLs from a web page. Returns a list of absolute image URLs " +
            "that can then be downloaded with downloadFile.")
    public String extractImageUrls(
            @ToolParam(description = "The full URL of the page to scan for images") String url) {
        notifier.notify("Extracting images from: " + url);
        try {
            String html = fetchHtml(url);
            Set<String> images = new LinkedHashSet<>();
            Matcher m = IMG_PATTERN.matcher(html);
            while (m.find() && images.size() < 50) {
                String src = resolveUrl(url, m.group(1));
                if (src != null && isImageUrl(src)) {
                    images.add(src);
                }
            }
            // Also look for og:image, twitter:image meta tags
            Pattern metaImg = Pattern.compile(
                    "<meta[^>]+(?:property|name)\\s*=\\s*[\"'](?:og:image|twitter:image)[\"'][^>]+content\\s*=\\s*[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE);
            Matcher mm = metaImg.matcher(html);
            while (mm.find()) {
                String src = resolveUrl(url, mm.group(1));
                if (src != null) images.add(src);
            }

            if (images.isEmpty()) return "No images found on " + url;
            StringBuilder sb = new StringBuilder("Found " + images.size() + " images:\n");
            int i = 1;
            for (String img : images) {
                sb.append(i++).append(". ").append(img).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Failed to extract images: " + e.getMessage();
        }
    }

    @Tool(description = "Extract all links (URLs) from a web page. Useful for navigating search results " +
            "or finding specific pages to visit next.")
    public String extractLinks(
            @ToolParam(description = "The full URL of the page to scan for links") String url) {
        notifier.notify("Extracting links from: " + url);
        try {
            String html = fetchHtml(url);
            Set<String> links = new LinkedHashSet<>();
            Matcher m = LINK_PATTERN.matcher(html);
            while (m.find() && links.size() < 50) {
                String href = resolveUrl(url, m.group(1));
                if (href != null && href.startsWith("http")) {
                    links.add(href);
                }
            }
            if (links.isEmpty()) return "No links found on " + url;
            StringBuilder sb = new StringBuilder("Found " + links.size() + " links:\n");
            int i = 1;
            for (String link : links) {
                sb.append(i++).append(". ").append(link).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Failed to extract links: " + e.getMessage();
        }
    }

    @Tool(description = "Search the web for images matching a query, download them, and save to a directive's " +
            "data folder. This is the main tool for finding and collecting images from the internet. " +
            "Downloads up to maxImages images.")
    public String searchAndDownloadImages(
            @ToolParam(description = "Search query for images, e.g. 'condos for sale new york'") String query,
            @ToolParam(description = "Directive name to save images into (e.g. 'search-condo-new-york')") String directiveName,
            @ToolParam(description = "Maximum number of images to download (1-20)") double maxImagesRaw) {
        int maxImages = (int) Math.round(maxImagesRaw);
        notifier.notify("Searching images: " + query);
        try {
            if (maxImages < 1) maxImages = 1;
            if (maxImages > MAX_IMAGES_PER_SEARCH) maxImages = MAX_IMAGES_PER_SEARCH;

            // Determine save directory
            String safeName = sanitizeName(directiveName);
            Path saveDir = BASE_DIR.resolve("directive_" + safeName);
            Files.createDirectories(saveDir);

            // Collect image URLs from multiple sources
            List<String> imageUrls = new ArrayList<>();

            // Try Bing Images (more scraper-friendly than Google)
            String bingUrl = "https://www.bing.com/images/search?q=" +
                    URLEncoder.encode(query, StandardCharsets.UTF_8) + "&form=HDRSC2&first=1";
            try {
                String html = fetchHtml(bingUrl);
                // Bing stores full-size image URLs in murl attributes
                Pattern murlPattern = Pattern.compile("murl&quot;:&quot;(https?://[^&]+?)&quot;");
                Matcher m = murlPattern.matcher(html);
                while (m.find() && imageUrls.size() < maxImages * 2) {
                    String imgUrl = m.group(1).replace("&amp;", "&");
                    if (isImageUrl(imgUrl)) imageUrls.add(imgUrl);
                }
            } catch (Exception e) {
                // Bing failed, try fallback
            }

            // Fallback: try DuckDuckGo
            if (imageUrls.isEmpty()) {
                String ddgUrl = "https://duckduckgo.com/?q=" +
                        URLEncoder.encode(query, StandardCharsets.UTF_8) + "&iax=images&ia=images";
                try {
                    String html = fetchHtml(ddgUrl);
                    // Extract any image URLs from the page
                    Matcher m = IMG_PATTERN.matcher(html);
                    while (m.find() && imageUrls.size() < maxImages * 2) {
                        String src = m.group(1);
                        if (src.startsWith("http") && isImageUrl(src)) {
                            imageUrls.add(src);
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (imageUrls.isEmpty()) {
                return "No images found for query: " + query +
                        ". Try using fetchPageText on a specific website, " +
                        "then extractImageUrls to find images on that page.";
            }

            // Download images
            int downloaded = 0;
            int errors = 0;
            StringBuilder report = new StringBuilder();
            String timestamp = LocalDateTime.now().format(TS_FMT);

            for (int i = 0; i < imageUrls.size() && downloaded < maxImages; i++) {
                String imgUrl = imageUrls.get(i);
                try {
                    String ext = guessExtension(imgUrl);
                    String filename = timestamp + "_img_" + (downloaded + 1) + ext;
                    Path target = saveDir.resolve(filename);

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(imgUrl))
                            .timeout(Duration.ofSeconds(30))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .GET()
                            .build();

                    HttpResponse<InputStream> response = httpClient.send(request,
                            HttpResponse.BodyHandlers.ofInputStream());

                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try (InputStream in = response.body()) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                        long size = Files.size(target);
                        // Skip tiny files (likely error pages)
                        if (size < 1000) {
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
                return "Found " + imageUrls.size() + " image URLs but all downloads failed. " +
                        "Try using extractImageUrls on a specific page and downloadFile for each image.";
            }

            return "Downloaded " + downloaded + " images to " + saveDir.toAbsolutePath() + "/\n" +
                    report.toString().trim() +
                    (errors > 0 ? "\n(" + errors + " failed)" : "");
        } catch (Exception e) {
            return "Image search failed: " + e.getMessage();
        }
    }

    @Tool(description = "Fetch a web page and return both its text content and all image URLs found. " +
            "Combines fetchPageText and extractImageUrls in a single call for efficiency.")
    public String fetchPageWithImages(
            @ToolParam(description = "The full URL to fetch") String url) {
        notifier.notify("Fetching page + images: " + url);
        try {
            String html = fetchHtml(url);

            // Extract images
            Set<String> images = new LinkedHashSet<>();
            Matcher m = IMG_PATTERN.matcher(html);
            while (m.find() && images.size() < 30) {
                String src = resolveUrl(url, m.group(1));
                if (src != null && isImageUrl(src)) images.add(src);
            }

            // Extract text
            String cleaned = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
            cleaned = cleaned.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
            cleaned = cleaned.replaceAll("(?i)<br\\s*/?>", "\n");
            cleaned = cleaned.replaceAll("(?i)</(p|div|li|tr|h[1-6])>", "\n");
            cleaned = TAG_PATTERN.matcher(cleaned).replaceAll(" ");
            cleaned = cleaned.replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&nbsp;", " ");
            cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll("\n").trim();

            if (cleaned.length() > 5000) {
                cleaned = cleaned.substring(0, 5000) + "\n... (truncated)";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== PAGE TEXT ===\n").append(cleaned).append("\n\n");
            if (!images.isEmpty()) {
                sb.append("=== IMAGES FOUND (").append(images.size()).append(") ===\n");
                int i = 1;
                for (String img : images) {
                    sb.append(i++).append(". ").append(img).append("\n");
                }
            } else {
                sb.append("=== NO IMAGES FOUND ===\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Failed to fetch page: " + e.getMessage();
        }
    }

    // ─── Internal helpers ────────────────────────────────────────────────────────

    /** Fetch HTML from a URL with a browser-like User-Agent. */
    private String fetchHtml(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }
        String body = response.body();
        if (body.length() > MAX_PAGE_SIZE) {
            body = body.substring(0, MAX_PAGE_SIZE);
        }
        return body;
    }

    /** Resolve a potentially relative URL against a base URL. */
    private String resolveUrl(String baseUrl, String href) {
        if (href == null || href.isBlank()) return null;
        href = href.trim();
        if (href.startsWith("data:") || href.startsWith("javascript:")) return null;
        try {
            if (href.startsWith("http://") || href.startsWith("https://")) return href;
            URI base = URI.create(baseUrl);
            if (href.startsWith("//")) return base.getScheme() + ":" + href;
            return base.resolve(href).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Check if a URL looks like an image based on extension or common patterns. */
    private boolean isImageUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        // Common image extensions
        if (lower.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|svg|tiff|ico)(\\?.*)?$")) return true;
        // Common image hosting patterns
        if (lower.contains("/image") || lower.contains("/photo") || lower.contains("/img/")) return true;
        // Skip tiny tracking pixels / icons
        if (lower.contains("1x1") || lower.contains("pixel") || lower.contains("spacer")) return false;
        return false;
    }

    /** Guess a file extension from a URL. */
    private String guessExtension(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png")) return ".png";
        if (lower.contains(".gif")) return ".gif";
        if (lower.contains(".webp")) return ".webp";
        if (lower.contains(".bmp")) return ".bmp";
        if (lower.contains(".svg")) return ".svg";
        return ".jpg"; // default
    }

    /** Sanitize a name into a safe folder name. */
    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        String safe = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (safe.isEmpty()) return "unnamed";
        return safe.length() > 60 ? safe.substring(0, 60) : safe;
    }

    /** Format a file size into a human-readable string. */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
