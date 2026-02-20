package com.botsfer.agent.tools;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * System tray icon that shows Mins Bot's status (idle, chatting, autonomous mode active).
 * The tray icon provides a quick visual indicator and a right-click menu for basic actions.
 */
@Component
public class SystemTrayService {

    private static final Logger log = LoggerFactory.getLogger(SystemTrayService.class);

    @Value("${app.tray.enabled:true}")
    private boolean enabled;

    private final ToolExecutionNotifier notifier;

    private TrayIcon trayIcon;
    private volatile String currentStatus = "Idle";

    public SystemTrayService(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[Tray] System tray disabled (app.tray.enabled=false).");
            return;
        }
        if (!SystemTray.isSupported()) {
            log.warn("[Tray] System tray not supported on this platform.");
            return;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();
            Image icon = createIcon(Color.CYAN);

            PopupMenu popup = new PopupMenu("Mins Bot");

            MenuItem statusItem = new MenuItem("Status: " + currentStatus);
            statusItem.setEnabled(false);
            popup.add(statusItem);

            popup.addSeparator();

            MenuItem showItem = new MenuItem("Show Mins Bot");
            showItem.addActionListener(e -> {
                try {
                    // Open the Mins Bot UI in the default browser as a fallback
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(java.net.URI.create("http://localhost:"
                                + System.getProperty("server.port", "8765")));
                    }
                } catch (Exception ex) {
                    log.error("[Tray] Failed to show window: {}", ex.getMessage());
                }
            });
            popup.add(showItem);

            popup.addSeparator();

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> {
                log.info("[Tray] Exit requested from tray menu.");
                System.exit(0);
            });
            popup.add(exitItem);

            trayIcon = new TrayIcon(icon, "Mins Bot — " + currentStatus, popup);
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);

            log.info("[Tray] System tray icon added.");
        } catch (Exception e) {
            log.error("[Tray] Failed to create tray icon: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception ignored) {}
        }
    }

    /** Update the tray icon status text and color. */
    public void updateStatus(String status) {
        this.currentStatus = status;
        if (trayIcon != null) {
            trayIcon.setToolTip("Mins Bot — " + status);
            // Change icon color based on status
            Color color = switch (status.toLowerCase()) {
                case "autonomous" -> Color.GREEN;
                case "chatting" -> Color.YELLOW;
                case "error" -> Color.RED;
                default -> Color.CYAN;
            };
            trayIcon.setImage(createIcon(color));
        }
    }

    /** Show a system tray notification balloon. */
    public void showNotification(String title, String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        }
    }

    /** Create a simple colored circle icon. */
    private Image createIcon(Color color) {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.fillOval(1, 1, size - 2, size - 2);
        g.setColor(color.darker());
        g.drawOval(1, 1, size - 2, size - 2);
        g.dispose();
        return img;
    }

    // ─── AI-callable tools ───

    @Tool(description = "Get the current Mins Bot tray icon status.")
    public String getTrayStatus() {
        notifier.notify("Checking tray status");
        return "Tray status: " + currentStatus
                + (trayIcon != null ? " (tray icon active)" : " (tray icon not active)");
    }

    @Tool(description = "Show a desktop notification via the system tray icon.")
    public String showTrayNotification(
            @org.springframework.ai.tool.annotation.ToolParam(description = "Notification title") String title,
            @org.springframework.ai.tool.annotation.ToolParam(description = "Notification message") String message) {
        notifier.notify("Showing notification: " + title);
        if (trayIcon == null) {
            return "System tray not available. Enable with app.tray.enabled=true.";
        }
        showNotification(title, message);
        return "Notification shown: " + title + " — " + message;
    }
}
