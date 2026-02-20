package com.botsfer.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Controls browser windows — open tabs, close browsers, navigate.
 */
@Service
public class BrowserControlService {

    private static final Logger log = LoggerFactory.getLogger(BrowserControlService.class);

    /**
     * Open a URL in Chrome (or default browser if Chrome isn't available).
     */
    public String openInChrome(String url) {
        return openInBrowser("chrome", url);
    }

    /**
     * Open a URL in a specific browser.
     */
    public String openInBrowser(String browser, String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        try {
            String exe = switch (browser.toLowerCase()) {
                case "chrome", "google chrome" -> "chrome";
                case "firefox" -> "firefox";
                case "edge", "microsoft edge" -> "msedge";
                case "brave" -> "brave";
                default -> null;
            };
            if (exe != null) {
                new ProcessBuilder("cmd", "/c", "start", exe, url).start();
            } else {
                new ProcessBuilder("cmd", "/c", "start", "", url).start();
            }
            return "Opened " + url + " in " + (exe != null ? browser : "default browser") + ".";
        } catch (Exception e) {
            return "Failed to open browser: " + e.getMessage();
        }
    }

    /**
     * Close all browser windows (Chrome, Firefox, Edge).
     */
    public String closeAllBrowsers() {
        int closed = 0;
        for (String proc : new String[]{"chrome.exe", "firefox.exe", "msedge.exe", "brave.exe", "opera.exe"}) {
            try {
                Process p = new ProcessBuilder("taskkill", "/IM", proc, "/F")
                        .redirectErrorStream(true).start();
                p.waitFor();
                if (p.exitValue() == 0) closed++;
            } catch (Exception e) {
                // skip
            }
        }
        return closed > 0 ? "Closed " + closed + " browser(s)." : "No browsers were running.";
    }

    /**
     * Search Google for a query (opens in default browser).
     */
    public String searchGoogle(String query) {
        String encoded = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
        return openInBrowser("default", "https://www.google.com/search?q=" + encoded);
    }

    /**
     * Search YouTube for a query.
     */
    public String searchYouTube(String query) {
        String encoded = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
        return openInBrowser("default", "https://www.youtube.com/results?search_query=" + encoded);
    }

    /**
     * Get list of open Chrome tabs (via PowerShell window title parsing).
     */
    public String listBrowserTabs() {
        try {
            // Get window titles of browser processes
            String ps = "Get-Process chrome,firefox,msedge -ErrorAction SilentlyContinue | " +
                    "Where-Object { $_.MainWindowTitle -ne '' } | " +
                    "Select-Object ProcessName, MainWindowTitle | Format-Table -AutoSize";
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command", ps)
                    .redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            p.waitFor();
            if (output.isBlank()) {
                return "No browser windows found.";
            }
            return "Browser windows:\n" + output;
        } catch (Exception e) {
            return "Could not list browser tabs: " + e.getMessage();
        }
    }
}
