package com.minsbot.agent.tools;

import com.minsbot.agent.BrowserControlService;
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

@Component
public class BrowserTools {

    private static final Path DOWNLOADS_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "downloads");

    private final BrowserControlService browserControl;
    private final ToolExecutionNotifier notifier;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public BrowserTools(BrowserControlService browserControl, ToolExecutionNotifier notifier) {
        this.browserControl = browserControl;
        this.notifier = notifier;
    }

    @Tool(description = "CANONICAL way to open a URL for the USER to view. Launches the URL in their "
            + "default browser as a visible window/tab. Use whenever the user says 'open <site>', "
            + "'go to <url>', 'show me <site>', 'take me to X'. "
            + "DO NOT use to extract page content for the bot — use webScraperTools.fetchPageText (cheap, "
            + "no browser) or playwrightTools.browseAndGetLinks (JS-heavy pages). "
            + "DO NOT use when the user says 'in-browser' or 'chat browser' — use playwrightTools.openInBrowserTab. "
            + "DO NOT use to drive the user's already-open Chrome — use chromeCdpTools.browserOpenNewTab.")
    public String openUrl(
            @ToolParam(description = "The URL to open, e.g. 'google.com' or 'https://example.com'") String url) {
        notifier.notify("Opening " + url + "...");
        return browserControl.openInBrowser("default", url);
    }

    @Tool(description = "Open Google search results IN THE USER'S BROWSER (visible window). "
            + "Use ONLY when the user wants to BROWSE results themselves — 'google X for me', "
            + "'open google for X', 'show me search results for Y'. "
            + "DO NOT use when the user wants the bot to ANSWER from search — use webSearchTools.searchWeb "
            + "(returns text results in chat, no browser opens, faster).")
    public String searchGoogle(
            @ToolParam(description = "The search query") String query) {
        notifier.notify("Searching Google: " + query);
        return browserControl.searchGoogle(query);
    }

    @Tool(description = "Open YouTube search results IN THE USER'S BROWSER (visible window). "
            + "Use when the user wants to BROWSE/PICK a video themselves — 'open youtube for X', "
            + "'find me videos about Y on youtube'. "
            + "For PLAYING a video inside Sentry Mode use that mode's 'play video' command instead.")
    public String searchYouTube(
            @ToolParam(description = "The YouTube search query") String query) {
        notifier.notify("Searching YouTube: " + query);
        return browserControl.searchYouTube(query);
    }

    @Tool(description = "Close all open web browser windows (Chrome, Firefox, Edge, Brave, Opera)")
    public String closeAllBrowsers() {
        notifier.notify("Closing all browsers...");
        return browserControl.closeAllBrowsers();
    }

    // @Tool removed — chromeCdpTools.browserListOpenTabs is more reliable (CDP vs PowerShell window-title scrape).
    public String listBrowserTabs() {
        notifier.notify("Listing browser tabs...");
        return browserControl.listBrowserTabs();
    }

    @Tool(description = "Download a file from a URL and save it to ~/mins_bot_data/downloads/ " +
            "(or to a specific directive folder if directiveName is provided). " +
            "Supports any file type: images, PDFs, documents, etc.")
    public String downloadFileToFolder(
            @ToolParam(description = "The full URL of the file to download") String url,
            @ToolParam(description = "Optional filename to save as. If empty, derived from URL.") String filename,
            @ToolParam(description = "Optional directive name to save into its folder (e.g. 'search-condo'). " +
                    "If empty, saves to ~/mins_bot_data/downloads/.") String directiveName) {
        notifier.notify("Downloading " + url + "...");
        try {
            // Determine save directory
            Path saveDir;
            if (directiveName != null && !directiveName.isBlank()) {
                String safeName = directiveName.trim().toLowerCase()
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
                if (safeName.isEmpty()) safeName = "unnamed";
                saveDir = Paths.get(System.getProperty("user.home"), "mins_bot_data", "mins_workfolder", "directive_" + safeName);
            } else {
                saveDir = DOWNLOADS_DIR;
            }
            Files.createDirectories(saveDir);

            // Determine filename
            String saveName = (filename != null && !filename.isBlank()) ? filename.trim() : deriveFilename(url);
            // Sanitize
            saveName = saveName.replaceAll("[/\\\\:*?\"<>|]", "_");
            if (saveName.length() > 200) saveName = saveName.substring(0, 200);

            Path target = saveDir.resolve(saveName);

            // Download
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "Download failed: HTTP " + response.statusCode();
            }

            try (InputStream in = response.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            long size = Files.size(target);
            String sizeStr = size < 1024 ? size + " B"
                    : size < 1024 * 1024 ? (size / 1024) + " KB"
                    : String.format("%.1f MB", size / (1024.0 * 1024.0));

            return "Downloaded: " + target.toAbsolutePath() + " (" + sizeStr + ")";
        } catch (Exception e) {
            return "Download failed: " + e.getMessage();
        }
    }

    @Tool(description = "Open a URL in Chrome's incognito (private) mode. "
            + "No browsing history, cookies, or cache will be saved. "
            + "Example: openIncognito('https://google.com')")
    public String openIncognito(
            @ToolParam(description = "URL to open in incognito mode") String url) {
        notifier.notify("Opening incognito: " + url);
        try {
            String chromePath = detectChromePath();
            if (chromePath != null) {
                new ProcessBuilder(chromePath, "--incognito", url).start();
                return "Opened in Chrome incognito: " + url;
            }
            // Fallback: try Edge
            new ProcessBuilder("cmd", "/c", "start msedge --inprivate " + url).start();
            return "Opened in Edge InPrivate: " + url;
        } catch (Exception e) {
            return "FAILED: Could not open incognito window: " + e.getMessage();
        }
    }

    private String detectChromePath() {
        String[] candidates = {
                System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
        };
        for (String path : candidates) {
            if (path != null && java.nio.file.Files.exists(java.nio.file.Paths.get(path))) {
                return path;
            }
        }
        return null;
    }

    /** Extract a filename from a URL, falling back to "download" if nothing useful. */
    private String deriveFilename(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null && !path.isEmpty()) {
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (!last.isEmpty() && last.contains(".")) return last;
            }
        } catch (Exception ignored) {}
        return "download_" + System.currentTimeMillis();
    }
}
