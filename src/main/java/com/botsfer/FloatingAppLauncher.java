package com.botsfer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import netscape.javascript.JSObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX entry point: starts Spring Boot in background, then shows the floating window.
 */
public class FloatingAppLauncher extends Application {

    private static ConfigurableApplicationContext springContext;
    private static final CountDownLatch springReady = new CountDownLatch(1);
    @SuppressWarnings("unused") // prevent GC of the bridge exposed to JS
    private WindowBridge bridge;

    public static void main(String[] args) {
        Application.launch(FloatingAppLauncher.class, args);
    }

    @Override
    public void init() {
        Thread springThread = new Thread(() -> {
            springContext = new SpringApplicationBuilder(BotsferApplication.class)
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
        boolean alwaysOnTop = env.getProperty("app.window.always-on-top", Boolean.class, true);

        WebView webView = new WebView();
        webView.setFocusTraversable(true);
        webView.setContextMenuEnabled(false);
        webView.addEventFilter(MouseEvent.DRAG_DETECTED, MouseEvent::consume);
        webView.addEventFilter(DragEvent.ANY, DragEvent::consume);
        WebEngine engine = webView.getEngine();
        String url = "http://localhost:" + port + "/";
        engine.load(url);

        // Make the WebView node itself transparent (removes default white background)
        webView.setStyle("-fx-background-color: transparent;");

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
                    // Force the WebView's internal page background to transparent
                    engine.executeScript("document.documentElement.style.background='transparent';"
                            + "document.body.style.background='transparent';");

                    // Make WebView's internal Chromium surface truly transparent
                    // (removes the white rectangle behind the HTML content)
                    try {
                        Field pageField = engine.getClass().getDeclaredField("page");
                        pageField.setAccessible(true);
                        Object page = pageField.get(engine);
                        Method setBackgroundColor = page.getClass()
                                .getMethod("setBackgroundColor", int.class);
                        setBackgroundColor.invoke(page, 0); // 0 = fully transparent ARGB
                    } catch (Exception bgEx) {
                        System.err.println("[Mins Bot] Could not set transparent WebView background: "
                                + bgEx.getMessage());
                    }
                } catch (Exception e) {
                    // Bridge may fail in some environments
                }
            }
        });

        Scene scene = new Scene(webView, collapsedWidth, collapsedHeight, Color.TRANSPARENT);
        scene.setFill(Color.TRANSPARENT);

        // JavaFX WebView on Windows transparent stages does not route native mouse
        // events to Chromium. We intercept ALL mouse events here and forward
        // non-ball clicks into JavaScript via document.elementFromPoint().
        final double[] anchor = new double[4];
        final boolean[] dragging = {false};
        final boolean[] moved = {false};
        final double threshold = 5.0;

        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            boolean inBall = e.getX() < collapsedWidth && e.getY() < collapsedHeight;
            if (inBall) {
                if (e.getClickCount() == 2) {
                    if (bridge.isExpanded()) {
                        bridge.collapse();
                        engine.executeScript("document.getElementById('root').classList.remove('expanded')");
                    } else {
                        bridge.expand();
                        engine.executeScript(
                                "document.getElementById('root').classList.add('expanded');"
                                + "setTimeout(function(){var i=document.getElementById('input');if(i)i.focus();},80);"
                        );
                    }
                    e.consume();
                    return;
                }
                anchor[0] = e.getScreenX();
                anchor[1] = e.getScreenY();
                anchor[2] = primaryStage.getX();
                anchor[3] = primaryStage.getY();
                dragging[0] = true;
                moved[0] = false;
            } else {
                // Forward click to the HTML element at (x, y)
                engine.executeScript(String.format(
                        "(function(){var el=document.elementFromPoint(%d,%d);"
                        + "if(!el)return;"
                        + "if(el.tagName==='INPUT'||el.tagName==='TEXTAREA'){el.focus();return;}"
                        + "if(typeof el.click==='function'){el.click();return;}"
                        + "var ev=new MouseEvent('click',{bubbles:true,cancelable:true,view:window});"
                        + "el.dispatchEvent(ev);"
                        + "})()",
                        (int) e.getX(), (int) e.getY()
                ));
            }
            e.consume();
        });

        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!dragging[0]) return;
            double dx = e.getScreenX() - anchor[0];
            double dy = e.getScreenY() - anchor[1];
            if (!moved[0] && (Math.abs(dx) > threshold || Math.abs(dy) > threshold)) {
                moved[0] = true;
            }
            if (moved[0]) {
                primaryStage.setX(anchor[2] + dx);
                primaryStage.setY(anchor[3] + dy);
            }
            e.consume();
        });

        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (dragging[0]) {
                if (!moved[0] && !bridge.isExpanded()) {
                    bridge.expand();
                    engine.executeScript(
                            "document.getElementById('root').classList.add('expanded');"
                            + "setTimeout(function(){var i=document.getElementById('input');if(i)i.focus();},80);"
                    );
                }
                dragging[0] = false;
                moved[0] = false;
            }
            e.consume();
        });

        primaryStage.setScene(scene);
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setAlwaysOnTop(alwaysOnTop);
        primaryStage.setWidth(collapsedWidth);
        primaryStage.setHeight(collapsedHeight);
        primaryStage.setResizable(false);
        primaryStage.setTitle("Mins Bot");

        if (initialX >= 0 && initialY >= 0) {
            primaryStage.setX(initialX);
            primaryStage.setY(initialY);
        } else {
            Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
            primaryStage.setX(bounds.getMaxX() - collapsedWidth - 24);
            primaryStage.setY(bounds.getMaxY() - collapsedHeight - 24);
        }

        primaryStage.show();
        Platform.runLater(webView::requestFocus);
        primaryStage.setOnCloseRequest(e -> {
            bridge.shutdownNativeVoice();
            Platform.exit();
            if (springContext != null) {
                SpringApplication.exit(springContext);
            }
        });
    }
}
