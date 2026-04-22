package com.minsbot.agent.tools;

import com.minsbot.firstrun.ComfyUiInstallerService;
import com.minsbot.firstrun.FirstRunService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Self-description tool. Called when the user asks "what can you do?", "help",
 * "what are your capabilities?", "list your skills", etc. Returns a curated,
 * category-grouped summary that the LLM forwards verbatim.
 *
 * <p>Beats relying on the editable system prompt (users can delete sections and
 * the bot will hallucinate stale capabilities). This tool reflects what's actually
 * wired up and flags what requires configuration.</p>
 */
@Component
public class CapabilitiesTool {

    private final ToolExecutionNotifier notifier;
    private final FirstRunService firstRun;
    private final ComfyUiInstallerService comfyInstaller;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiKey;
    @Value("${spring.ai.anthropic.api-key:}")
    private String anthropicKey;

    public CapabilitiesTool(ToolExecutionNotifier notifier,
                            FirstRunService firstRun,
                            ComfyUiInstallerService comfyInstaller) {
        this.notifier = notifier;
        this.firstRun = firstRun;
        this.comfyInstaller = comfyInstaller;
    }

    @Tool(description =
            "Describe what Mins Bot can do right now on this machine. Call this when the user asks " +
            "'what can you do', 'help', 'what are your capabilities', 'list your skills', 'what tools do you have', " +
            "'what's possible', or similar. Returns a grouped capability list reflecting live configuration " +
            "(API keys set, Ollama running, ComfyUI available, etc.). Do NOT paraphrase — return the text as-is.")
    public String whatCanYouDo() {
        notifier.notify("Listing capabilities");
        boolean openAi = isKeySet(openAiKey);
        boolean anthropic = isKeySet(anthropicKey);
        boolean ollama = firstRun.isOllamaRunning();
        boolean comfy = comfyInstaller.isRunning();
        boolean gpu = Boolean.TRUE.equals(firstRun.detectGpu().get("available"));

        StringBuilder sb = new StringBuilder(1500);
        sb.append("Here's what I can do on this machine right now:\n\n");

        sb.append("**Chat & reasoning**\n");
        sb.append("• ").append(openAi ? "Cloud chat via OpenAI ✓" : "OpenAI not configured — add key in Setup tab").append("\n");
        sb.append("• ").append(anthropic ? "Cloud chat via Anthropic ✓" : "Anthropic not configured").append("\n");
        sb.append("• ").append(ollama ? "Local chat via Ollama ✓" : "Local chat (Ollama) not running — Models tab").append("\n");
        sb.append("• Offline mode — toggle the shield in the title bar to block all cloud APIs\n\n");

        sb.append("**Images**\n");
        sb.append("• ").append(comfy ? "Generate images locally via ComfyUI ✓ (\"create me a picture of X\")"
                : "Local image gen (ComfyUI) not running — Models tab").append("\n");
        sb.append("• Flip, rotate, grayscale, resize existing images\n");
        sb.append("• Generate QR codes from text/URLs\n\n");

        sb.append("**Screen & vision**\n");
        sb.append("• Take screenshots, read text on your screen, click visible UI elements\n");
        sb.append("• Watch mode: I describe what's happening as you work\n");
        sb.append("• Keyboard + mouse control (with explicit permission each time)\n\n");

        sb.append("**Files & clipboard**\n");
        sb.append("• List / read / write files, search by content or filename\n");
        sb.append("• Read and write your clipboard\n");
        sb.append("• Open files or folders in Explorer / their default app\n");
        sb.append("• Summarise PDFs, extract from DOCX/XLSX, crack encrypted PDFs\n\n");

        sb.append("**Communication & integrations**\n");
        sb.append("• Draft and send emails (SMTP + Gmail when connected)\n");
        sb.append("• Calendar: read & schedule events (Google)\n");
        sb.append("• Drive, Spotify, GitHub — connect in the Integrations tab\n");
        sb.append("• Viber / Telegram / Discord / Slack / WhatsApp / Messenger / LINE / Teams / WeChat bridge\n\n");

        sb.append("**Web**\n");
        sb.append("• Search the web, open URLs, scrape pages, drive Chrome via CDP\n");
        sb.append("• YouTube transcripts, travel search\n\n");

        sb.append("**Voice**\n");
        sb.append("• Voice input (push mic in the title bar)\n");
        sb.append("• Text-to-speech (Fish Audio / ElevenLabs / Windows SAPI)\n");
        sb.append("• Translate spoken audio while listening\n\n");

        sb.append("**System & automation**\n");
        sb.append("• List running apps, switch windows, close/open apps, control volume\n");
        sb.append("• Timers, reminders, recurring tasks, scheduled agents\n");
        sb.append("• Background agents on missions you define (Agents tab)\n");
        sb.append("• Proactive mode — I notice and act on what's on your screen\n\n");

        sb.append("**Health & hardware**\n");
        sb.append("• ").append(gpu ? "NVIDIA GPU detected ✓" : "No NVIDIA GPU — CPU-only").append("\n");
        sb.append("• Run a full health check any time: Diagnostics tab\n\n");

        sb.append("Try any of them by just asking. Examples: \"summarize the PDF on my desktop\", ")
          .append("\"generate an image of a sunrise\", \"what's on my calendar today\", \"translate that to Spanish\".");

        return sb.toString();
    }

    private static boolean isKeySet(String v) {
        return v != null && !v.isBlank() && !v.startsWith("sk-xxx") && v.length() > 10;
    }
}
