package com.minsbot.agent.tools;

import com.minsbot.ElevenLabsVoiceService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Text-to-speech (read aloud). Uses ElevenLabs when configured, otherwise Windows SAPI on Windows.
 */
@Component
public class TtsTools {

    private final ToolExecutionNotifier notifier;
    private final ElevenLabsVoiceService elevenLabs;

    public TtsTools(ToolExecutionNotifier notifier, ElevenLabsVoiceService elevenLabs) {
        this.notifier = notifier;
        this.elevenLabs = elevenLabs;
    }

    @Tool(description = "Read text aloud using text-to-speech (ElevenLabs when configured, otherwise Windows SAPI). Use when the user asks to 'read aloud', 'speak', or 'say' something.")
    public String speak(
            @ToolParam(description = "Text to speak aloud") String text) {
        if (text == null || text.isBlank()) return "No text to speak.";
        notifier.notify("Speaking: " + (text.length() > 30 ? text.substring(0, 30) + "..." : text));

        if (elevenLabs.isEnabled()) {
            String result = speakElevenLabs(text);
            if (result != null) return result;
            // Fall through to SAPI on error if Windows
        }

        return speakWindowsSapi(text);
    }

    private String speakElevenLabs(String text) {
        try {
            byte[] audio = elevenLabs.textToSpeech(text);
            if (audio == null || audio.length == 0) return null;
            try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audio))) {
                Clip clip = AudioSystem.getClip();
                clip.open(ais);
                clip.start();
                while (clip.isRunning()) {
                    Thread.sleep(50);
                }
                clip.close();
            }
            return "Spoke: \"" + (text.length() > 80 ? text.substring(0, 80) + "..." : text) + "\"";
        } catch (Exception e) {
            return null;
        }
    }

    private String speakWindowsSapi(String text) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return "Text-to-speech is only supported on Windows (SAPI), or set app.elevenlabs.enabled and API key for ElevenLabs.";
        }
        try {
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
