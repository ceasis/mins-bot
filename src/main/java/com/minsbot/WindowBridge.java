package com.minsbot;

import javafx.application.Platform;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.List;

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

    // Pre-fullscreen snapshot — used to faithfully restore the window when
    // exiting Sentry Mode. JavaFX's built-in restore is unreliable on Windows.
    private double preFsX = Double.NaN;
    private double preFsY = Double.NaN;
    private double preFsW = Double.NaN;
    private double preFsH = Double.NaN;

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

    /**
     * Copy text to the system clipboard. Used by the in-WebView "Copy chat"
     * button — JavaFX's embedded WebView has flaky support for both
     * {@code navigator.clipboard.writeText} (returns rejected Promise) and
     * {@code document.execCommand('copy')} (silently fails on hidden
     * textareas), so the most reliable path is a JS → Java bridge to the
     * real native clipboard.
     *
     * @return true if the copy succeeded
     */
    public boolean copyToClipboard(String text) {
        if (text == null) text = "";
        final String payload = text;
        try {
            final boolean[] ok = {false};
            Runnable copy = () -> {
                try {
                    javafx.scene.input.Clipboard cb =
                            javafx.scene.input.Clipboard.getSystemClipboard();
                    javafx.scene.input.ClipboardContent content =
                            new javafx.scene.input.ClipboardContent();
                    content.putString(payload);
                    cb.setContent(content);
                    ok[0] = true;
                } catch (Exception e) {
                    System.err.println("[WindowBridge] copyToClipboard failed: " + e.getMessage());
                }
            };
            // Clipboard access must run on the JavaFX Application Thread.
            if (Platform.isFxApplicationThread()) {
                copy.run();
            } else {
                java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                Platform.runLater(() -> { try { copy.run(); } finally { done.countDown(); } });
                done.await(2, java.util.concurrent.TimeUnit.SECONDS);
            }
            return ok[0];
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Open an arbitrary URL in the user's default system browser.
     * Used for OAuth flows so the user has real back/forward/close controls
     * instead of being trapped inside the WebView.
     */
    public boolean openUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return true;
            }
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (Exception e) {
            System.err.println("[WindowBridge] Failed to open URL: " + e.getMessage());
            return false;
        }
    }

    /** Minimize window to taskbar. */
    public void minimize() {
        Platform.runLater(() -> stage.setIconified(true));
    }

    /**
     * Enter exclusive full-screen mode for Sentry Mode (covers taskbar).
     *
     * <p>Snaps the stage to expanded chat dimensions first — JavaFX restores those
     * bounds on fullscreen exit, so without this the user could end up back at the
     * collapsed-ball size if they triggered Sentry while the bot was a ball.
     *
     * <p>Disables JavaFX's built-in Esc-to-exit so the JS Esc handler in sentry.js
     * fires (otherwise JavaFX swallows the key and the overlay stays visible while
     * the window leaves fullscreen).
     */
    public void enterFullscreen() {
        Platform.runLater(() -> {
            // Snapshot the pre-fullscreen geometry so we can restore it precisely on exit.
            preFsX = stage.getX();
            preFsY = stage.getY();
            preFsW = stage.getWidth();
            preFsH = stage.getHeight();
            if (!expanded) {
                stage.setWidth(expandedWidth);
                stage.setHeight(expandedHeight);
                expanded = true;
            }
            stage.setFullScreenExitHint("");
            stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
            stage.setFullScreen(true);
        });
    }

    /** Exit full-screen mode (called from JS when leaving Sentry Mode). */
    public void exitFullscreen() {
        Platform.runLater(() -> {
            stage.setFullScreen(false);
            // JavaFX's auto-restore is unreliable on Windows — apply the snapshot
            // ourselves and force the window to expanded chat dimensions if the
            // snapshot is missing or smaller than the chat needs.
            double targetW = !Double.isNaN(preFsW) && preFsW >= expandedWidth ? preFsW : expandedWidth;
            double targetH = !Double.isNaN(preFsH) && preFsH >= expandedHeight ? preFsH : expandedHeight;
            stage.setWidth(targetW);
            stage.setHeight(targetH);
            if (!Double.isNaN(preFsX) && !Double.isNaN(preFsY)) {
                stage.setX(preFsX);
                stage.setY(preFsY);
            }
            expanded = true;
            stage.toFront();
            stage.requestFocus();
        });
    }

    public boolean isFullscreen() {
        return stage.isFullScreen();
    }

    /**
     * Open Mins Bot in a chromeless Chromium app-mode window (no URL bar, no tabs)
     * via {@code --app=URL}. Tries Chrome → Edge → Brave → Vivaldi. Falls back to
     * the default system browser if nothing Chromium-based is installed.
     */
    public boolean openInBrowser() {
        String url = "http://localhost:" + serverPort + "/?full=1";
        if (launchAppMode(url)) return true;

        // Fallback: default browser (URL bar will be visible)
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return true;
            }
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

    /** Try Chrome/Edge/Brave/Vivaldi with {@code --app=URL}. Returns true if one launched. */
    private boolean launchAppMode(String url) {
        String programFiles = System.getenv("ProgramFiles");
        String programFiles86 = System.getenv("ProgramFiles(x86)");
        String localAppData = System.getenv("LOCALAPPDATA");

        List<String> candidates = List.of(
                // Chrome
                programFiles   + "\\Google\\Chrome\\Application\\chrome.exe",
                programFiles86 + "\\Google\\Chrome\\Application\\chrome.exe",
                localAppData   + "\\Google\\Chrome\\Application\\chrome.exe",
                // Edge
                programFiles   + "\\Microsoft\\Edge\\Application\\msedge.exe",
                programFiles86 + "\\Microsoft\\Edge\\Application\\msedge.exe",
                // Brave
                programFiles   + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                programFiles86 + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                localAppData   + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                // Vivaldi
                localAppData   + "\\Vivaldi\\Application\\vivaldi.exe",
                programFiles   + "\\Vivaldi\\Application\\vivaldi.exe"
        );

        String dataDir = localAppData == null ? System.getProperty("user.home") : localAppData;
        String userDataDir = dataDir + "\\MinsBotBrowser";

        for (String path : candidates) {
            if (path == null) continue;
            File exe = new File(path);
            if (!exe.isFile()) continue;
            try {
                new ProcessBuilder(
                        exe.getAbsolutePath(),
                        "--app=" + url,
                        "--user-data-dir=" + userDataDir,
                        "--no-first-run",
                        "--no-default-browser-check"
                )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
                return true;
            } catch (Exception e) {
                System.err.println("[WindowBridge] Failed to launch " + exe + ": " + e.getMessage());
            }
        }
        return false;
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
