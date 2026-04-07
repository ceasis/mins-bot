package com.minsbot.agent.tools;

import com.minsbot.FloatingAppLauncher;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tools to control the Mins Bot window itself: move, resize, minimize, restore.
 * Allows the user to say "move yourself to the left", "minimize yourself", etc.
 */
@Component
public class BotWindowTools {

    private final ToolExecutionNotifier notifier;

    public BotWindowTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Move the Mins Bot window to a position on screen. "
            + "Use when the user says 'move yourself to the left', 'go to the right side', "
            + "'move to the top', 'move to bottom-right corner', 'get out of the way', "
            + "'move yourself to the center', 'move to coordinates 100,200'.")
    public String moveBotWindow(
            @ToolParam(description = "Position: 'left', 'right', 'top-left', 'top-right', "
                    + "'bottom-left', 'bottom-right', 'center', or exact 'x,y' coordinates") String position) {
        notifier.notify("Moving bot window: " + position);
        try {
            int[] screen = FloatingAppLauncher.getScreenSize();
            int[] bounds = FloatingAppLauncher.getWindowBounds();
            if (screen == null || bounds == null) return "Could not get screen/window info.";

            int sw = screen[0], sh = screen[1];
            int ww = bounds[2], wh = bounds[3];
            int margin = 10;

            double x, y;
            switch (position.toLowerCase().trim()) {
                case "left" -> { x = margin; y = (sh - wh) / 2.0; }
                case "right" -> { x = sw - ww - margin; y = (sh - wh) / 2.0; }
                case "top", "top-center" -> { x = (sw - ww) / 2.0; y = margin; }
                case "bottom", "bottom-center" -> { x = (sw - ww) / 2.0; y = sh - wh - margin - 40; }
                case "top-left" -> { x = margin; y = margin; }
                case "top-right" -> { x = sw - ww - margin; y = margin; }
                case "bottom-left" -> { x = margin; y = sh - wh - margin - 40; }
                case "bottom-right" -> { x = sw - ww - margin; y = sh - wh - margin - 40; }
                case "center" -> { x = (sw - ww) / 2.0; y = (sh - wh) / 2.0; }
                default -> {
                    // Try parsing as "x,y" coordinates
                    try {
                        String[] parts = position.split("[,\\s]+");
                        x = Double.parseDouble(parts[0].trim());
                        y = Double.parseDouble(parts[1].trim());
                    } catch (Exception e) {
                        return "Unknown position: " + position + ". Use: left, right, top-left, top-right, "
                                + "bottom-left, bottom-right, center, top, bottom, or x,y coordinates.";
                    }
                }
            }

            FloatingAppLauncher.moveTo(x, y);
            return "Moved to " + position + " (" + (int) x + ", " + (int) y + ")";
        } catch (Exception e) {
            return "Failed to move: " + e.getMessage();
        }
    }

    @Tool(description = "Minimize the Mins Bot window. "
            + "Use when the user says 'minimize yourself', 'hide yourself', 'get out of the way', "
            + "'minimize the bot', 'go away for now'.")
    public String minimizeBotWindow() {
        notifier.notify("Minimizing bot window...");
        FloatingAppLauncher.minimize();
        return "Bot window minimized.";
    }

    @Tool(description = "Restore the Mins Bot window from minimized state. "
            + "Use when the user says 'come back', 'show yourself', 'restore', 'I need you back'.")
    public String restoreBotWindow() {
        notifier.notify("Restoring bot window...");
        FloatingAppLauncher.restore();
        return "Bot window restored.";
    }

    @Tool(description = "Resize the Mins Bot window. "
            + "Use when the user says 'make yourself bigger', 'make yourself smaller', "
            + "'resize to 400x600', 'make the bot window wider'.")
    public String resizeBotWindow(
            @ToolParam(description = "Size: 'small' (300x400), 'medium' (380x520), 'large' (500x700), "
                    + "'wide' (600x520), or exact 'widthxheight' like '450x600'") String size) {
        notifier.notify("Resizing bot window: " + size);
        try {
            double w, h;
            switch (size.toLowerCase().trim()) {
                case "small", "compact" -> { w = 300; h = 400; }
                case "medium", "default", "normal" -> { w = 380; h = 520; }
                case "large", "big" -> { w = 500; h = 700; }
                case "wide" -> { w = 600; h = 520; }
                case "tall" -> { w = 380; h = 800; }
                default -> {
                    try {
                        String[] parts = size.toLowerCase().split("[x,\\s]+");
                        w = Double.parseDouble(parts[0].trim());
                        h = Double.parseDouble(parts[1].trim());
                    } catch (Exception e) {
                        return "Unknown size: " + size + ". Use: small, medium, large, wide, tall, or WIDTHxHEIGHT.";
                    }
                }
            }
            FloatingAppLauncher.resizeTo(w, h);
            return "Resized to " + (int) w + "x" + (int) h;
        } catch (Exception e) {
            return "Failed to resize: " + e.getMessage();
        }
    }

    @Tool(description = "Get the current position and size of the Mins Bot window on screen.")
    public String getBotWindowInfo() {
        int[] bounds = FloatingAppLauncher.getWindowBounds();
        int[] screen = FloatingAppLauncher.getScreenSize();
        if (bounds == null) return "Could not get window info.";
        return "Bot window: position (" + bounds[0] + "," + bounds[1] + "), "
                + "size " + bounds[2] + "x" + bounds[3]
                + (screen != null ? ", screen " + screen[0] + "x" + screen[1] : "");
    }
}
