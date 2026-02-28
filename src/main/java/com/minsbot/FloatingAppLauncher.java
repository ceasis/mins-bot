package com.minsbot;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import netscape.javascript.JSObject;
import com.minsbot.agent.SystemContextProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX entry point: starts Spring Boot in background, then shows the window.
 */
public class FloatingAppLauncher extends Application {

    private static ConfigurableApplicationContext springContext;
    private static final CountDownLatch springReady = new CountDownLatch(1);
    private static Stage primaryStageRef;
    @SuppressWarnings("unused") // prevent GC of the bridge exposed to JS
    private WindowBridge bridge;

    public static void main(String[] args) {
        Application.launch(FloatingAppLauncher.class, args);
    }

    @Override
    public void init() {
        Thread springThread = new Thread(() -> {
            springContext = new SpringApplicationBuilder(MinsbotApplication.class)
                    .headless(false)
                    .run();
            springReady.countDown();
        });
        springThread.setDaemon(false);
        springThread.start();
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        try {
            if (!springReady.await(60, TimeUnit.SECONDS)) {
                throw new RuntimeException("Spring Boot did not start in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        Environment env = springContext.getBean(Environment.class);
        int port = env.getProperty("server.port", Integer.class, 8765);
        int collapsedWidth = env.getProperty("app.window.collapsed.width", Integer.class, 64);
        int collapsedHeight = env.getProperty("app.window.collapsed.height", Integer.class, 64);
        int expandedWidth = env.getProperty("app.window.expanded.width", Integer.class, 456);
        int expandedHeight = env.getProperty("app.window.expanded.height", Integer.class, 520);
        int initialX = env.getProperty("app.window.initial.x", Integer.class, -1);
        int initialY = env.getProperty("app.window.initial.y", Integer.class, -1);
        boolean alwaysOnTop = env.getProperty("app.window.always-on-top", Boolean.class, false);

        WebView webView = new WebView();
        webView.setFocusTraversable(true);
        webView.setContextMenuEnabled(false);
        WebEngine engine = webView.getEngine();
        String url = "http://localhost:" + port + "/";
        engine.load(url);

        ChatService chatService = springContext.getBean(ChatService.class);
        NativeVoiceService nativeVoiceService = new NativeVoiceService(chatService);
        bridge = new WindowBridge(
                primaryStage,
                collapsedWidth,
                collapsedHeight,
                expandedWidth,
                expandedHeight,
                nativeVoiceService
        );
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                try {
                    JSObject window = (JSObject) engine.executeScript("window");
                    if (window != null) {
                        window.setMember("java", bridge);
                    }
                    // Auto-expand on load (regular window is always expanded)
                    engine.executeScript(
                            "document.getElementById('root').classList.add('expanded');"
                            + "setTimeout(function(){var i=document.getElementById('input');if(i)i.focus();},80);"
                    );
                    bridge.expand();
                } catch (Exception e) {
                    // Bridge may fail in some environments
                }
            }
        });

        Scene scene = new Scene(webView, expandedWidth, expandedHeight);
        // Forward mouse clicks into the page via JS: on some JavaFX/WebKit builds mouse events
        // never reach the web content (keyboard/TAB works but clicks don't).
        // We track press position to detect drags (text selection) vs clicks.
        final double[] pressPos = new double[2];
        final boolean[] isDrag = {false};

        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            pressPos[0] = e.getX();
            pressPos[1] = e.getY();
            isDrag[0] = false;
            // Don't consume — let the WebView handle it for text selection
        });

        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            // If mouse moved more than 3px from press point, it's a drag (text selection)
            if (Math.abs(e.getX() - pressPos[0]) > 3 || Math.abs(e.getY() - pressPos[1]) > 3) {
                isDrag[0] = true;
            }
            // Don't consume — let WebView handle drag for text selection
        });

        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            // Only forward synthetic click if this was NOT a drag (text selection)
            if (!isDrag[0]) {
                int x = (int) e.getX();
                int y = (int) e.getY();
                Platform.runLater(() -> {
                    forwardClickToPage(engine, x, y);
                    webView.requestFocus();
                });
            }
            // Don't consume — let WebView handle it
        });
        primaryStage.setScene(scene);
        primaryStage.initStyle(StageStyle.DECORATED);
        primaryStage.setAlwaysOnTop(alwaysOnTop);
        primaryStage.setWidth(expandedWidth);
        primaryStage.setHeight(expandedHeight);
        primaryStage.setResizable(true);
        primaryStageRef = primaryStage;
        refreshBotName();

        if (initialX >= 0 && initialY >= 0) {
            primaryStage.setX(initialX);
            primaryStage.setY(initialY);
        } else {
            Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
            primaryStage.setX(bounds.getMaxX() - expandedWidth - 24);
            primaryStage.setY(bounds.getMaxY() - expandedHeight - 60);
        }

        primaryStage.show();
        Platform.runLater(webView::requestFocus);
        Runnable quitAction = () -> {
            bridge.shutdownNativeVoice();
            Platform.exit();
            if (springContext != null) {
                SpringApplication.exit(springContext);
            }
            // Force JVM exit so the app closes entirely when user clicks window close
            new Thread(() -> {
                try { Thread.sleep(400); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                System.exit(0);
            }, "minsbot-quit").start();
        };
        primaryStage.setOnCloseRequest(evt -> quitAction.run());
        try {
            springContext.getBean(MinsBotQuitService.class).setQuitRunnable(quitAction);
        } catch (Exception ignored) {
            // Bean may not be available in some tests
        }
    }

    /**
     * Synthesize a click in the page at the given viewport coordinates.
     * Used when the WebView does not deliver mouse events to the content (keyboard works, clicks don't).
     *
     * Walks up from SVG children (path, svg, g, etc.) to find the actual clickable HTML parent,
     * since SVG elements may not properly bubble synthetic click events in JavaFX WebView.
     * Dispatches a full mousedown→mouseup→click sequence for maximum compatibility.
     */
    private static void forwardClickToPage(WebEngine engine, int x, int y) {
        try {
            String script = "(function(){"
                    + "var el = document.elementFromPoint(" + x + "," + y + ");"
                    + "if(!el) return;"
                    // If we hit an SVG child, walk up to the nearest interactive HTML ancestor
                    + "var target = el.closest('button,a,input,select,textarea,label,[onclick],[role=button]');"
                    + "if(!target) target = el;"
                    + "if(typeof target.focus === 'function') target.focus();"
                    // Dispatch full mouse event sequence for maximum compatibility
                    + "var opts = {bubbles:true,cancelable:true,clientX:" + x + ",clientY:" + y + ",view:window};"
                    + "target.dispatchEvent(new MouseEvent('mousedown', opts));"
                    + "target.dispatchEvent(new MouseEvent('mouseup', opts));"
                    + "target.dispatchEvent(new MouseEvent('click', opts));"
                    + "})();";
            engine.executeScript(script);
        } catch (Exception ignored) {
            // Page may not be ready or JS disabled
        }
    }

    /** Saved position/size before hiding, so we can restore without iconify/deiconify. */
    private static double savedX, savedY, savedWidth, savedHeight;
    private static boolean wasAlwaysOnTop;

    /**
     * Hide the Mins Bot window so it doesn't appear in screenshots.
     * Uses move-off-screen + opacity instead of iconify to avoid focus-stealing
     * when restoring. Blocks until the hide is applied on the JavaFX thread.
     */
    public static void hideWindow() {
        if (primaryStageRef == null) return;
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                // Save current position/size
                savedX = primaryStageRef.getX();
                savedY = primaryStageRef.getY();
                savedWidth = primaryStageRef.getWidth();
                savedHeight = primaryStageRef.getHeight();
                wasAlwaysOnTop = primaryStageRef.isAlwaysOnTop();
                // Make invisible and move off-screen (prevents OCR interference and mouse interaction)
                primaryStageRef.setAlwaysOnTop(false);
                primaryStageRef.setOpacity(0);
                primaryStageRef.setX(-10000);
                primaryStageRef.setY(-10000);
            } finally {
                latch.countDown();
            }
        });
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        try { Thread.sleep(150); } catch (InterruptedException ignored) {} // brief settle
    }

    /**
     * Restore the Mins Bot window after hiding it.
     * Does NOT steal focus from the currently active window (e.g. browser).
     */
    public static void showWindow() {
        if (primaryStageRef == null) return;
        Platform.runLater(() -> {
            primaryStageRef.setX(savedX);
            primaryStageRef.setY(savedY);
            primaryStageRef.setWidth(savedWidth);
            primaryStageRef.setHeight(savedHeight);
            primaryStageRef.setOpacity(1);
            primaryStageRef.setAlwaysOnTop(wasAlwaysOnTop);
        });
    }

    /** Re-read bot name from config and update the window title. Called on startup and by ConfigScanService. */
    public static void refreshBotName() {
        if (primaryStageRef == null) return;
        String name = SystemContextProvider.loadBotName();
        String title = (name != null && !name.isBlank()) ? "Mins Bot - " + name : "Mins Bot";
        Platform.runLater(() -> primaryStageRef.setTitle(title));
    }
}
