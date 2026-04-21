package com.minsbot.agent.tools;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Global hotkey: <b>Ctrl+Shift+V</b> cycles through clipboard history.
 * <p>
 * Each press within a 400ms debounce window increments a counter. When the
 * window closes, the service writes the Nth-from-latest clipboard entry to
 * the system clipboard and sends a plain Ctrl+V so the focused app pastes it.
 * <ul>
 *   <li>1 press  → paste most recent clipboard entry</li>
 *   <li>2 presses → paste the entry before that</li>
 *   <li>N presses → paste the Nth-latest</li>
 * </ul>
 */
@Service
public class ClipboardCycleService implements NativeKeyListener {

    private static final Logger log = LoggerFactory.getLogger(ClipboardCycleService.class);

    /** Time window for counting consecutive presses. */
    private static final long DEBOUNCE_MS = 400;

    private final ClipboardHistoryTools history;

    private volatile boolean hookOwned = false;
    private volatile boolean ctrlDown = false;
    private volatile boolean shiftDown = false;

    private final AtomicInteger pressCount = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "clipboard-cycle");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> pendingPaste;

    public ClipboardCycleService(ClipboardHistoryTools history) {
        this.history = history;
    }

    @PostConstruct
    public void init() {
        try {
            // JNativeHook is a singleton — register only if not already up (GlobalHotkeyService
            // may have registered first; adding a listener is always safe either way).
            java.util.logging.Logger jnhLog =
                    java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName());
            jnhLog.setLevel(Level.WARNING);
            jnhLog.setUseParentHandlers(false);

            try {
                GlobalScreen.registerNativeHook();
                hookOwned = true;
            } catch (IllegalStateException alreadyRegistered) {
                // Fine — another component already registered the hook.
            }
            GlobalScreen.addNativeKeyListener(this);
            log.info("[ClipboardCycle] Registered Ctrl+Shift+V cycle hotkey");
        } catch (NativeHookException e) {
            log.warn("[ClipboardCycle] Failed to register native hook: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            GlobalScreen.removeNativeKeyListener(this);
            if (hookOwned) GlobalScreen.unregisterNativeHook();
        } catch (Exception ignored) {}
        scheduler.shutdownNow();
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        int code = e.getKeyCode();
        if (code == NativeKeyEvent.VC_CONTROL) ctrlDown = true;
        else if (code == NativeKeyEvent.VC_SHIFT) shiftDown = true;
        else if (code == NativeKeyEvent.VC_V && ctrlDown && shiftDown) {
            onCycleHotkey();
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        int code = e.getKeyCode();
        if (code == NativeKeyEvent.VC_CONTROL) ctrlDown = false;
        else if (code == NativeKeyEvent.VC_SHIFT) shiftDown = false;
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) { /* unused */ }

    /**
     * Each press increments the counter and (re)schedules the paste. The paste
     * fires once, DEBOUNCE_MS after the LAST press in a burst — so pressing
     * quickly multiple times selects how far back in history to paste from.
     */
    private void onCycleHotkey() {
        int n = pressCount.incrementAndGet();
        log.debug("[ClipboardCycle] Hotkey press #{}", n);

        ScheduledFuture<?> prev = pendingPaste;
        if (prev != null) prev.cancel(false);
        pendingPaste = scheduler.schedule(this::firePaste, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void firePaste() {
        int howMany = pressCount.getAndSet(0);
        if (howMany <= 0) return;

        String entry = history.getEntryFromEnd(howMany);
        if (entry == null) {
            log.info("[ClipboardCycle] No clipboard entry at index {} (history smaller)", howMany);
            return;
        }

        try {
            // Set the system clipboard to the selected entry
            StringSelection sel = new StringSelection(entry);
            Clipboard sysClip = Toolkit.getDefaultToolkit().getSystemClipboard();
            sysClip.setContents(sel, sel);

            // Small settle delay so the new clipboard value is visible to the focused app
            Thread.sleep(60);

            // Send a plain Ctrl+V to the focused app
            Robot robot = new Robot();
            robot.setAutoDelay(20);
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_CONTROL);

            log.info("[ClipboardCycle] Pasted entry #{} from end: {}",
                    howMany, entry.length() > 40 ? entry.substring(0, 40) + "…" : entry);
        } catch (Exception e) {
            log.warn("[ClipboardCycle] Paste failed: {}", e.getMessage());
        }
    }
}
