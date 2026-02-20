package com.botsfer.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * System notifications (tray balloon / toast).
 * Lets the bot notify the user with a desktop popup (e.g. "Remind me in 10 min" → show toast when done).
 */
@Component
public class NotificationTools {

    private static TrayIcon trayIcon;
    private static final Object LOCK = new Object();

    private final ToolExecutionNotifier notifier;

    public NotificationTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Show a system notification (desktop popup / toast). Use when the user asks to be notified, " +
            "reminded, or when a long task finishes and they should be alerted.")
    public String showNotification(
            @ToolParam(description = "Short title for the notification") String title,
            @ToolParam(description = "Message body (one or two lines work best)") String message) {
        if (message == null) message = "";
        if (title == null) title = "Mins Bot";
        notifier.notify("Showing notification: " + title);
        try {
            ensureTrayIcon();
            if (trayIcon == null) {
                return "System tray is not supported on this system. Cannot show notification.";
            }
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
            return "Notification shown: \"" + title + "\" — " + message;
        } catch (Exception e) {
            return "Could not show notification: " + e.getMessage();
        }
    }

    private static void ensureTrayIcon() {
        if (trayIcon != null) return;
        synchronized (LOCK) {
            if (trayIcon != null) return;
            if (!SystemTray.isSupported()) return;
            try {
                BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = icon.createGraphics();
                g.setColor(new Color(60, 120, 200));
                g.fillOval(2, 2, 12, 12);
                g.dispose();
                trayIcon = new TrayIcon(icon, "Mins Bot");
                SystemTray.getSystemTray().add(trayIcon);
            } catch (Exception ignored) {
                // leave trayIcon null
            }
        }
    }
}
