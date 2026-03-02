package com.minsbot.agent.tools;

import com.minsbot.ChatService;
import com.minsbot.agent.AudioMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Audio listening mode — continuous background system audio capture and transcription.
 * The "ear" counterpart to ScreenWatchingTools' "eye".
 *
 * <p>Captures system audio (what's playing through speakers/headphones) at regular intervals,
 * transcribes it via OpenAI Whisper, and pushes transcriptions to a live feed in the UI.
 * This lets the bot know what the user is listening to (music, videos, podcasts, meetings, etc.).</p>
 */
@Component
public class AudioListeningTools {

    private static final Logger log = LoggerFactory.getLogger(AudioListeningTools.class);

    private static final int DEFAULT_INTERVAL_SECONDS = 15;
    private static final int MAX_ROUNDS = 720; // 3 hours at 15s interval

    private final AudioMemoryService audioMemoryService;
    private final ChatService chatService;
    private final ToolExecutionNotifier notifier;

    private volatile boolean listening = false;
    private volatile Thread listenThread;

    /** Pending transcriptions for the UI feed. */
    private final ConcurrentLinkedQueue<String> listenFeed = new ConcurrentLinkedQueue<>();

    /** Most recent transcription — available for main AI context. */
    private volatile String latestTranscription = null;

    public AudioListeningTools(AudioMemoryService audioMemoryService, ChatService chatService,
                               ToolExecutionNotifier notifier) {
        this.audioMemoryService = audioMemoryService;
        this.chatService = chatService;
        this.notifier = notifier;
    }

    public boolean isListening() { return listening; }

    public String getLatestTranscription() { return latestTranscription; }

    /** Drain all pending listen transcriptions (called by the frontend poll endpoint). */
    public List<String> drainTranscriptions() {
        List<String> result = new ArrayList<>();
        String msg;
        while ((msg = listenFeed.poll()) != null) {
            result.add(msg);
        }
        return result;
    }

    @Tool(description = "Start listening to system audio in the background. "
            + "Captures what is playing through the speakers/headphones every 15 seconds, "
            + "transcribes it, and shows live transcriptions in the UI. "
            + "Use when the user says 'listen to what I'm listening', 'what song is this', "
            + "'listen to my meeting', or 'turn on your ears'. "
            + "Call stopListening() to stop.")
    public String startListening() {
        notifier.notify("Starting audio listening...");

        if (listening) {
            return "Already listening. Call stopListening() first to restart.";
        }

        log.info("[ListenMode] Starting — interval: {}s, max rounds: {}", DEFAULT_INTERVAL_SECONDS, MAX_ROUNDS);

        listening = true;
        latestTranscription = null;

        listenThread = new Thread(() -> runListenLoop(DEFAULT_INTERVAL_SECONDS), "audio-listen");
        listenThread.setDaemon(true);
        listenThread.start();

        return "Listening mode started. Capturing system audio every " + DEFAULT_INTERVAL_SECONDS
                + " seconds. Transcriptions will appear in the UI. Say 'stop listening' to end.";
    }

    @Tool(description = "Stop listening to system audio. "
            + "Use when the user says 'stop listening', 'turn off your ears', or 'stop hearing'.")
    public String stopListening() {
        notifier.notify("Stopping audio listening...");
        if (!listening) {
            return "Listening mode is not active.";
        }
        log.info("[ListenMode] Stop requested");
        listening = false;
        if (listenThread != null) {
            listenThread.interrupt();
        }
        return "Listening mode stopped.";
    }

    // ═══ Listen loop ═══

    /**
     * Translate text to English using the ChatClient LLM.
     * If already English or translation fails, returns the original text.
     */
    private String translateToEnglish(String text) {
        if (text == null || text.isBlank()) return text;
        ChatClient client = chatService.getChatClient();
        if (client == null) return text;
        try {
            String result = client.prompt()
                    .user("Translate the following to English. If it's already in English, return it as-is. "
                            + "Only output the translation, nothing else. No quotes, no labels, no explanation:\n\n" + text)
                    .call()
                    .content();
            if (result != null && !result.isBlank()) {
                log.debug("[ListenMode] Translated: {} → {}",
                        text.length() > 60 ? text.substring(0, 60) + "..." : text,
                        result.length() > 60 ? result.substring(0, 60) + "..." : result);
                return result.trim();
            }
        } catch (Exception e) {
            log.debug("[ListenMode] Translation failed, using original: {}", e.getMessage());
        }
        return text;
    }

    private void runListenLoop(int intervalSeconds) {
        int round = 0;
        int consecutiveSilent = 0;

        try {
            while (listening && round < MAX_ROUNDS) {
                round++;
                log.info("[ListenMode] Round {}/{} — capturing audio...", round, MAX_ROUNDS);

                try {
                    String transcription = audioMemoryService.captureNow();

                    if (transcription != null && !transcription.isBlank()) {
                        consecutiveSilent = 0;
                        String display = transcription.length() > 300
                                ? transcription.substring(0, 300) + "..."
                                : transcription;

                        // Translate to English before pushing to feed
                        String translated = translateToEnglish(display);
                        listenFeed.add(translated);
                        latestTranscription = translated;
                        log.info("[ListenMode] Round {} — transcribed ({} chars): {}",
                                round, transcription.length(),
                                transcription.length() > 100 ? transcription.substring(0, 100) + "..." : transcription);
                    } else {
                        consecutiveSilent++;
                        log.debug("[ListenMode] Round {} — silence (no speech detected, {} consecutive)",
                                round, consecutiveSilent);

                        // After 20 consecutive silent captures (~5 min), show a status note
                        if (consecutiveSilent == 20) {
                            listenFeed.add("(no audio detected — is something playing?)");
                        }
                    }
                } catch (Exception e) {
                    log.warn("[ListenMode] Round {} — capture failed: {}", round, e.getMessage());
                }

                Thread.sleep(intervalSeconds * 1000L);
            }
        } catch (InterruptedException e) {
            log.info("[ListenMode] Interrupted");
        } catch (Exception e) {
            log.warn("[ListenMode] Loop ended with exception: {}", e.getMessage());
        } finally {
            listening = false;
            listenThread = null;
            log.info("[ListenMode] Ended after {} rounds", round);
            if (round >= MAX_ROUNDS) {
                listenFeed.add("Listening mode ended (reached maximum duration).");
            }
        }
    }
}
