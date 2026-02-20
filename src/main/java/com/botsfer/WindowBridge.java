package com.botsfer;

import javafx.application.Platform;
import javafx.stage.Stage;

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
    private volatile boolean expanded;

    public WindowBridge(Stage stage, int collapsedWidth, int collapsedHeight,
                        int expandedWidth, int expandedHeight,
                        NativeVoiceService nativeVoiceService) {
        this.stage = stage;
        this.collapsedWidth = collapsedWidth;
        this.collapsedHeight = collapsedHeight;
        this.expandedWidth = expandedWidth;
        this.expandedHeight = expandedHeight;
        this.nativeVoiceService = nativeVoiceService;
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
