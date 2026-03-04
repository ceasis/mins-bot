package com.minsbot;

import com.google.genai.Client;
import com.google.genai.AsyncSession;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.Modality;
import com.google.genai.types.Part;

import javax.sound.sampled.*;
import java.io.FileInputStream;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gemini 2.5 Live API — Real-time Audio Translator
 *
 * Captures mic audio (Filipino/Tagalog by default), streams to Gemini Live,
 * and plays back the English translation through your speakers.
 *
 * API key loaded from application-secrets.properties (gemini.api.key).
 *
 * Usage:
 *   1. Run from IDE (main class: com.minsbot.GeminiLiveAudioTest)
 *   2. Speak into your microphone in Filipino/Tagalog
 *   3. Hear the English translation played back through speakers
 *   4. Console shows transcriptions of what was heard and translated
 *   5. Press Enter to stop.
 */
public class GeminiLiveAudioTest {

    // Audio format: 16kHz, mono, 16-bit PCM (what Gemini Live expects)
    private static final float SAMPLE_RATE = 16000f;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;
    private static final int CHUNK_MS = 500;
    private static final int CHUNK_BYTES = (int) (SAMPLE_RATE * (SAMPLE_SIZE_BITS / 8) * CHANNELS * CHUNK_MS / 1000);

    // Gemini output is 24kHz PCM
    private static final float OUTPUT_SAMPLE_RATE = 24000f;

    private static final String MODEL = "gemini-2.5-flash-native-audio-latest";
    private static final String SOURCE_LANGUAGE = "Filipino/Tagalog";

    // Queue for audio playback (producer: message handler, consumer: playback thread)
    private static final ConcurrentLinkedQueue<byte[]> playbackQueue = new ConcurrentLinkedQueue<>();

    public static void main(String[] args) throws Exception {
        // ── 1. Get API key ──
        String apiKey = loadApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: No API key found.");
            System.err.println("Set gemini.api.key in application-secrets.properties");
            System.exit(1);
        }

        System.out.println("=== Gemini Live — Real-time Translator ===");
        System.out.println("Model:    " + MODEL);
        System.out.println("Language: " + SOURCE_LANGUAGE + " -> English");
        System.out.println();

        // ── 2. Connect to Gemini Live ──
        System.out.println("[*] Connecting to Gemini Live API...");

        HttpOptions httpOptions = HttpOptions.builder()
                .apiVersion("v1beta")
                .build();
        Client client = Client.builder().apiKey(apiKey).httpOptions(httpOptions).build();

        String systemInstruction = "You are a real-time translator for " + SOURCE_LANGUAGE + " to English. "
                + "The audio you will hear is in " + SOURCE_LANGUAGE + ". "
                + "Listen carefully and provide accurate, natural English translations. "
                + "Capture the full meaning, context, and intent of what is being said. "
                + "Speak the English translation clearly and naturally. "
                + "If the audio is already in English, repeat it back clearly. "
                + "Do not add any commentary — only speak the translation.";

        Content sysContent = Content.builder()
                .parts(List.of(Part.builder().text(systemInstruction).build()))
                .build();

        LiveConnectConfig config = LiveConnectConfig.builder()
                .responseModalities(List.of(new Modality(Modality.Known.AUDIO)))
                .systemInstruction(sysContent)
                .build();

        CountDownLatch connectLatch = new CountDownLatch(1);
        final AsyncSession[] sessionHolder = {null};
        final Exception[] connectError = {null};

        client.async.live.connect(MODEL, config)
                .thenAccept(session -> {
                    sessionHolder[0] = session;
                    System.out.println("[+] Connected! Session ID: " + session.sessionId());

                    session.receive(msg -> handleMessage(msg))
                            .exceptionally(ex -> {
                                System.err.println("[!] Receive error: " + ex.getMessage());
                                return null;
                            });

                    connectLatch.countDown();
                })
                .exceptionally(ex -> {
                    connectError[0] = new RuntimeException(ex.getMessage(), ex);
                    connectLatch.countDown();
                    return null;
                });

        if (!connectLatch.await(15, TimeUnit.SECONDS)) {
            System.err.println("[!] Connection timed out after 15 seconds.");
            System.exit(1);
        }
        if (connectError[0] != null) {
            System.err.println("[!] Connection failed: " + connectError[0].getMessage());
            System.exit(1);
        }

        AsyncSession session = sessionHolder[0];

