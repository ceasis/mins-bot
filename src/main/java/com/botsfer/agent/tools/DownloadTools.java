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

@Component
public class DownloadTools {

    private final ToolExecutionNotifier notifier;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public DownloadTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Download a file from a URL and save it to a local path. Use when the user wants to save a file from a link.")
    public String downloadFile(
            @ToolParam(description = "Full URL of the file to download") String url,
            @ToolParam(description = "Full local path to save the file, e.g. C:\\Users\\Me\\Downloads\\file.pdf") String savePath) {
        if (url == null || url.isBlank()) return "URL is required.";
        if (savePath == null || savePath.isBlank()) return "Save path is required.";
        notifier.notify("Downloading: " + url);
        try {
            URI uri = URI.create(url);
            HttpRequest request = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(60)).GET().build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "Download failed: HTTP " + response.statusCode();
            }
            Path out = Paths.get(savePath);
            Files.createDirectories(out.getParent() != null ? out.getParent() : out.getRoot());
            try (InputStream in = response.body()) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(out);
            return "Downloaded to " + out.toAbsolutePath() + " (" + size + " bytes).";
        } catch (Exception e) {
            return "Download failed: " + e.getMessage();
        }
    }
}
