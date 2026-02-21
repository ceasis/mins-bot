package com.minsbot.agent.tools;

import com.minsbot.MinsBotQuitService;
import com.minsbot.agent.SystemControlService;
import com.sun.management.OperatingSystemMXBean;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Component
public class SystemTools {

    private final SystemControlService systemControl;
    private final ToolExecutionNotifier notifier;
    private final MinsBotQuitService quitService;

    public SystemTools(SystemControlService systemControl, ToolExecutionNotifier notifier,
                       MinsBotQuitService quitService) {
        this.systemControl = systemControl;
        this.notifier = notifier;
        this.quitService = quitService;
    }

    @Tool(description = "Quit the Mins Bot application. Only call this when the user has explicitly confirmed they want to quit (e.g. replied 'yes' or 'y' to 'Quit Mins Bot?'). Do NOT call when the user just says 'quit' — in that case only reply with 'Quit Mins Bot?' and wait for their answer.")
    public String quitMinsBot() {
        notifier.notify("Quitting Mins Bot...");
        quitService.requestQuit();
        return "Quitting Mins Bot.";
    }

    @Tool(description = "Close all running user application windows on the PC, except system processes and Mins Bot itself")
    public String closeAllWindows() {
        notifier.notify("Closing all windows...");
        return systemControl.closeAllWindows();
    }

    @Tool(description = "Close a specific running application by its common name")
    public String closeApp(
            @ToolParam(description = "Name of the application, e.g. 'chrome', 'notepad', 'spotify'") String appName) {
        notifier.notify("Closing " + appName + "...");
        return systemControl.closeApp(appName);
    }

    @Tool(description = "Launch or open an application by name")
    public String openApp(
            @ToolParam(description = "Name of the application to open, e.g. 'chrome', 'calculator', 'terminal'") String appName) {
        notifier.notify("Opening " + appName + "...");
        return systemControl.openApp(appName);
    }

    @Tool(description = "Minimize all windows and show the desktop")
    public String minimizeAll() {
        notifier.notify("Minimizing all windows...");
        return systemControl.minimizeAll();
    }

    @Tool(description = "Lock the computer screen")
    public String lockScreen() {
        notifier.notify("Locking screen...");
        return systemControl.lockScreen();
    }

    @Tool(description = "Take a screenshot of the entire screen right now and save it")
    public String takeScreenshot() {
        notifier.notify("Taking screenshot...");
        return systemControl.takeScreenshot();
    }

    @Tool(description = "List all currently running user applications and their process counts")
    public String listRunningApps() {
        notifier.notify("Listing running apps...");
        return systemControl.listRunningApps();
    }

    @Tool(description = "List open windows with their titles (process name, PID, window title). Use to see what windows are open before focusing one.")
    public String listOpenWindows() {
        notifier.notify("Listing open windows...");
        return systemControl.listOpenWindows();
    }

    @Tool(description = "Bring a window to the front by its title or process name (partial match). Use after listOpenWindows to focus a specific app.")
    public String focusWindow(
            @ToolParam(description = "Part of the window title or process name, e.g. 'notepad', 'chrome', 'Excel'") String titleOrProcess) {
        notifier.notify("Focusing window...");
        return systemControl.focusWindow(titleOrProcess);
    }

    @Tool(description = "Send keystrokes to the currently focused window. Use for shortcuts: ^v = Ctrl+V (paste), ^c = Ctrl+C (copy), %{F4} = Alt+F4. Type text directly; use {ENTER}, {TAB}, {ESC} for special keys.")
    public String sendKeys(
            @ToolParam(description = "Keystrokes to send: + = Shift, ^ = Ctrl, % = Alt, {ENTER}, {TAB}, {ESC}, {DOWN}, {UP}, or plain text") String keys) {
        notifier.notify("Sending keystrokes...");
        return systemControl.sendKeys(keys);
    }

    @Tool(description = "Open an application with optional arguments: a file path for Notepad, a URL for browser, or a folder path for Explorer.")
    public String openAppWithArgs(
            @ToolParam(description = "App name: notepad, chrome, edge, firefox, explorer, etc.") String appName,
            @ToolParam(description = "Optional: file path, URL, or folder path") String args) {
        notifier.notify("Opening app with args...");
        return systemControl.openAppWithArgs(appName, args);
    }

