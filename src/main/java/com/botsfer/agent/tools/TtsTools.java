package com.botsfer.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Text-to-speech (read aloud). Uses Windows SAPI via PowerShell when on Windows.
 */
@Component
public class TtsTools {

    private final ToolExecutionNotifier notifier;

    public TtsTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Read text aloud using system text-to-speech (Windows). Use when the user asks to 'read aloud', 'speak', or 'say' something.")
    public String speak(
            @ToolParam(description = "Text to speak aloud") String text) {
        if (text == null || text.isBlank()) return "No text to speak.";
        notifier.notify("Speaking: " + (text.length() > 30 ? text.substring(0, 30) + "..." : text));
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return "Text-to-speech is only supported on Windows (SAPI).";
        }
        try {
            // Escape for PowerShell: single quotes in text break the string
            String escaped = text.replace("'", "''").replace("\r", " ").replace("\n", " ");
            if (escaped.length() > 4000) escaped = escaped.substring(0, 4000) + "...";
            String ps = "Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.Speak('" + escaped + "')";
            Path script = Files.createTempFile("mins_bot_tts_", ".ps1");
            Files.writeString(script, ps, StandardCharsets.UTF_8);
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                    .inheritIO()
                    .start();
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            Files.deleteIfExists(script);
            if (!done) {
                p.destroyForcibly();
                return "Speech timed out.";
            }
            return "Spoke: \"" + (text.length() > 80 ? text.substring(0, 80) + "..." : text) + "\"";
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "TTS failed: " + e.getMessage();
        }
    }
}
