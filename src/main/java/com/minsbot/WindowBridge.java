package com.minsbot;

import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.net.URI;

/**
 * Exposed to the WebView JavaScript as window.java for expand/collapse.
 */
public class WindowBridge {

    private final Stage stage;
    private final int collapsedWidth;
    private final int collapsedHeight;
    private final int expandedWidth;
    private final int expandedHeight;
    private final NativeVoiceService nativeVoiceService;
    private final int serverPort;
    private volatile boolean expanded;

    public WindowBridge(Stage stage, int collapsedWidth, int collapsedHeight,
                        int expandedWidth, int expandedHeight,
                        NativeVoiceService nativeVoiceService,
                        int serverPort) {
        this.stage = stage;
        this.collapsedWidth = collapsedWidth;
        this.collapsedHeight = collapsedHeight;
        this.expandedWidth = expandedWidth;
        this.expandedHeight = expandedHeight;
        this.nativeVoiceService = nativeVoiceService;
        this.serverPort = serverPort;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void expand() {
        expanded = true;
        Platform.runLater(() -> {
            stage.setWidth(expandedWidth);
            stage.setHeight(expandedHeight);
            stage.toFront();
            stage.requestFocus();
        });
    }

    public void collapse() {
        expanded = false;
        Platform.runLater(() -> {
            stage.setWidth(collapsedWidth);
            stage.setHeight(collapsedHeight);
        });
    }

    /** Minimize window to taskbar. */
    public void minimize() {
        Platform.runLater(() -> stage.setIconified(true));
    }

    /**
     * Open Mins Bot in the user's default system browser (Chrome, Edge, etc.)
     * with {@code ?full=1} so it shows the full UI (tab bar visible). Returns
     * true on success.
     */
    public boolean openInBrowser() {
        String url = "http://localhost:" + serverPort + "/?full=1";
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return true;
            }
            // Fallback for Windows if Desktop isn't supported (e.g. headless JRE)
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (Exception e) {
            System.err.println("[WindowBridge] Failed to open browser: " + e.getMessage());
            return false;
        }
    }

    /** Close the application immediately. */
    public void close() {
        Platform.runLater(() -> {
            stage.close();
            Platform.exit();
            // Use halt to skip Spring's graceful shutdown which can hang
            // waiting for background threads (audio, screen watchers, etc.)
            Runtime.getRuntime().halt(0);
        });
    }

    /** Current window X (for drag). */
    public double getX() {
        return stage.getX();
    }

    /** Current window Y (for drag). */
    public double getY() {
        return stage.getY();
    }

    /** Move window (call from JS when dragging the ball). */
    public void setPosition(double x, double y) {
        Platform.runLater(() -> {
            stage.setX(x);
            stage.setY(y);
        });
    }

    /** Whether native voice recognition is available on this OS. */
    public boolean isNativeVoiceAvailable() {
        return nativeVoiceService != null && nativeVoiceService.isAvailable();
    }

    /** Start one-shot native voice capture. */
    public boolean startNativeVoice() {
        return nativeVoiceService != null && nativeVoiceService.start();
    }

    /** Stop current native voice capture. */
    public void stopNativeVoice() {
        if (nativeVoiceService != null) {
            nativeVoiceService.stop();
        }
    }

    /** True while native voice capture is active. */
    public boolean isNativeVoiceListening() {
        return nativeVoiceService != null && nativeVoiceService.isListening();
    }

    /** Retrieve latest transcript once (returns empty string when none). */
    public String consumeNativeVoiceTranscript() {
        return nativeVoiceService == null ? "" : nativeVoiceService.consumeTranscript();
    }

    /** Retrieve latest voice error once (returns empty string when none). */
    public String consumeNativeVoiceError() {
        return nativeVoiceService == null ? "" : nativeVoiceService.consumeError();
    }

    /** Cleanup hook. */
    public void shutdownNativeVoice() {
        if (nativeVoiceService != null) {
            nativeVoiceService.shutdown();
        }
    }
}