    @Tool(description = "Execute a PowerShell command and return its output. Use for system queries like disk space, RAM, installed programs, battery status, etc.")
    public String runPowerShell(
            @ToolParam(description = "The PowerShell command to execute") String command) {
        notifier.notify("Running PowerShell: " + command);
        return systemControl.runPowerShell(command);
    }

    @Tool(description = "Execute a CMD command and return its output. Use for commands like ipconfig, ping, dir, systeminfo, netstat, etc.")
    public String runCmd(
            @ToolParam(description = "The CMD command to execute") String command) {
        notifier.notify("Running CMD: " + command);
        return systemControl.runCmd(command);
    }

    @Tool(description = "Get system hardware and OS information: RAM (total, free, used), CPU (name, cores, usage), OS, computer name, uptime, Java version, and disk space for all drives")
    public String getSystemInfo() {
        notifier.notify("Gathering system info...");
        StringBuilder sb = new StringBuilder();
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            Runtime rt = Runtime.getRuntime();

            // OS
            sb.append("OS: ").append(System.getProperty("os.name"))
              .append(" ").append(System.getProperty("os.version"))
              .append(" (").append(System.getProperty("os.arch")).append(")\n");

            // Computer & user
            try {
                sb.append("Computer: ").append(InetAddress.getLocalHost().getHostName()).append("\n");
            } catch (Exception e) {
                sb.append("Computer: unknown\n");
            }
            sb.append("User: ").append(System.getProperty("user.name")).append("\n");

            // CPU
            sb.append("CPU: ").append(os.getAvailableProcessors()).append(" logical cores\n");
            double cpuLoad = os.getCpuLoad();
            if (cpuLoad >= 0) {
                sb.append("CPU usage: ").append(String.format("%.1f%%", cpuLoad * 100)).append("\n");
            }

            // RAM
            long totalRam = os.getTotalMemorySize();
            long freeRam = os.getFreeMemorySize();
            long usedRam = totalRam - freeRam;
            sb.append("RAM total: ").append(fmt(totalRam)).append("\n");
            sb.append("RAM used: ").append(fmt(usedRam)).append("\n");
            sb.append("RAM free: ").append(fmt(freeRam)).append("\n");
            sb.append("RAM usage: ").append(String.format("%.1f%%", usedRam * 100.0 / totalRam)).append("\n");

            // JVM memory
            sb.append("JVM heap max: ").append(fmt(rt.maxMemory())).append("\n");
            sb.append("JVM heap used: ").append(fmt(rt.totalMemory() - rt.freeMemory())).append("\n");

            // Uptime
            long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
            long uptimeSec = uptimeMs / 1000;
            long hours = uptimeSec / 3600;
            long mins = (uptimeSec % 3600) / 60;
            sb.append("JVM uptime: ").append(hours).append("h ").append(mins).append("m\n");

            // Java version
            sb.append("Java: ").append(System.getProperty("java.version"))
              .append(" (").append(System.getProperty("java.vendor")).append(")\n");

            // Disks
            sb.append("\nDrives:\n");
            for (Path root : FileSystems.getDefault().getRootDirectories()) {
                try {
                    FileStore store = Files.getFileStore(root);
                    long total = store.getTotalSpace();
                    long free = store.getUsableSpace();
                    long used = total - free;
                    int pct = total > 0 ? (int) (used * 100 / total) : 0;
                    sb.append(String.format("  %s  %s total, %s free, %s used (%d%%)\n",
                            root, fmt(total), fmt(free), fmt(used), pct));
                } catch (Exception e) {
                    sb.append("  ").append(root).append("  (not accessible)\n");
                }
            }
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage());
        }
        return sb.toString();
    }

    @Tool(description = "Get current date and time and time zone. Use when the user asks what time or date it is.")
    public String getCurrentDateTime() {
        notifier.notify("Getting date/time...");
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now();
        String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return "Current date/time: " + formatted + " (" + zone.getId() + ")";
    }

    @Tool(description = "Get the value of an environment variable (e.g. USERPROFILE, PATH, APPDATA).")
    public String getEnvVar(
            @ToolParam(description = "Name of the environment variable") String name) {
        notifier.notify("Getting env var...");
        String value = System.getenv(name);
        return value != null ? value : "(not set)";
    }

    @Tool(description = "List all environment variable names (values not shown). Use to see what env vars exist.")
    public String listEnvVars() {
        notifier.notify("Listing env vars...");
        Map<String, String> env = System.getenv();
        List<String> names = new ArrayList<>(new TreeSet<>(env.keySet()));
        return "Environment variables: " + String.join(", ", names);
    }

    @Tool(description = "Mute system volume (toggle). Send the mute key once.")
    public String mute() {
        notifier.notify("Muting...");
        return systemControl.runPowerShell("$wshell = New-Object -ComObject WScript.Shell; $wshell.SendKeys([char]173)");
    }

    @Tool(description = "Unmute system volume (toggle). Send the mute key once.")
    public String unmute() {
        notifier.notify("Unmuting...");
        return systemControl.runPowerShell("$wshell = New-Object -ComObject WScript.Shell; $wshell.SendKeys([char]173)");
    }

    @Tool(description = "Put the computer to sleep (suspend).")
    public String sleep() {
        notifier.notify("Putting PC to sleep...");
        return systemControl.runCmd("rundll32.exe powrprof.dll,SetSuspendState 0,1,0");
    }

    @Tool(description = "Hibernate the computer.")
    public String hibernate() {
        notifier.notify("Hibernating...");
        return systemControl.runCmd("shutdown /h");
    }

    @Tool(description = "Shut down the computer. Optionally delay in minutes (0 = immediately).")
    public String shutdown(
            @ToolParam(description = "Minutes to wait before shutting down (0 for immediately)") double delayMinutesRaw) {
        notifier.notify("Shutting down...");
        int delayMinutes = (int) Math.round(delayMinutesRaw);
        int seconds = Math.max(0, delayMinutes) * 60;
        return systemControl.runCmd("shutdown /s /t " + seconds);
    }

    @Tool(description = "Ping a host to check if it is reachable. Returns round-trip time or error.")
    public String ping(
            @ToolParam(description = "Hostname or IP to ping (e.g. google.com, 8.8.8.8)") String host) {
        notifier.notify("Pinging " + host + "...");
        return systemControl.runCmd("ping -n 3 " + host.replace(" ", ""));
    }

    @Tool(description = "Get the local machine's IP address(es).")
    public String getLocalIpAddress() {
        notifier.notify("Getting local IP...");
        try {
            InetAddress local = InetAddress.getLocalHost();
            return "Local host: " + local.getHostName() + " / " + local.getHostAddress();
        } catch (Exception e) {
            String out = systemControl.runCmd("ipconfig");
            return out != null && out.length() > 500 ? out.substring(0, 500) + "..." : out;
        }
    }

    @Tool(description = "Open the folder where screenshots are saved (mins_bot_data/screenshots).")
    public String openScreenshotsFolder() {
        notifier.notify("Opening screenshots folder...");
        try {
            Path dir = Paths.get(System.getProperty("user.home"), "mins_bot_data", "screenshots");
            if (!Files.isDirectory(dir)) return "Screenshots folder does not exist yet: " + dir;
            Desktop.getDesktop().open(dir.toFile());
            return "Opened: " + dir.toAbsolutePath();
        } catch (Exception e) {
            return "Failed to open folder: " + e.getMessage();
        }
    }

    @Tool(description = "List the most recent screenshot files (name and date).")
    public String listRecentScreenshots(
            @ToolParam(description = "Maximum number of screenshots to list (e.g. 10)") double maxCountRaw) {
        int maxCount = (int) Math.round(maxCountRaw);
        notifier.notify("Listing recent screenshots...");
        try {
            Path dir = Paths.get(System.getProperty("user.home"), "mins_bot_data", "screenshots");
            if (!Files.isDirectory(dir)) return "Screenshots folder does not exist yet.";
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.png")) {
                for (Path p : stream) {
                    if (Files.isRegularFile(p)) files.add(p);
                }
            }
            files.sort(Comparator.<Path, FileTime>comparing((Path p) -> {
                try { return Files.getLastModifiedTime(p); } catch (Exception e) { return FileTime.fromMillis(0); }
            }).reversed());
            int n = Math.min(Math.max(1, maxCount), files.size());
            StringBuilder sb = new StringBuilder("Recent screenshots (newest first):\n");
            for (int i = 0; i < n; i++) {
                Path p = files.get(i);
                try {
                    FileTime mod = Files.getLastModifiedTime(p);
                    sb.append("  ").append(p.getFileName()).append("  ").append(mod.toString()).append("\n");
                } catch (Exception e) {
                    sb.append("  ").append(p.getFileName()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to list screenshots: " + e.getMessage();
        }
    }

    @Tool(description = "Get recently opened files from Windows (shell recent items). Returns paths so the user can open one.")
    public String getRecentFiles(
            @ToolParam(description = "Maximum number of recent files to return (e.g. 15)") double maxCountRaw) {
        int maxCount = (int) Math.round(maxCountRaw);
        notifier.notify("Getting recent files...");
        int n = Math.min(25, Math.max(1, maxCount));
        String ps = "Get-ChildItem -Path $env:APPDATA\\Microsoft\\Windows\\Recent -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First " + n + " | ForEach-Object { $_.Name + ' | ' + $_.LastWriteTime }";
        return systemControl.runPowerShell(ps);
    }

    @Tool(description = "Open a URL in the user's default PC browser (Chrome, Edge, Firefox, etc.). Use this when the user wants to open a website or URL normally. Do NOT use this if the user explicitly says 'in-browser' or 'chat browser' — those go to the built-in Mins Bot browser tab instead.")
    public String openUrl(
            @ToolParam(description = "The URL to open, e.g. 'https://www.youtube.com' or 'https://google.com'") String url) {
        notifier.notify("Opening in PC browser: " + url);
        return systemControl.openUrl(url);
    }

    @Tool(description = "Change the desktop wallpaper to an image file. Supports JPG, PNG, and BMP. "
            + "Provide the full path to an image file on this PC.")
    public String setWallpaper(
            @ToolParam(description = "Full path to the image file, e.g. C:\\Users\\me\\Pictures\\wall.jpg") String imagePath) {
        notifier.notify("Changing wallpaper...");
        Path p = Paths.get(imagePath).toAbsolutePath();
        if (!Files.exists(p)) return "Image not found: " + p;
        if (Files.isDirectory(p)) return "Path is a directory, not an image: " + p;

        String absPath = p.toString().replace("'", "''");
        // Use SystemParametersInfo via Add-Type to set wallpaper natively
        String ps = """
                Add-Type -TypeDefinition @"
                using System;
                using System.Runtime.InteropServices;
                public class Wallpaper {
                    [DllImport("user32.dll", CharSet = CharSet.Auto)]
                    public static extern int SystemParametersInfo(int uAction, int uParam, string lpvParam, int fuWinIni);
                    public const int SPI_SETDESKWALLPAPER = 0x0014;
                    public const int SPIF_UPDATEINIFILE = 0x01;
                    public const int SPIF_SENDWININICHANGE = 0x02;
                    public static void Set(string path) {
                        SystemParametersInfo(SPI_SETDESKWALLPAPER, 0, path, SPIF_UPDATEINIFILE | SPIF_SENDWININICHANGE);
                    }
                }
                "@
                [Wallpaper]::Set('%s')
                """.formatted(absPath);
        String result = systemControl.runPowerShell(ps);
        if (result != null && result.toLowerCase().contains("error")) {
            return "Failed to set wallpaper: " + result;
        }
        return "Wallpaper changed to: " + p;
    }

    // ═══ Mouse control tools ═══

    @Tool(description = "Click the mouse at screen coordinates. Use for clicking buttons, links, or any on-screen element. "
            + "Take a screenshot first to see the screen and determine the correct coordinates.")
    public String mouseClick(
            @ToolParam(description = "X coordinate (pixels from left edge of screen)") int x,
            @ToolParam(description = "Y coordinate (pixels from top edge of screen)") int y,
            @ToolParam(description = "Mouse button: 'left' (default), 'right', or 'middle'") String button) {
        notifier.notify("Clicking at (" + x + ", " + y + ")");
        return systemControl.mouseClick(x, y, button);
    }

    @Tool(description = "Double-click the mouse at screen coordinates.")
    public String mouseDoubleClick(
            @ToolParam(description = "X coordinate") int x,
            @ToolParam(description = "Y coordinate") int y) {
        notifier.notify("Double-clicking at (" + x + ", " + y + ")");
        return systemControl.mouseDoubleClick(x, y);
    }

    @Tool(description = "Move the mouse cursor to screen coordinates without clicking.")
    public String mouseMove(
            @ToolParam(description = "X coordinate") int x,
            @ToolParam(description = "Y coordinate") int y) {
        return systemControl.mouseMove(x, y);
    }

    @Tool(description = "Drag the mouse from one point to another (left button held). Useful for drag-and-drop, "
            + "selecting text, resizing windows, or moving items.")
    public String mouseDrag(
            @ToolParam(description = "Starting X coordinate") int fromX,
            @ToolParam(description = "Starting Y coordinate") int fromY,
            @ToolParam(description = "Ending X coordinate") int toX,
            @ToolParam(description = "Ending Y coordinate") int toY) {
        notifier.notify("Dragging from (" + fromX + "," + fromY + ") to (" + toX + "," + toY + ")");
        return systemControl.mouseDrag(fromX, fromY, toX, toY);
    }

    @Tool(description = "Scroll the mouse wheel. Positive = scroll down, negative = scroll up. "
            + "Each notch is about 3 lines of text.")
    public String mouseScroll(
            @ToolParam(description = "Number of notches to scroll. Positive = down, negative = up. e.g. 3 or -5") int amount) {
        return systemControl.mouseScroll(amount);
    }

    @Tool(description = "Get the current mouse cursor position on screen.")
    public String getMousePosition() {
        return systemControl.getMousePosition();
    }

    @Tool(description = "Get the screen resolution (width x height in pixels).")
    public String getScreenSize() {
        return systemControl.getScreenSize();
    }

    // ═══ PC browser control tools ═══

    @Tool(description = "Navigate the user's PC browser to a URL. Focuses the browser window, "
            + "clicks the address bar (Ctrl+L), types the URL, and presses Enter. "
            + "Use this for browser navigation — NOT Playwright/headless.")
    public String browserNavigate(
            @ToolParam(description = "Browser name to focus, e.g. 'chrome', 'edge', 'firefox'. Defaults to 'chrome'.") String browserName,
            @ToolParam(description = "The URL to navigate to, e.g. 'youtube.com' or 'https://google.com'") String url) {
        notifier.notify("Navigating browser to: " + url);
        return systemControl.browserNavigate(browserName, url);
    }

    @Tool(description = "Open a new empty tab in the user's PC browser (Ctrl+T).")
    public String browserNewTab(
            @ToolParam(description = "Browser name: 'chrome', 'edge', 'firefox'. Defaults to 'chrome'.") String browserName) {
        notifier.notify("Opening new browser tab");
        return systemControl.browserNewTab(browserName);
    }

    @Tool(description = "Close the current tab in the user's PC browser (Ctrl+W).")
    public String browserCloseTab(
            @ToolParam(description = "Browser name: 'chrome', 'edge', 'firefox'. Defaults to 'chrome'.") String browserName) {
        notifier.notify("Closing browser tab");
        return systemControl.browserCloseTab(browserName);
    }

    @Tool(description = "Switch to the next or previous tab in the user's PC browser. "
            + "Use 'next' to go right (Ctrl+Tab) or 'previous' to go left (Ctrl+Shift+Tab).")
    public String browserSwitchTab(
            @ToolParam(description = "Browser name: 'chrome', 'edge', 'firefox'. Defaults to 'chrome'.") String browserName,
            @ToolParam(description = "'next' (go right) or 'previous'/'prev' (go left)") String direction) {
        notifier.notify("Switching browser tab: " + direction);
        return systemControl.browserSwitchTab(browserName, direction);
    }

    @Tool(description = "Refresh the current page in the user's PC browser (F5).")
    public String browserRefresh(
            @ToolParam(description = "Browser name: 'chrome', 'edge', 'firefox'. Defaults to 'chrome'.") String browserName) {
        return systemControl.browserRefresh(browserName);
    }

    @Tool(description = "Go back one page in the user's PC browser (Alt+Left).")
    public String browserBack(
            @ToolParam(description = "Browser name: 'chrome', 'edge', 'firefox'. Defaults to 'chrome'.") String browserName) {
        return systemControl.browserBack(browserName);
    }

    @Tool(description = "Go forward one page in the user's PC browser (Alt+Right).")
    public String browserForward(
            @ToolParam(description = "Browser name: 'chrome', 'edge', 'firefox'. Defaults to 'chrome'.") String browserName) {
        return systemControl.browserForward(browserName);
    }

    // ═══ Helpers ═══

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }
}
