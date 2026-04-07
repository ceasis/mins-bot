package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Window manager tools: snap windows left/right/top/bottom, arrange side-by-side,
 * cascade, tile, resize, and multi-app launch with layout.
 * Uses PowerShell and Windows keyboard shortcuts for window management.
 */
@Component
public class WindowManagerTools {

    private static final Logger log = LoggerFactory.getLogger(WindowManagerTools.class);
    private final ToolExecutionNotifier notifier;
    private final SystemTools systemTools;

    public WindowManagerTools(ToolExecutionNotifier notifier, SystemTools systemTools) {
        this.notifier = notifier;
        this.systemTools = systemTools;
    }

    @Tool(description = "Snap the currently focused window to a position: left half, right half, "
            + "top-left quarter, top-right quarter, bottom-left quarter, bottom-right quarter, "
            + "maximize, or minimize. Uses Windows keyboard shortcuts (Win+Arrow). "
            + "Use when the user says 'snap this to the left', 'put this on the right side'.")
    public String snapWindow(
            @ToolParam(description = "Position: left, right, top-left, top-right, bottom-left, bottom-right, maximize, minimize") String position) {
        notifier.notify("Snapping window: " + position);
        try {
            java.awt.Robot robot = new java.awt.Robot();
            int winKey = java.awt.event.KeyEvent.VK_WINDOWS;

            switch (position.toLowerCase().trim()) {
                case "left" -> pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_LEFT);
                case "right" -> pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_RIGHT);
                case "maximize", "max", "full" -> pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_UP);
                case "minimize", "min" -> pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_DOWN);
                case "top-left", "topleft" -> {
                    pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_LEFT);
                    Thread.sleep(200);
                    pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_UP);
                }
                case "top-right", "topright" -> {
                    pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_RIGHT);
                    Thread.sleep(200);
                    pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_UP);
                }
                case "bottom-left", "bottomleft" -> {
                    pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_LEFT);
                    Thread.sleep(200);
                    pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_DOWN);
                }
                case "bottom-right", "bottomright" -> {
                    pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_RIGHT);
                    Thread.sleep(200);
                    pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_DOWN);
                }
                default -> { return "Unknown position: " + position + ". Use: left, right, top-left, top-right, bottom-left, bottom-right, maximize, minimize."; }
            }
            return "Window snapped to " + position + ".";
        } catch (Exception e) {
            return "Failed to snap window: " + e.getMessage();
        }
    }

    @Tool(description = "Open two apps side by side (split screen). "
            + "Opens the first app and snaps it left, then opens the second and snaps it right. "
            + "Use when the user says 'open Chrome and VS Code side by side', 'split screen with X and Y'.")
    public String openSideBySide(
            @ToolParam(description = "First app name (will be snapped left), e.g. 'chrome', 'notepad'") String leftApp,
            @ToolParam(description = "Second app name (will be snapped right), e.g. 'code', 'terminal'") String rightApp) {
        notifier.notify("Setting up split screen: " + leftApp + " | " + rightApp);
        try {
            // Open and snap left app
            systemTools.openApp(leftApp);
            Thread.sleep(1500);
            java.awt.Robot robot = new java.awt.Robot();
            int winKey = java.awt.event.KeyEvent.VK_WINDOWS;
            pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_LEFT);
            Thread.sleep(500);

            // Dismiss snap assist (press Escape)
            robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
            robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
            Thread.sleep(300);

            // Open and snap right app
            systemTools.openApp(rightApp);
            Thread.sleep(1500);
            pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_RIGHT);
            Thread.sleep(300);

            // Dismiss snap assist again
            robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
            robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);

            return "Split screen ready: " + leftApp + " (left) | " + rightApp + " (right)";
        } catch (Exception e) {
            return "Failed to set up split screen: " + e.getMessage();
        }
    }

    @Tool(description = "Open multiple apps and arrange them in a grid layout. "
            + "2 apps = side by side, 3 apps = one left + two stacked right, 4 apps = quadrants. "
            + "Use when the user says 'open Chrome, VS Code, and Terminal arranged nicely'.")
    public String openAndArrange(
            @ToolParam(description = "Comma-separated app names, e.g. 'chrome, code, terminal, notepad'") String apps) {
        String[] appList = apps.split(",");
        for (int i = 0; i < appList.length; i++) appList[i] = appList[i].trim();
        notifier.notify("Opening and arranging " + appList.length + " apps...");

        try {
            java.awt.Robot robot = new java.awt.Robot();
            int winKey = java.awt.event.KeyEvent.VK_WINDOWS;

            if (appList.length == 1) {
                systemTools.openApp(appList[0]);
                Thread.sleep(1000);
                pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_UP); // maximize
                return "Opened " + appList[0] + " (maximized).";
            }

            if (appList.length == 2) {
                return openSideBySide(appList[0], appList[1]);
            }

            if (appList.length == 3) {
                // Left half, top-right quarter, bottom-right quarter
                systemTools.openApp(appList[0]);
                Thread.sleep(1500);
                pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_LEFT);
                Thread.sleep(300);
                pressEscape(robot);

                systemTools.openApp(appList[1]);
                Thread.sleep(1500);
                pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_RIGHT);
                Thread.sleep(200);
                pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_UP);
                Thread.sleep(300);
                pressEscape(robot);

                systemTools.openApp(appList[2]);
                Thread.sleep(1500);
                pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_RIGHT);
                Thread.sleep(200);
                pressWinArrow(robot, winKey, java.awt.event.KeyEvent.VK_DOWN);
                pressEscape(robot);

                return "Arranged: " + appList[0] + " (left) | " + appList[1] + " (top-right) | " + appList[2] + " (bottom-right)";
            }

            // 4+ apps: quadrants
            String[][] positions = {{"left", "up"}, {"right", "up"}, {"left", "down"}, {"right", "down"}};
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < Math.min(appList.length, 4); i++) {
                systemTools.openApp(appList[i]);
                Thread.sleep(1500);
                int first = positions[i][0].equals("left") ? java.awt.event.KeyEvent.VK_LEFT : java.awt.event.KeyEvent.VK_RIGHT;
                int second = positions[i][1].equals("up") ? java.awt.event.KeyEvent.VK_UP : java.awt.event.KeyEvent.VK_DOWN;
                pressWinArrow(robot, winKey, first);
                Thread.sleep(200);
                pressWinArrow(robot, winKey, second);
                Thread.sleep(300);
                pressEscape(robot);
                result.append(appList[i]).append(" (").append(positions[i][0]).append("-").append(positions[i][1]).append("), ");
            }
            return "Arranged in quadrants: " + result.toString().replaceAll(", $", "");
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Move and resize a window by its title to exact pixel coordinates. "
            + "Use for precise window placement when snap isn't enough.")
    public String moveWindow(
            @ToolParam(description = "Window title (partial match)") String title,
            @ToolParam(description = "X position in pixels") double x,
            @ToolParam(description = "Y position in pixels") double y,
            @ToolParam(description = "Width in pixels") double width,
            @ToolParam(description = "Height in pixels") double height) {
        notifier.notify("Moving window: " + title);
        try {
            String ps = String.format(
                    "Add-Type @'\n"
                    + "using System; using System.Runtime.InteropServices;\n"
                    + "public class WinApi {\n"
                    + "  [DllImport(\"user32.dll\")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int h2, bool r);\n"
                    + "  [DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr h, int cmd);\n"
                    + "}\n"
                    + "'@\n"
                    + "$p = Get-Process | Where-Object { $_.MainWindowTitle -like '*%s*' -and $_.MainWindowHandle -ne 0 } | Select-Object -First 1\n"
                    + "if ($p) {\n"
                    + "  [WinApi]::ShowWindow($p.MainWindowHandle, 1)\n"
                    + "  [WinApi]::MoveWindow($p.MainWindowHandle, %d, %d, %d, %d, $true)\n"
                    + "  \"Moved '$($p.MainWindowTitle)' to (%d,%d) size %dx%d\"\n"
                    + "} else { 'Window not found: %s' }",
                    title, (int)x, (int)y, (int)width, (int)height,
                    (int)x, (int)y, (int)width, (int)height, title);

            return runPowerShell(ps);
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Cascade all open windows so they overlap with visible title bars. "
            + "Use when the user says 'cascade windows', 'organize my windows'.")
    public String cascadeWindows() {
        notifier.notify("Cascading windows...");
        try {
            // Use Windows shell COM to cascade
            return runPowerShell(
                    "(New-Object -ComObject Shell.Application).CascadeWindows(); 'Windows cascaded.'");
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Tile all open windows horizontally or vertically. "
            + "Use when the user says 'tile windows', 'show all windows side by side'.")
    public String tileWindows(
            @ToolParam(description = "Direction: horizontal (stacked top/bottom) or vertical (side by side)") String direction) {
        notifier.notify("Tiling windows " + direction + "...");
        try {
            String method = direction.toLowerCase().contains("horiz") ? "TileHorizontally" : "TileVertically";
            return runPowerShell(
                    "(New-Object -ComObject Shell.Application)." + method + "(); 'Windows tiled " + direction + ".'");
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Minimize all windows except one. Focuses the specified window and minimizes everything else. "
            + "Use when the user says 'just show Chrome', 'hide everything except VS Code'.")
    public String focusOnly(
            @ToolParam(description = "Window title or app name to keep visible") String appTitle) {
        notifier.notify("Focusing only: " + appTitle);
        try {
            // Minimize all first, then restore target
            String ps = "$target = Get-Process | Where-Object { $_.MainWindowTitle -like '*" + appTitle.replace("'", "''")
                    + "*' -and $_.MainWindowHandle -ne 0 } | Select-Object -First 1\n"
                    + "if ($target) {\n"
                    + "  (New-Object -ComObject Shell.Application).MinimizeAll()\n"
                    + "  Start-Sleep -Milliseconds 500\n"
                    + "  Add-Type '[DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr h, int c);' -Name WA -Namespace WA\n"
                    + "  [WA.WA]::ShowWindow($target.MainWindowHandle, 9)\n"
                    + "  \"Focused: $($target.MainWindowTitle)\"\n"
                    + "} else { 'Window not found: " + appTitle + "' }";
            return runPowerShell(ps);
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    // ═══ Helpers ═══

    private void pressWinArrow(java.awt.Robot robot, int winKey, int arrowKey) {
        robot.keyPress(winKey);
        robot.keyPress(arrowKey);
        robot.keyRelease(arrowKey);
        robot.keyRelease(winKey);
    }

    private void pressEscape(java.awt.Robot robot) throws InterruptedException {
        Thread.sleep(200);
        robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
        robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
    }

    private String runPowerShell(String script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", script);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        proc.waitFor(10, TimeUnit.SECONDS);
        return output.isBlank() ? "Done." : output;
    }
}