        // ── 3. Open microphone (input: 16kHz) ──
        AudioFormat micFormat = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);
        DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, micFormat);

        if (!AudioSystem.isLineSupported(micInfo)) {
            System.err.println("[!] Microphone not available or format not supported.");
            session.close().get(5, TimeUnit.SECONDS);
            System.exit(1);
        }

        TargetDataLine mic = (TargetDataLine) AudioSystem.getLine(micInfo);
        mic.open(micFormat);
        mic.start();

        // ── 4. Open speaker (output: 24kHz — Gemini's output rate) ──
        AudioFormat speakerFormat = new AudioFormat(OUTPUT_SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);
        DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, speakerFormat);

        SourceDataLine speaker = null;
        if (AudioSystem.isLineSupported(speakerInfo)) {
            speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
            speaker.open(speakerFormat);
            speaker.start();
            System.out.println("[*] Speaker open (24kHz playback)");
        } else {
            System.out.println("[!] Speaker not available — audio will not be played back");
        }

        AtomicBoolean running = new AtomicBoolean(true);

        System.out.println("[*] Microphone open (16kHz capture)");
        System.out.println("[*] Speak in " + SOURCE_LANGUAGE + " — translation will play through speakers");
        System.out.println("[*] Press Enter to stop...");
        System.out.println("─".repeat(60));

        // ── 5. Mic capture thread ──
        Thread micThread = new Thread(() -> {
            byte[] buffer = new byte[CHUNK_BYTES];
            while (running.get()) {
                int bytesRead = mic.read(buffer, 0, buffer.length);
                if (bytesRead > 0 && running.get()) {
                    byte[] chunk = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunk, 0, bytesRead);

                    Blob audioBlob = Blob.builder()
                            .data(chunk)
                            .mimeType("audio/pcm;rate=16000")
                            .build();

                    session.sendRealtimeInput(LiveSendRealtimeInputParameters.builder()
                                    .audio(audioBlob)
                                    .build())
                            .exceptionally(ex -> {
                                System.err.println("[!] Send error: " + ex.getMessage());
                                return null;
                            });
                }
            }
        }, "mic-capture");
        micThread.setDaemon(true);
        micThread.start();

        // ── 6. Speaker playback thread ──
        final SourceDataLine spk = speaker;
        Thread playbackThread = new Thread(() -> {
            while (running.get() || !playbackQueue.isEmpty()) {
                byte[] audioData = playbackQueue.poll();
                if (audioData != null && spk != null) {
                    spk.write(audioData, 0, audioData.length);
                } else {
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                }
            }
        }, "audio-playback");
        playbackThread.setDaemon(true);
        playbackThread.start();

        // ── 7. Wait for Enter to stop ──
        new Scanner(System.in).nextLine();

        // ── 8. Cleanup ──
        System.out.println("─".repeat(60));
        System.out.println("[*] Stopping...");
        running.set(false);

        mic.stop();
        mic.close();

        // Drain remaining audio
        if (spk != null) {
            spk.drain();
            spk.stop();
            spk.close();
        }

        try {
            session.close().get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        System.out.println("[*] Done. Goodbye!");
    }

    // ── Message handler ──

    private static void handleMessage(LiveServerMessage msg) {
        if (msg.goAway().isPresent()) {
            System.out.println("[!] Server sent goAway (session expiring)");
            return;
        }

        if (msg.setupComplete().isPresent()) {
            System.out.println("[+] Setup complete — ready for audio");
            return;
        }

        msg.serverContent().ifPresent(content -> {
            // Input transcription — what Gemini heard you say
            content.inputTranscription().ifPresent(transcription -> {
                String json = transcription.toJson();
                if (json != null && json.contains("\"text\"")) {
                    String text = extractJsonText(json);
                    if (text != null && !text.isBlank()) {
                        System.out.println("  [You]    " + text);
                    }
                }
            });

            // Model turn — translated audio + optional text
            content.modelTurn().ifPresent(modelContent -> {
                modelContent.parts().ifPresent(parts -> {
                    for (Part part : parts) {
                        // Queue audio for playback
                        part.inlineData().ifPresent(blob -> {
                            byte[] data = blob.data().orElse(null);
                            if (data != null && data.length > 0) {
                                playbackQueue.add(data);
                            }
                        });
                        // Print any text (output transcription)
                        part.text().ifPresent(text -> {
                            if (!text.isBlank()) {
                                System.out.println("  [Gemini] " + text);
                            }
                        });
                    }
                });
            });

            // Turn complete
            boolean turnDone = content.turnComplete().orElse(false);
            if (turnDone) {
                System.out.println("  [Turn complete]");
            }
        });
    }

    // ── Utilities ──

    private static String loadApiKey() {
        java.io.File secretsFile = Paths.get("application-secrets.properties").toFile();
        if (!secretsFile.exists()) {
            secretsFile = Paths.get(System.getProperty("user.dir"), "application-secrets.properties").toFile();
        }
        if (secretsFile.exists()) {
            try {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(secretsFile)) {
                    props.load(fis);
                }
                String key = props.getProperty("gemini.api.key");
                if (key != null && !key.isBlank()) {
                    System.out.println("[*] Loaded API key from " + secretsFile.getAbsolutePath());
                    return key.trim();
                }
            } catch (Exception e) {
                System.err.println("[!] Failed to read secrets file: " + e.getMessage());
            }
        }
        return System.getenv("GEMINI_API_KEY");
    }

    private static String extractJsonText(String json) {
        int idx = json.indexOf("\"text\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + 6);
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        start++;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') sb.append('"');
                else if (next == '\\') sb.append('\\');
                else if (next == 'n') sb.append('\n');
                else sb.append(next);
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }
}
