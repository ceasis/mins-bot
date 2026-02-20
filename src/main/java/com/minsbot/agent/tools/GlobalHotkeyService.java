package com.minsbot.agent.tools;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * System-wide keyboard shortcut listener using JNativeHook.
 * Registers global hotkeys that trigger actions even when Mins Bot is not focused.
 */
@Component
public class GlobalHotkeyService implements NativeKeyListener {

    private static final Logger log = LoggerFactory.getLogger(GlobalHotkeyService.class);

    @Value("${app.hotkeys.enabled:false}")
    private boolean enabled;

    private final ToolExecutionNotifier notifier;
    private volatile boolean hookRegistered = false;

    // Registered hotkeys: key combo string → action description
    private final Map<String, String> hotkeys = new ConcurrentHashMap<>();
    // Fired hotkey events (polled by frontend/AI)
    private final ConcurrentLinkedQueue<String> firedHotkeys = new ConcurrentLinkedQueue<>();

    // Track currently pressed modifier keys
    private volatile boolean ctrlPressed = false;
    private volatile boolean altPressed = false;
    private volatile boolean shiftPressed = false;

    public GlobalHotkeyService(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[Hotkeys] Global hotkeys disabled (app.hotkeys.enabled=false).");
            return;
        }
        try {
            // Suppress JNativeHook's verbose logging
            java.util.logging.Logger jnhLogger = java.util.logging.Logger
                    .getLogger(GlobalScreen.class.getPackage().getName());
            jnhLogger.setLevel(Level.WARNING);
            jnhLogger.setUseParentHandlers(false);

            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            hookRegistered = true;
            log.info("[Hotkeys] Global keyboard hook registered.");
        } catch (NativeHookException e) {
            log.error("[Hotkeys] Failed to register native hook: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (hookRegistered) {
            try {
                GlobalScreen.removeNativeKeyListener(this);
                GlobalScreen.unregisterNativeHook();
                log.info("[Hotkeys] Global keyboard hook unregistered.");
            } catch (NativeHookException e) {
                log.error("[Hotkeys] Failed to unregister: {}", e.getMessage());
            }
        }
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        int code = e.getKeyCode();
        if (code == NativeKeyEvent.VC_CONTROL) { ctrlPressed = true; return; }
        if (code == NativeKeyEvent.VC_ALT) { altPressed = true; return; }
        if (code == NativeKeyEvent.VC_SHIFT) { shiftPressed = true; return; }

        // Build combo string
        StringBuilder combo = new StringBuilder();
        if (ctrlPressed) combo.append("Ctrl+");
        if (altPressed) combo.append("Alt+");
        if (shiftPressed) combo.append("Shift+");
        combo.append(NativeKeyEvent.getKeyText(code));

        String comboStr = combo.toString();
        String action = hotkeys.get(comboStr.toLowerCase());
        if (action != null) {
            log.info("[Hotkeys] Triggered: {} → {}", comboStr, action);
            firedHotkeys.add("Hotkey " + comboStr + ": " + action);
            notifier.notify("Hotkey: " + comboStr);
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        int code = e.getKeyCode();
        if (code == NativeKeyEvent.VC_CONTROL) ctrlPressed = false;
        if (code == NativeKeyEvent.VC_ALT) altPressed = false;
        if (code == NativeKeyEvent.VC_SHIFT) shiftPressed = false;
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) { }

    // ─── AI-callable tools ───

    @Tool(description = "Register a global keyboard shortcut. The hotkey works even when Mins Bot " +
            "is not focused. Example combos: 'Ctrl+Alt+B', 'Ctrl+Shift+S'.")
    public String registerHotkey(
            @ToolParam(description = "Key combination, e.g. 'Ctrl+Alt+B', 'Ctrl+Shift+H'") String combo,
            @ToolParam(description = "Description of what this hotkey does") String action) {
        notifier.notify("Registering hotkey: " + combo);
        if (!enabled || !hookRegistered) {
            return "Global hotkeys are disabled. Set app.hotkeys.enabled=true and restart.";
        }
        hotkeys.put(combo.trim().toLowerCase(), action);
        log.info("[Hotkeys] Registered: {} → {}", combo, action);
        return "Hotkey registered: " + combo + " → " + action;
    }

    @Tool(description = "Remove a registered global hotkey.")
    public String removeHotkey(
            @ToolParam(description = "Key combination to remove") String combo) {
        String removed = hotkeys.remove(combo.trim().toLowerCase());
        if (removed != null) {
            return "Hotkey removed: " + combo;
        }
        return "No hotkey found for: " + combo;
    }

    @Tool(description = "List all registered global hotkeys.")
    public String listHotkeys() {
        notifier.notify("Listing hotkeys");
        if (hotkeys.isEmpty()) {
            return "No hotkeys registered." + (enabled ? "" : " (Hotkeys are disabled)");
        }
        StringBuilder sb = new StringBuilder("Registered hotkeys:\n");
        int i = 1;
        for (Map.Entry<String, String> entry : hotkeys.entrySet()) {
            sb.append(i++).append(". ").append(entry.getKey())
                    .append(" → ").append(entry.getValue()).append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Get any hotkey events that were triggered since the last check.")
    public String getTriggeredHotkeys() {
        if (firedHotkeys.isEmpty()) return "No hotkey events pending.";
        StringBuilder sb = new StringBuilder("Triggered hotkeys:\n");
        String event;
        int i = 1;
        while ((event = firedHotkeys.poll()) != null) {
            sb.append(i++).append(". ").append(event).append("\n");
        }
        return sb.toString().trim();
    }
}
