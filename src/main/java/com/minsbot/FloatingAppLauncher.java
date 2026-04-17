package com.minsbot;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.Cursor;
import javafx.scene.paint.Color;
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
        // Cache as bitmap so dragging the window repaints from cache instead of re-rendering the page (reduces flicker)
        webView.setCache(true);
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
                nativeVoiceService,
                port
        );
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                try {
                    JSObject window = (JSObject) engine.executeScript("window");
                    if (window != null) {
                        window.setMember("java", bridge);
                    }
                    // Auto-expand on load (regular window is always expanded)
                    // Also mark as embedded so tab bar is hidden in app mode
                    engine.executeScript(
                            "document.body.classList.add('embedded-minsbot');"
                            + "document.getElementById('root').classList.add('expanded');"
                            + "setTimeout(function(){var i=document.getElementById('input');if(i)i.focus();},80);"
                    );
                    bridge.expand();
                } catch (Exception e) {
                    // Bridge may fail in some environments
                }
            }
        });

        Scene scene = new Scene(webView, expandedWidth, expandedHeight);
        // Opaque fill so window move repaints don't show transparency or garbage (reduces flicker when dragging)
        scene.setFill(Color.web("#0a0a0c"));
        // Forward mouse clicks into the page via JS (keyboard works; on some builds clicks don't reach WebView).
        final double[] pressPos = new double[2];
        final boolean[] isDrag = {false};
        final boolean[] textPress = {false};
        // Title bar height (must match CSS .title-bar height) for drag detection
        final int titleBarHeight = 32;
        final double[] titleBarPressStage = new double[2];
        final boolean[] draggingTitleBar = {false};
        final boolean[] didTitleBarDrag = {false};

        // Title bar drag: press/drag in top titleBarHeight px moves the window
        // Uses SCREEN coordinates (not scene) to avoid flicker — scene coords shift as the window moves
        final double[] titleBarPressScreen = new double[2];
        // Width of the window-control buttons area (minimize + close, 46px each + buffer)
        final int controlsWidth = 100;
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            isDrag[0] = false; // always reset drag flag on any press
            textPress[0] = false;
            if (e.getY() < titleBarHeight) {
                // Let clicks on the close/minimize buttons pass through to WebView natively
                if (e.getX() > scene.getWidth() - controlsWidth) {
                    return; // don't consume — let native WebView handle button clicks
                }
                draggingTitleBar[0] = true;
                didTitleBarDrag[0] = false;
                titleBarPressScreen[0] = e.getScreenX();
                titleBarPressScreen[1] = e.getScreenY();
                titleBarPressStage[0] = primaryStage.getX();
                titleBarPressStage[1] = primaryStage.getY();
                e.consume();
                return;
            }
            pressPos[0] = e.getX();
            pressPos[1] = e.getY();
            // If press starts in an editable text control, let native WebView handle selection/caret.
            textPress[0] = isTextEditableAt(engine, (int) e.getX(), (int) e.getY());
            if (textPress[0]) {
                int x = (int) e.getX();
                int y = (int) e.getY();
                Platform.runLater(() -> focusEditableAt(engine, x, y));
            }
        });

        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (draggingTitleBar[0]) {
                didTitleBarDrag[0] = true;
                double dx = e.getScreenX() - titleBarPressScreen[0];
                double dy = e.getScreenY() - titleBarPressScreen[1];
                primaryStage.setX(titleBarPressStage[0] + dx);
                primaryStage.setY(titleBarPressStage[1] + dy);
                e.consume();
                return;
            }
            if (Math.abs(e.getX() - pressPos[0]) > 3 || Math.abs(e.getY() - pressPos[1]) > 3) {
                isDrag[0] = true;
            }
        });

        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                boolean wasTitleBarDrag = draggingTitleBar[0] && didTitleBarDrag[0];
                draggingTitleBar[0] = false;
                boolean wasTextPress = textPress[0];
                textPress[0] = false;
                // Forward synthetic click if this was not a drag
                if (!isDrag[0] && !wasTitleBarDrag && !wasTextPress) {
                    int x = (int) e.getX();
                    int y = (int) e.getY();
                    Platform.runLater(() -> {
                        boolean textTarget = forwardClickToPage(engine, x, y);
                        // Preserve input caret interactions (click-in-middle, selection).
                        // Request WebView focus only for non-text targets.
                        if (!textTarget) {
                            webView.requestFocus();
                        }
                    });
                }
            }
        });
        // Forward hover to WebView content so :hover styles and pointer cursor work in JavaFX.
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            int x = (int) e.getX();
            int y = (int) e.getY();
            // Always show hand cursor on native title-bar window controls (minimize/close area).
            if (y < titleBarHeight && x > scene.getWidth() - controlsWidth) {
                scene.setCursor(Cursor.HAND);
                webView.setCursor(Cursor.HAND);
                syncHoverToPage(engine, x, y);
                return;
            }
            boolean interactive = syncHoverToPage(engine, x, y);
            Cursor c = interactive ? Cursor.HAND : Cursor.DEFAULT;
            scene.setCursor(c);
            webView.setCursor(c);
        });
        scene.addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            syncHoverToPage(engine, -1, -1);
            scene.setCursor(Cursor.DEFAULT);
            webView.setCursor(Cursor.DEFAULT);
        });
        primaryStage.setScene(scene);
        primaryStage.initStyle(StageStyle.UNDECORATED);

        // App icon — AI bot (replaces default Java icon in taskbar/alt-tab)
        try {
            primaryStage.getIcons().addAll(
                    new javafx.scene.image.Image(getClass().getResourceAsStream("/static/bot-icon.png")),
                    new javafx.scene.image.Image(getClass().getResourceAsStream("/static/bot-icon-64.png")),
                    new javafx.scene.image.Image(getClass().getResourceAsStream("/static/bot-icon-32.png")),
                    new javafx.scene.image.Image(getClass().getResourceAsStream("/static/bot-icon-16.png"))
            );
        } catch (Exception e) {
            // Icon loading is non-critical
        }
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

        // Start animated-eyes taskbar icon
        try {
            springContext.getBean(IconAnimator.class).start(primaryStage);
        } catch (Exception e) {
            // Icon animation is non-critical
        }

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
    private static boolean forwardClickToPage(WebEngine engine, int x, int y) {
        try {
            String script = "(function(){"
                    + "var el = document.elementFromPoint(" + x + "," + y + ");"
                    + "if(!el) return false;"
                    // If we hit an SVG child, walk up to the nearest interactive HTML ancestor
                    + "var target = el.closest('button,a,input,select,textarea,label,[onclick],[role=button]');"
                    + "if(!target) target = el;"
                    + "var textTarget = target.matches('input:not([type=button]):not([type=submit]):not([type=checkbox]):not([type=radio]),textarea,[contenteditable]');"
                    // For text fields, DO NOT dispatch synthetic click events here.
                    // Native events already occurred; dispatching again breaks drag-selection/highlight.
                    + "var opts = {bubbles:true,cancelable:true,clientX:" + x + ",clientY:" + y + ",view:window};"
                    + "if(textTarget){"
                    + "  if(typeof target.focus==='function'){"
                    + "    try{ target.focus({preventScroll:true}); }catch(_){ target.focus(); }"
                    + "  }"
                    + "  return true;"
                    + "}"
                    + "if(typeof target.focus === 'function') target.focus();"
                    // Dispatch full mouse event sequence for maximum compatibility (buttons, links, etc.)
                    + "target.dispatchEvent(new MouseEvent('mousedown', opts));"
                    + "target.dispatchEvent(new MouseEvent('mouseup', opts));"
                    + "target.dispatchEvent(new MouseEvent('click', opts));"
                    + "return false;"
                    + "})();";
            Object result = engine.executeScript(script);
            return result instanceof Boolean b && b;
        } catch (Exception ignored) {
            // Page may not be ready or JS disabled
            return false;
        }
    }

    /**
     * Keep WebView hover state in sync when JavaFX intercepts mouse events.
     * Adds/removes `.mins-javafx-hover` on the closest interactive element and returns whether one is hovered.
     */
    private static boolean syncHoverToPage(WebEngine engine, int x, int y) {
        try {
            String script = "(function(){"
                    + "if(!window.__minsHover){window.__minsHover=null;}"
                    + "var target=null;"
                    + "if(" + x + ">=0 && " + y + ">=0){"
                    + "  var el=document.elementFromPoint(" + x + "," + y + ");"
                    + "  if(el){target=el.closest('button,a,input,select,textarea,label,[onclick],[role=button]');}"
                    + "}"
                    + "if(window.__minsHover!==target){"
                    + "  if(window.__minsHover){window.__minsHover.classList.remove('mins-javafx-hover');}"
                    + "  if(target){target.classList.add('mins-javafx-hover');}"
                    + "  window.__minsHover=target;"
                    + "}"
                    + "return !!target;"
                    + "})();";
            Object result = engine.executeScript(script);
            return result instanceof Boolean b && b;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** True when point is over editable text element (input/textarea/contenteditable). */
    private static boolean isTextEditableAt(WebEngine engine, int x, int y) {
        try {
            String script = "(function(){"
                    + "var el=document.elementFromPoint(" + x + "," + y + ");"
                    + "if(!el) return false;"
                    + "var t=el.closest('input,textarea,[contenteditable],select');"
                    + "if(!t) return false;"
                    + "if(t.matches('input[type=button],input[type=submit],input[type=checkbox],input[type=radio],input[type=range],input[type=color],input[type=file]')) return false;"
                    + "return true;"
                    + "})();";
            Object result = engine.executeScript(script);
            return result instanceof Boolean b && b;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Focus editable element at viewport point without dispatching synthetic click events. */
    private static void focusEditableAt(WebEngine engine, int x, int y) {
        try {
            String script = "(function(){"
                    + "var el=document.elementFromPoint(" + x + "," + y + ");"
                    + "if(!el) return;"
                    + "var t=el.closest('input,textarea,[contenteditable],select');"
                    + "if(!t) return;"
                    + "if(t.matches('input[type=button],input[type=submit],input[type=checkbox],input[type=radio],input[type=range],input[type=color],input[type=file]')) return;"
                    + "if(typeof t.focus==='function'){"
                    + "  try{ t.focus({preventScroll:true}); }catch(_){ t.focus(); }"
                    + "}"
                    + "})();";
            engine.executeScript(script);
        } catch (Exception ignored) {
            // ignore
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

    /**
     * Get the current window bounds as [x, y, width, height] in screen pixels.
     * Returns null if the window is not available.
     * Used by vision/click tools to know where the bot window is so they can
     * avoid clicking inside it and tell the AI to ignore that screen region.
     */
    public static int[] getWindowBounds() {
        if (primaryStageRef == null) return null;
        try {
            // Read on FX thread to avoid threading issues
            java.util.concurrent.atomic.AtomicReference<int[]> ref = new java.util.concurrent.atomic.AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    ref.set(new int[]{
                            (int) primaryStageRef.getX(),
                            (int) primaryStageRef.getY(),
                            (int) primaryStageRef.getWidth(),
                            (int) primaryStageRef.getHeight()
                    });
                } finally {
                    latch.countDown();
                }
            });
            latch.await(500, TimeUnit.MILLISECONDS);
            return ref.get();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if a screen coordinate is inside the bot window.
     */
    public static boolean isInsideWindow(int screenX, int screenY) {
        int[] bounds = getWindowBounds();
        if (bounds == null) return false;
        return screenX >= bounds[0] && screenX <= bounds[0] + bounds[2]
                && screenY >= bounds[1] && screenY <= bounds[1] + bounds[3];
    }

    /** Move the bot window to specific screen coordinates. */
    public static void moveTo(double x, double y) {
        if (primaryStageRef == null) return;
        Platform.runLater(() -> {
            primaryStageRef.setX(x);
            primaryStageRef.setY(y);
        });
    }

    /** Resize the bot window. */
    public static void resizeTo(double width, double height) {
        if (primaryStageRef == null) return;
        Platform.runLater(() -> {
            primaryStageRef.setWidth(width);
            primaryStageRef.setHeight(height);
        });
    }

    /** Minimize (iconify) the bot window. */
    public static void minimize() {
        if (primaryStageRef == null) return;
        Platform.runLater(() -> primaryStageRef.setIconified(true));
    }

    /** Restore the bot window from minimized state. */
    public static void restore() {
        if (primaryStageRef == null) return;
        Platform.runLater(() -> {
            primaryStageRef.setIconified(false);
            primaryStageRef.setOpacity(1);
            primaryStageRef.toFront();
        });
    }

    /** Get screen dimensions (logical). */
    public static int[] getScreenSize() {
        java.awt.Dimension d = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        return new int[]{d.width, d.height};
    }
}
