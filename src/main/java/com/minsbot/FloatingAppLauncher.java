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
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> e.consume());
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            e.consume();
            int x = (int) e.getX();
            int y = (int) e.getY();
            Platform.runLater(() -> {
                forwardClickToPage(engine, x, y);
                webView.requestFocus(); // so keyboard input goes to the window
            });
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
     */
    private static void forwardClickToPage(WebEngine engine, int x, int y) {
        try {
            String script = "(function(){ var el = document.elementFromPoint(" + x + "," + y + "); if(el){ el.focus(); el.click(); } })();";
            engine.executeScript(script);
        } catch (Exception ignored) {
            // Page may not be ready or JS disabled
        }
    }

    /** Re-read bot name from config and update the window title. Called on startup and by ConfigScanService. */
    public static void refreshBotName() {
        if (primaryStageRef == null) return;
        String name = SystemContextProvider.loadBotName();
        String title = (name != null && !name.isBlank()) ? "Mins Bot - " + name : "Mins Bot";
        Platform.runLater(() -> primaryStageRef.setTitle(title));
    }
}
