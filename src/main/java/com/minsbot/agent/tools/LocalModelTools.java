package com.minsbot.agent.tools;

import com.minsbot.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * AI-callable tools for managing local language models via Ollama.
 * Can install Ollama, download models, switch between OpenAI ↔ local models.
 */
@Component
public class LocalModelTools {

    private static final Logger log = LoggerFactory.getLogger(LocalModelTools.class);
    private static final String OLLAMA_API = "http://localhost:11434";
    private static final Path DOWNLOAD_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "downloads");

    private final ToolExecutionNotifier notifier;
    private final ChatService chatService;

    @Autowired(required = false)
    private ChatMemory chatMemory;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    // Track what we're running on
    private volatile String activeProvider = "openai"; // "openai" or "ollama"
    private volatile String activeOllamaModel = "";

    // Keep reference to the original OpenAI client so we can switch back
    private volatile ChatClient openAiClient;
    private volatile ChatClient ollamaClient;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public LocalModelTools(ToolExecutionNotifier notifier, @Lazy ChatService chatService) {
        this.notifier = notifier;
        this.chatService = chatService;
    }

    @Tool(description = "Check if Ollama is installed and running locally. " +
            "Returns Ollama version and status.")
    public String ollamaStatus() {
        notifier.notify("Checking Ollama status");
        StringBuilder sb = new StringBuilder();

        // Check if Ollama binary exists
        boolean installed = isOllamaInstalled();
        sb.append("Ollama installed: ").append(installed ? "YES" : "NO").append("\n");

        if (installed) {
            // Check if Ollama API is responding
            boolean running = isOllamaRunning();
            sb.append("Ollama running: ").append(running ? "YES" : "NO").append("\n");

            if (running) {
                try {
                    String version = ollamaApiGet("/api/version");
                    sb.append("Version: ").append(version).append("\n");
                } catch (Exception e) {
                    sb.append("Version: unknown\n");
                }
            } else {
                sb.append("Start Ollama with: ollama serve (or it may start automatically)\n");
            }
        }

        sb.append("\nActive provider: ").append(activeProvider);
        if ("ollama".equals(activeProvider)) {
            sb.append(" (model: ").append(activeOllamaModel).append(")");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Install Ollama on this computer. Downloads and runs the Ollama installer " +
            "silently. After installation, Ollama runs as a background service. " +
            "Use this when the user wants to use local AI models without an API key.")
    public String installOllama() {
        notifier.notify("Installing Ollama...");
        try {
            if (isOllamaInstalled()) {
                if (isOllamaRunning()) {
                    return "Ollama is already installed and running.";
                }
                // Try to start it
                startOllama();
                Thread.sleep(3000);
                return isOllamaRunning()
                        ? "Ollama was already installed. Started successfully."
                        : "Ollama is installed but failed to start. Try running 'ollama serve' manually.";
            }

            String os = System.getProperty("os.name", "").toLowerCase();

            if (os.contains("win")) {
                return installOllamaWindows();
            } else if (os.contains("mac")) {
                return installOllamaMac();
            } else {
                return installOllamaLinux();
            }
        } catch (Exception e) {
            log.error("[LocalModel] Ollama install failed: {}", e.getMessage(), e);
            return "Ollama installation failed: " + e.getMessage();
        }
    }

    @Tool(description = "Download/pull a local AI model via Ollama. Popular models: " +
            "llama3.2 (8B, general), llama3.2:1b (1B, fast), mistral (7B, good balance), " +
            "phi3 (3.8B, small/fast), gemma2 (9B, Google), deepseek-r1 (reasoning), " +
            "qwen2.5 (7B, multilingual), codellama (code). " +
            "First download may take several minutes depending on model size.")
    public String pullModel(
            @ToolParam(description = "Model name to download, e.g. 'llama3.2', 'mistral', 'phi3'") String modelName) {
        notifier.notify("Pulling model: " + modelName);
        try {
            if (!isOllamaRunning()) {
                return "Ollama is not running. Install it first with installOllama().";
            }

            log.info("[LocalModel] Pulling model: {}", modelName);

            // Use Ollama CLI to pull (shows progress)
            ProcessBuilder pb = new ProcessBuilder("ollama", "pull", modelName);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    // Update status with progress
                    if (line.contains("%")) {
                        notifier.notify("Pulling " + modelName + ": " + line.trim());
                    }
                }
            }

            boolean finished = proc.waitFor(30, TimeUnit.MINUTES);
            if (!finished) {
                proc.destroyForcibly();
                return "Model pull timed out after 30 minutes. The model may be very large. " +
                        "Try again or use a smaller model like 'phi3' or 'llama3.2:1b'.";
            }

            if (proc.exitValue() == 0) {
                log.info("[LocalModel] Model {} pulled successfully.", modelName);
                return "Model '" + modelName + "' downloaded successfully! " +
                        "Use switchToLocalModel('" + modelName + "') to start using it.";
            } else {
                return "Model pull failed:\n" + output.toString().trim();
            }
        } catch (Exception e) {
            log.error("[LocalModel] Pull failed: {}", e.getMessage());
            return "Failed to pull model: " + e.getMessage();
        }
    }

    @Tool(description = "List all locally installed Ollama models with their sizes.")
    public String listLocalModels() {
        notifier.notify("Listing local models");
        try {
            if (!isOllamaRunning()) {
                return "Ollama is not running. Install it first with installOllama().";
            }

            ProcessBuilder pb = new ProcessBuilder("ollama", "list");
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            proc.waitFor(10, TimeUnit.SECONDS);

            String result = output.toString().trim();
            if (result.isEmpty()) {
                return "No local models installed. Use pullModel() to download one.";
            }

            return "Local models:\n" + result + "\n\nActive provider: " + activeProvider
                    + ("ollama".equals(activeProvider) ? " (" + activeOllamaModel + ")" : "");
        } catch (Exception e) {
            return "Failed to list models: " + e.getMessage();
        }
    }

    @Tool(description = "Remove a locally installed Ollama model to free disk space.")
    public String removeModel(
            @ToolParam(description = "Model name to remove") String modelName) {
        notifier.notify("Removing model: " + modelName);
        try {
            if (!isOllamaRunning()) {
                return "Ollama is not running.";
            }

            ProcessBuilder pb = new ProcessBuilder("ollama", "rm", modelName);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            proc.waitFor(30, TimeUnit.SECONDS);

            if (proc.exitValue() == 0) {
                // If we were using this model, switch back
                if (modelName.equals(activeOllamaModel)) {
                    switchToOpenAI();
                }
                return "Model '" + modelName + "' removed.";
            }
            return "Failed to remove model: " + output.toString().trim();
        } catch (Exception e) {
            return "Failed to remove model: " + e.getMessage();
        }
    }

    @Tool(description = "Switch to using a local Ollama model instead of OpenAI. " +
            "The model must be already pulled/downloaded. This changes the active AI " +
            "to run entirely on your machine — no API key or internet needed for chat.")
    public String switchToLocalModel(
            @ToolParam(description = "Local model name to use, e.g. 'llama3.2', 'mistral'") String modelName) {
        notifier.notify("Switching to local: " + modelName);
        try {
            if (!isOllamaRunning()) {
                return "Ollama is not running. Install it first with installOllama().";
            }

            // Save the current OpenAI client so we can switch back
            if (openAiClient == null && chatService.getChatClient() != null) {
                openAiClient = chatService.getChatClient();
            }

            // Create Ollama ChatClient
            OllamaApi ollamaApi = new OllamaApi.Builder()
                    .baseUrl(OLLAMA_API)
                    .build();
            OllamaChatModel chatModel = OllamaChatModel.builder()
                    .ollamaApi(ollamaApi)
                    .defaultOptions(OllamaOptions.builder()
                            .model(modelName)
                            .temperature(0.7)
                            .build())
                    .build();

            ChatClient.Builder clientBuilder = ChatClient.builder(chatModel);
            if (chatMemory != null) {
                clientBuilder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory)
                        .conversationId("mins-bot-local")
                        .build());
            }
            ollamaClient = clientBuilder.build();

            // Swap it in
            chatService.setChatClient(ollamaClient);
            activeProvider = "ollama";
            activeOllamaModel = modelName;

            log.info("[LocalModel] Switched to Ollama model: {}", modelName);
            return "Switched to local model: " + modelName + " (via Ollama). " +
                    "All chat now runs locally on your machine. " +
                    "Use switchToOpenAI() to switch back.";
        } catch (Exception e) {
            log.error("[LocalModel] Switch failed: {}", e.getMessage(), e);
            return "Failed to switch to local model: " + e.getMessage();
        }
    }

    @Tool(description = "Switch back to using OpenAI (cloud) for AI chat. " +
            "Requires an API key to be configured.")
    public String switchToOpenAI() {
        notifier.notify("Switching to OpenAI");
        try {
            if (openAiClient != null) {
                chatService.setChatClient(openAiClient);
                activeProvider = "openai";
                activeOllamaModel = "";
                log.info("[LocalModel] Switched back to OpenAI.");
                return "Switched back to OpenAI.";
            }

            if (openAiApiKey == null || openAiApiKey.isBlank()) {
                return "No OpenAI API key configured. Cannot switch to OpenAI.";
            }

            return "OpenAI client not available. Restart the app with an API key configured.";
        } catch (Exception e) {
            return "Failed to switch to OpenAI: " + e.getMessage();
        }
    }

    @Tool(description = "Get info about the currently active AI provider and model.")
    public String getActiveProvider() {
        notifier.notify("Checking active provider");
        StringBuilder sb = new StringBuilder();
        sb.append("Active provider: ").append(activeProvider).append("\n");
        if ("ollama".equals(activeProvider)) {
            sb.append("Local model: ").append(activeOllamaModel).append("\n");
            sb.append("Ollama API: ").append(OLLAMA_API).append("\n");
        } else {
            sb.append("Using cloud API (OpenAI)\n");
        }
        sb.append("ChatClient available: ").append(chatService.getChatClient() != null);
        return sb.toString().trim();
    }

    // ═══ Ollama installation helpers ═══

    private boolean isOllamaInstalled() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ollama", "--version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
            return finished && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isOllamaRunning() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_API + "/api/version"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void startOllama() {
        try {
            new ProcessBuilder("ollama", "serve").start();
        } catch (Exception e) {
            log.warn("[LocalModel] Failed to start Ollama: {}", e.getMessage());
        }
    }

    private String ollamaApiGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_API + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private String installOllamaWindows() throws Exception {
        notifier.notify("Downloading Ollama installer...");
        Files.createDirectories(DOWNLOAD_DIR);
        Path installer = DOWNLOAD_DIR.resolve("OllamaSetup.exe");

        // Download installer
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://ollama.com/download/OllamaSetup.exe"))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(installer));

        if (resp.statusCode() != 200) {
            return "Failed to download Ollama installer (HTTP " + resp.statusCode() + ").";
        }

        long size = Files.size(installer);
        log.info("[LocalModel] Downloaded OllamaSetup.exe ({} MB)", size / (1024 * 1024));
        notifier.notify("Running Ollama installer...");

        // Run installer silently
        ProcessBuilder pb = new ProcessBuilder(installer.toString(), "/VERYSILENT", "/NORESTART");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        boolean finished = proc.waitFor(5, TimeUnit.MINUTES);

        if (!finished) {
            proc.destroyForcibly();
            return "Ollama installer timed out. Try running it manually: " + installer.toAbsolutePath();
        }

        // Wait for Ollama service to start
        Thread.sleep(5000);

        if (isOllamaRunning()) {
            log.info("[LocalModel] Ollama installed and running on Windows.");
            return "Ollama installed successfully! It's running at " + OLLAMA_API + ".\n" +
                    "Now use pullModel('llama3.2') to download a model.";
        } else {
            startOllama();
            Thread.sleep(3000);
            return isOllamaRunning()
                    ? "Ollama installed and started! Use pullModel('llama3.2') to download a model."
                    : "Ollama installed but not yet running. It may need a restart. " +
                    "Try running 'ollama serve' in a terminal.";
        }
    }

    private String installOllamaMac() throws Exception {
        notifier.notify("Installing Ollama via curl...");
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", "curl -fsSL https://ollama.com/install.sh | sh");
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        proc.waitFor(10, TimeUnit.MINUTES);

        Thread.sleep(3000);
        return isOllamaRunning()
                ? "Ollama installed and running! Use pullModel('llama3.2') to download a model."
                : "Ollama install script finished. Output:\n" + output.toString().trim();
    }

    private String installOllamaLinux() throws Exception {
        return installOllamaMac(); // Same curl install script works for Linux
    }
}
