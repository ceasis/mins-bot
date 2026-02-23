package com.minsbot.agent.tools;

import com.minsbot.MinsBotQuitService;
import com.minsbot.agent.ScreenMemoryService;
import com.minsbot.agent.SystemControlService;
import com.minsbot.agent.TextractService;
import com.minsbot.agent.VisionService;
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
    private final VisionService visionService;
    private final ScreenMemoryService screenMemoryService;
    private final TextractService textractService;

    public SystemTools(SystemControlService systemControl, ToolExecutionNotifier notifier,
                       MinsBotQuitService quitService, VisionService visionService,
                       ScreenMemoryService screenMemoryService, TextractService textractService) {
        this.systemControl = systemControl;
        this.notifier = notifier;
        this.quitService = quitService;
        this.visionService = visionService;
        this.screenMemoryService = screenMemoryService;
        this.textractService = textractService;
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

    private static final java.util.regex.Pattern DESKTOP_FILE_OP_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(Move-Item|Copy-Item|Remove-Item|Test-Path|Get-ChildItem|Get-Item|ls\\s|dir\\s|mv\\s|cp\\s|move\\s|del\\s|xcopy|robocopy)" +
            ".*(Desktop|\\\\Desktop)");

    @Tool(description = "Execute a PowerShell command and return its output. Use for system queries like disk space, RAM, installed programs, battery status, etc. "
            + "NEVER use this to move, copy, or delete files that are VISIBLE on the user's screen or desktop — "
            + "use findAndDragElement for visual drag/move operations instead.")
    public String runPowerShell(
            @ToolParam(description = "The PowerShell command to execute") String command) {
        if (DESKTOP_FILE_OP_PATTERN.matcher(command).find()) {
            return "BLOCKED: Do not use PowerShell to move/copy/delete Desktop files. "
                    + "Use findAndDragElement(source, target) instead to visually drag items on screen.";
        }
        notifier.notify("Running PowerShell: " + command);
        return systemControl.runPowerShell(command);
    }

    @Tool(description = "Execute a CMD command and return its output. Use for commands like ipconfig, ping, dir, systeminfo, netstat, etc. "
            + "NEVER use this to move, copy, or delete files that are VISIBLE on the user's screen or desktop — "
            + "use findAndDragElement for visual drag/move operations instead.")
    public String runCmd(
            @ToolParam(description = "The CMD command to execute") String command) {
        if (DESKTOP_FILE_OP_PATTERN.matcher(command).find()) {
            return "BLOCKED: Do not use CMD to move/copy/delete Desktop files. "
                    + "Use findAndDragElement(source, target) instead to visually drag items on screen.";
        }
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

    // ═══ Element location: shared logic ═══

    /** Result of locating an element on screen. */
    private record ElementLocation(int screenX, int screenY, String method, int imgWidth, int imgHeight,
                                   int logicalWidth, int logicalHeight, double scaleX, double scaleY) {
        String summary(String desc) {
            return "Found '" + desc + "' via " + method + " at screen(" + screenX + "," + screenY + ")"
                    + " [img=" + imgWidth + "x" + imgHeight + ", screen=" + logicalWidth + "x" + logicalHeight
                    + ", scale=" + String.format("%.4f", scaleX) + "x" + String.format("%.4f", scaleY) + "]";
        }
    }

    /** Holds screenshot context so multiple elements can be found on the same image. */
    private record ScreenshotContext(Path imagePath, int imgWidth, int imgHeight,
                                     int logicalWidth, int logicalHeight, double scaleX, double scaleY) {}

    private static final Path SCREENSHOTS_BASE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "screenshots");

    /** Convert an absolute screenshot path to a chat-embeddable URL marker. */
    private String screenshotUrl(Path imagePath) {
        try {
            String relative = SCREENSHOTS_BASE.relativize(imagePath).toString().replace('\\', '/');
            return "[img:/api/screenshot?file=" + relative + "]";
        } catch (Exception e) {
            return "";
        }
    }

    /** Take a screenshot and compute DPI scaling info. */
    private ScreenshotContext captureScreen() {
        java.awt.Dimension screenSize = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        System.out.println("[locate] Screen logical size: " + screenSize.width + "x" + screenSize.height);

        try {
            java.awt.GraphicsDevice gd = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            java.awt.geom.AffineTransform tx = gd.getDefaultConfiguration().getDefaultTransform();
            System.out.println("[locate] DPI scale: " + tx.getScaleX() + "x" + tx.getScaleY());
        } catch (Exception ignored) {}

        String screenshotResult = systemControl.takeScreenshot();
        if (screenshotResult.startsWith("Screenshot failed")) return null;

        String pathStr = screenshotResult.replace("Screenshot saved: ", "").trim();
        Path imagePath = Paths.get(pathStr);
        if (!Files.exists(imagePath)) return null;

        int imgW = 0, imgH = 0;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(imagePath.toFile());
            if (img != null) { imgW = img.getWidth(); imgH = img.getHeight(); }
        } catch (Exception e) {
            System.out.println("[locate] Could not read image: " + e.getMessage());
        }

        double scaleX = (imgW > 0 && imgW != screenSize.width) ? (double) screenSize.width / imgW : 1.0;
        double scaleY = (imgH > 0 && imgH != screenSize.height) ? (double) screenSize.height / imgH : 1.0;
        System.out.println("[locate] Image: " + imgW + "x" + imgH + ", scale: " + String.format("%.4f", scaleX) + "x" + String.format("%.4f", scaleY));

        return new ScreenshotContext(imagePath, imgW, imgH, screenSize.width, screenSize.height, scaleX, scaleY);
    }

    /** Find an element on a pre-captured screenshot. */
    private ElementLocation locateOnImage(ScreenshotContext ctx, String elementDescription) {
        String searchText = extractSearchText(elementDescription);
        System.out.println("[locate] Search: '" + searchText + "' from '" + elementDescription + "'");

        // Try OCR first (full text)
        double[] ocrCoords = screenMemoryService.findTextOnScreen(ctx.imagePath(), searchText);
        if (ocrCoords != null) {
            int sx = (int) Math.round(ocrCoords[0] * ctx.scaleX());
            int sy = (int) Math.round(ocrCoords[1] * ctx.scaleY());
            System.out.println("[locate] OCR MATCH: '" + searchText + "' → screen(" + sx + "," + sy + ")");
            return new ElementLocation(sx, sy, "OCR", ctx.imgWidth(), ctx.imgHeight(),
                    ctx.logicalWidth(), ctx.logicalHeight(), ctx.scaleX(), ctx.scaleY());
        }

        // OCR fallback: try filename stem without extension (e.g. "file1.txt" → "file1")
        if (searchText.contains(".")) {
            String stem = searchText.substring(0, searchText.lastIndexOf('.'));
            if (!stem.isBlank()) {
                System.out.println("[locate] OCR retry with stem: '" + stem + "'");
                ocrCoords = screenMemoryService.findTextOnScreen(ctx.imagePath(), stem);
                if (ocrCoords != null) {
                    int sx = (int) Math.round(ocrCoords[0] * ctx.scaleX());
                    int sy = (int) Math.round(ocrCoords[1] * ctx.scaleY());
                    System.out.println("[locate] OCR STEM MATCH: '" + stem + "' → screen(" + sx + "," + sy + ")");
                    return new ElementLocation(sx, sy, "OCR(stem)", ctx.imgWidth(), ctx.imgHeight(),
                            ctx.logicalWidth(), ctx.logicalHeight(), ctx.scaleX(), ctx.scaleY());
                }
            }
        }

        // Try AWS Textract (better at white text on dark backgrounds)
        if (textractService.isAvailable()) {
            System.out.println("[locate] OCR miss — trying AWS Textract for '" + searchText + "'...");
            double[] textractCoords = textractService.findTextOnScreen(ctx.imagePath(), searchText);
            if (textractCoords != null) {
                int sx = (int) Math.round(textractCoords[0] * ctx.scaleX());
                int sy = (int) Math.round(textractCoords[1] * ctx.scaleY());
                System.out.println("[locate] TEXTRACT MATCH: '" + searchText + "' → screen(" + sx + "," + sy + ")");
                return new ElementLocation(sx, sy, "Textract", ctx.imgWidth(), ctx.imgHeight(),
                        ctx.logicalWidth(), ctx.logicalHeight(), ctx.scaleX(), ctx.scaleY());
            }

            // Textract fallback: try filename stem
            if (searchText.contains(".")) {
                String stem = searchText.substring(0, searchText.lastIndexOf('.'));
                if (!stem.isBlank()) {
                    textractCoords = textractService.findTextOnScreen(ctx.imagePath(), stem);
                    if (textractCoords != null) {
                        int sx = (int) Math.round(textractCoords[0] * ctx.scaleX());
                        int sy = (int) Math.round(textractCoords[1] * ctx.scaleY());
                        System.out.println("[locate] TEXTRACT STEM MATCH: '" + stem + "' → screen(" + sx + "," + sy + ")");
                        return new ElementLocation(sx, sy, "Textract(stem)", ctx.imgWidth(), ctx.imgHeight(),
                                ctx.logicalWidth(), ctx.logicalHeight(), ctx.scaleX(), ctx.scaleY());
                    }
                }
            }
        }

        System.out.println("[locate] OCR+Textract miss for '" + searchText + "' — trying AI vision...");
        if (!visionService.isAvailable()) return null;

        int[] visionCoords = visionService.findElementCoordinates(
                ctx.imagePath(), elementDescription,
                ctx.imgWidth() > 0 ? ctx.imgWidth() : ctx.logicalWidth(),
                ctx.imgHeight() > 0 ? ctx.imgHeight() : ctx.logicalHeight());
        if (visionCoords == null) return null;

        int sx = (int) Math.round(visionCoords[0] * ctx.scaleX());
        int sy = (int) Math.round(visionCoords[1] * ctx.scaleY());
        System.out.println("[locate] VISION MATCH: '" + elementDescription + "' → screen(" + sx + "," + sy + ")");
        return new ElementLocation(sx, sy, "AI vision", ctx.imgWidth(), ctx.imgHeight(),
                ctx.logicalWidth(), ctx.logicalHeight(), ctx.scaleX(), ctx.scaleY());
    }

    // ═══ Element click: OCR-first, AI vision fallback ═══

    @Tool(description = "Find a UI element on screen by description and click on it. Takes a screenshot, "
            + "uses OCR to locate text-based elements with pixel-perfect accuracy, and falls back to AI vision "
            + "for non-text elements. Automatically hides the Mins Bot window during capture to avoid OCR interference. "
            + "Use when you need to click a button, link, input field, tab, icon, or any "
            + "visible element. Example: findAndClickElement('the Submit button') "
            + "or findAndClickElement('the search input field') or findAndClickElement('the Selection tab').")
    public String findAndClickElement(
            @ToolParam(description = "Description of the element to find and click, e.g. 'the Submit button', "
                    + "'the search input field', 'the Selection tab', 'the close icon'") String elementDescription) {
        notifier.notify("Finding: " + elementDescription);

        hideMinsBotWindow();
        try {
            ScreenshotContext ctx = captureScreen();
            if (ctx == null) return "Could not take screenshot.";

            ElementLocation loc = locateOnImage(ctx, elementDescription);
            if (loc == null) {
                return "Could not find '" + elementDescription + "' on screen (tried OCR + AI vision). "
                        + "Try with a more specific description. " + screenshotUrl(ctx.imagePath());
            }

            String clickResult = systemControl.mouseClick(loc.screenX(), loc.screenY(), "left");
            return loc.summary(elementDescription) + ". " + clickResult;
        } finally {
            showMinsBotWindow();
        }
    }

    // ═══ Element locate (no click) — for drag-and-drop workflows ═══

    @Tool(description = "Find a UI element on screen and return its screen coordinates WITHOUT clicking. "
            + "Automatically hides the Mins Bot window during capture to avoid OCR interference. "
            + "Use this for drag-and-drop: call findElementOnScreen for the source item and the target, "
            + "then use mouseDrag(sourceX, sourceY, targetX, targetY). "
            + "Example workflow: findElementOnScreen('my_file.txt') → returns coordinates → "
            + "findElementOnScreen('TARGET folder') → returns coordinates → mouseDrag(x1,y1,x2,y2).")
    public String findElementOnScreen(
            @ToolParam(description = "Description of the element to locate, e.g. 'my_file.txt', "
                    + "'the TARGET folder', 'the Recycle Bin icon'") String elementDescription) {
        notifier.notify("Locating: " + elementDescription);

        hideMinsBotWindow();
        try {
            ScreenshotContext ctx = captureScreen();
            if (ctx == null) return "Could not take screenshot.";

            ElementLocation loc = locateOnImage(ctx, elementDescription);
            if (loc == null) {
                return "Could not find '" + elementDescription + "' on screen (tried OCR + AI vision). "
                        + "Try with a more specific description. " + screenshotUrl(ctx.imagePath());
            }

            return loc.summary(elementDescription) + ". Coordinates: x=" + loc.screenX() + ", y=" + loc.screenY()
                    + ". Use these coordinates with mouseDrag or mouseClick.";
        } finally {
            showMinsBotWindow();
        }
    }

    // ═══ Drag-and-drop: find both items on ONE screenshot and drag ═══

    /** Result of verifying a drag operation. */
    private enum DragVerifyResult { SUCCESS, MOVED, FAILED, UNKNOWN }

    @Tool(description = "Find two elements on screen and drag one to the other. Takes a SINGLE screenshot, "
            + "locates both the source and target via OCR/vision, and performs the mouse drag. "
            + "Automatically hides the Mins Bot window during capture to avoid OCR interference. "
            + "Retries up to 3 times with fresh screenshots until verification confirms the drag succeeded. "
            + "Use when the user says 'drag X into Y', 'drag X to Y', 'move X into Y folder'. "
            + "Example: findAndDragElement('my_file.txt', 'TARGET folder') drags the file into the folder.")
    public String findAndDragElement(
            @ToolParam(description = "Description of the source element to drag, e.g. 'my_file.txt', 'the icon'") String sourceDescription,
            @ToolParam(description = "Description of the target to drag onto, e.g. 'TARGET folder', 'Recycle Bin'") String targetDescription) {
        notifier.notify("Dragging: " + sourceDescription + " → " + targetDescription);

        hideMinsBotWindow();
        try {
            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                System.out.println("[drag] Attempt " + attempt + "/" + maxAttempts);

                if (attempt > 1) {
                    notifier.notify("Retry " + attempt + "/3: " + sourceDescription + " → " + targetDescription);
                }

                // Take a fresh screenshot each attempt (source/target may have shifted)
                ScreenshotContext ctx = captureScreen();
                if (ctx == null) return "Could not take screenshot.";

                ElementLocation source = locateOnImage(ctx, sourceDescription);
                if (source == null) {
                    if (attempt == 1) {
                        return "Could not find source '" + sourceDescription + "' on screen. Try a more specific description. "
                                + screenshotUrl(ctx.imagePath());
                    }
                    // On retry, source not found likely means it was already dragged successfully
                    System.out.println("[drag] Source '" + sourceDescription + "' no longer found on retry — likely already moved.");
                    return "Drag verified: '" + sourceDescription + "' is no longer visible on screen (moved successfully on previous attempt).";
                }

                ElementLocation target = locateOnImage(ctx, targetDescription);
                if (target == null) {
                    return "Found source '" + sourceDescription + "' but could not find target '" + targetDescription
                            + "' on screen. Try a more specific description. " + screenshotUrl(ctx.imagePath());
                }

                System.out.println("[drag] Attempt " + attempt + ": dragging from (" + source.screenX() + "," + source.screenY()
                        + ") to (" + target.screenX() + "," + target.screenY() + ")");

                // Use fast atomic drag — segmented drag is too slow (OCR at each 30px
                // checkpoint causes Windows to cancel the drag-and-drop operation)
                String dragResult = systemControl.mouseDrag(
                        source.screenX(), source.screenY(), target.screenX(), target.screenY());

                // Wait for UI to settle
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

                // Verify using AI vision
                notifier.notify("Verifying drag (attempt " + attempt + ")...");
                DragVerifyResult verify = verifyDragResult(sourceDescription, targetDescription,
                        source.screenX(), source.screenY());

                if (verify == DragVerifyResult.SUCCESS || verify == DragVerifyResult.MOVED) {
                    String msg = verify == DragVerifyResult.SUCCESS
                            ? "Verified: '" + sourceDescription + "' is no longer at its original position."
                            : "Verified: '" + sourceDescription + "' moved to a new position.";
                    return "Dragged '" + sourceDescription + "' → '" + targetDescription + "' on attempt " + attempt
                            + ". " + dragResult + " " + msg;
                }

                if (verify == DragVerifyResult.FAILED && attempt < maxAttempts) {
                    System.out.println("[drag] Attempt " + attempt + " failed verification — retrying...");
                    try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    continue;
                }

                // Last attempt or unknown — return what we have
                if (attempt == maxAttempts) {
                    return "Dragged '" + sourceDescription + "' → '" + targetDescription
                            + "' but after " + maxAttempts + " attempts, the source element may still be near its original position. "
                            + "The drag may not have succeeded — please verify visually.";
                }
            }

            return "Drag failed after " + maxAttempts + " attempts.";
        } finally {
            showMinsBotWindow();
        }
    }

    // ═══ Mins Bot window hide/show (prevents OCR from matching chat text) ═══

    /**
     * Hide the Mins Bot window (iconify via JavaFX) so OCR doesn't pick up chat text from our own UI.
     * Uses the JavaFX Stage API directly — reliable for JavaFX transparent/decorated windows.
     */
    private void hideMinsBotWindow() {
        try {
            com.minsbot.FloatingAppLauncher.hideWindow();
            System.out.println("[locate] Mins Bot window hidden (iconified)");
        } catch (Exception e) {
            System.out.println("[locate] Could not hide Mins Bot window: " + e.getMessage());
        }
    }

    /** Restore the Mins Bot window after element finding/actions. */
    private void showMinsBotWindow() {
        try {
            com.minsbot.FloatingAppLauncher.showWindow();
            System.out.println("[locate] Mins Bot window restored");
        } catch (Exception e) {
            System.out.println("[locate] Could not restore Mins Bot window: " + e.getMessage());
        }
    }

    /**
     * Crop a square region around the source element's original position from a screenshot.
     * Returns the path to the cropped image, or null on failure.
     */
    private Path cropAroundSource(ScreenshotContext ctx, int sourceScreenX, int sourceScreenY) {
        try {
            java.awt.image.BufferedImage fullImg = javax.imageio.ImageIO.read(ctx.imagePath().toFile());
            if (fullImg == null) return null;

            // Convert screen (logical) coordinates to image pixel coordinates
            int imgX = (int) Math.round(sourceScreenX / ctx.scaleX());
            int imgY = (int) Math.round(sourceScreenY / ctx.scaleY());

            // Crop size: 25% of the smaller screen dimension — big enough for any desktop icon + label
            int cropSide = Math.min(fullImg.getWidth(), fullImg.getHeight()) / 4;
            int half = cropSide / 2;

            // Clamp to image bounds
            int x1 = Math.max(0, imgX - half);
            int y1 = Math.max(0, imgY - half);
            int x2 = Math.min(fullImg.getWidth(), x1 + cropSide);
            int y2 = Math.min(fullImg.getHeight(), y1 + cropSide);
            x1 = Math.max(0, x2 - cropSide);
            y1 = Math.max(0, y2 - cropSide);

            int w = x2 - x1;
            int h = y2 - y1;
            System.out.println("[drag] Cropping verify image: (" + x1 + "," + y1 + ") " + w + "x" + h
                    + " (source at screen " + sourceScreenX + "," + sourceScreenY
                    + " → image " + imgX + "," + imgY + ")");

            java.awt.image.BufferedImage cropped = fullImg.getSubimage(x1, y1, w, h);
            Path cropPath = ctx.imagePath().resolveSibling("verify_crop.png");
            javax.imageio.ImageIO.write(cropped, "PNG", cropPath.toFile());
            return cropPath;
        } catch (Exception e) {
            System.out.println("[drag] Crop failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Verify a drag operation using hybrid OCR + AI vision.
     * <ol>
     *   <li>OCR check: if source text is still visible → FAILED (definitive, fast)</li>
     *   <li>If OCR says gone → crop around source location, send to gpt-4o for confirmation</li>
     * </ol>
     */
    private DragVerifyResult verifyDragResult(String sourceDescription, String targetDescription,
                                              int sourceScreenX, int sourceScreenY) {
        try {
            ScreenshotContext verifyCtx = captureScreen();
            if (verifyCtx == null) return DragVerifyResult.UNKNOWN;

            String searchText = extractSearchText(sourceDescription);
            System.out.println("[drag] Verify: looking for '" + searchText + "' on post-drag screenshot");

            // ── Phase 1: OCR check (fast, definitive for positives) ──
            double[] ocrCoords = screenMemoryService.findTextOnScreen(verifyCtx.imagePath(), searchText);
            if (ocrCoords != null) {
                System.out.println("[drag] OCR verify: '" + searchText + "' STILL VISIBLE → FAILED");
                return DragVerifyResult.FAILED;
            }

            // Also try filename stem (e.g. "PLANTS" for "PLANTS.txt")
            if (searchText.contains(".")) {
                String stem = searchText.substring(0, searchText.lastIndexOf('.'));
                if (!stem.isBlank()) {
                    double[] stemCoords = screenMemoryService.findTextOnScreen(verifyCtx.imagePath(), stem);
                    if (stemCoords != null) {
                        System.out.println("[drag] OCR verify: stem '" + stem + "' STILL VISIBLE → FAILED");
                        return DragVerifyResult.FAILED;
                    }
                }
            }

            // ── Phase 2: Textract check (catches text OCR missed, e.g. white on dark) ──
            if (textractService.isAvailable()) {
                double[] txCoords = textractService.findTextOnScreen(verifyCtx.imagePath(), searchText);
                if (txCoords != null) {
                    System.out.println("[drag] Textract verify: '" + searchText + "' STILL VISIBLE → FAILED");
                    return DragVerifyResult.FAILED;
                }
                if (searchText.contains(".")) {
                    String stem = searchText.substring(0, searchText.lastIndexOf('.'));
                    if (!stem.isBlank()) {
                        txCoords = textractService.findTextOnScreen(verifyCtx.imagePath(), stem);
                        if (txCoords != null) {
                            System.out.println("[drag] Textract verify: stem '" + stem + "' STILL VISIBLE → FAILED");
                            return DragVerifyResult.FAILED;
                        }
                    }
                }
            }

            System.out.println("[drag] OCR+Textract verify: '" + searchText + "' not found — checking with AI vision...");

            // ── Phase 3: AI vision (gpt-4o) on cropped region around source location ──
            if (!visionService.isAvailable()) {
                System.out.println("[drag] AI vision not available — trusting OCR result → SUCCESS");
                return DragVerifyResult.SUCCESS;
            }

            Path cropPath = cropAroundSource(verifyCtx, sourceScreenX, sourceScreenY);
            if (cropPath == null) {
                System.out.println("[drag] Could not crop image — trusting OCR result → SUCCESS");
                return DragVerifyResult.SUCCESS;
            }

            String aiResponse = visionService.verifyDragStrong(cropPath, sourceDescription);
            if (aiResponse == null || aiResponse.isBlank()) {
                System.out.println("[drag] AI vision returned empty — trusting OCR result → SUCCESS");
                return DragVerifyResult.SUCCESS;
            }

            String firstLine = aiResponse.split("\\r?\\n")[0].trim().toUpperCase();
            System.out.println("[drag] AI vision (strong): " + aiResponse.replace("\n", " | "));

            if (firstLine.startsWith("YES")) {
                System.out.println("[drag] Both OCR and AI confirm source is gone → SUCCESS");
                return DragVerifyResult.SUCCESS;
            } else if (firstLine.startsWith("NO")) {
                System.out.println("[drag] AI vision says source still visible (OCR missed it) → FAILED");
                return DragVerifyResult.FAILED;
            }

            // AI returned something ambiguous — trust OCR
            System.out.println("[drag] AI vision ambiguous — trusting OCR result → SUCCESS");
            return DragVerifyResult.SUCCESS;
        } catch (Exception e) {
            System.out.println("[drag] Verification failed: " + e.getMessage());
            return DragVerifyResult.UNKNOWN;
        }
    }

    /**
     * Extract the meaningful search text from a natural language element description.
     * "the Submit button" → "Submit", "the search input field" → "search",
     * "Selection tab" → "Selection", "Sign In" → "Sign In"
     */
    private static String extractSearchText(String description) {
        String s = description.trim();
        // Remove leading articles
        s = s.replaceFirst("(?i)^(the|a|an)\\s+", "");
        // Remove trailing UI element type words (includes "folder"/"file" so "DATA folder" → "DATA")
        s = s.replaceFirst("(?i)\\s+(button|btn|tab|link|field|input|icon|menu|option|checkbox|radio|label|text|area|box|dropdown|select|folder|dir|directory|file|window|dialog|panel)$", "");
        // If nothing left, return original
        return s.isBlank() ? description.trim() : s.trim();
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

    // ═══ Browser tab capture ═══

    @Tool(description = "Capture screenshots of ALL open browser tabs (or only tabs matching a keyword filter). "
            + "Automatically cycles through every tab: switches tab → waits for render → takes screenshot → repeats. "
            + "Use when the user asks to 'capture all tabs', 'screenshot each tab', 'capture all youtube tabs', etc. "
            + "Set filterKeyword to filter by tab title (e.g. 'youtube', 'github'). Leave empty to capture ALL tabs.")
    public String captureAllBrowserTabs(
            @ToolParam(description = "Browser name: 'chrome', 'edge', 'firefox'. Defaults to 'chrome'.") String browserName,
            @ToolParam(description = "Optional keyword to filter tabs by title (e.g. 'youtube'). Empty string = capture all tabs.") String filterKeyword) {
        String filter = (filterKeyword != null && !filterKeyword.isBlank()) ? filterKeyword : "";
        notifier.notify("Capturing " + (filter.isEmpty() ? "all" : "'" + filter + "'") + " browser tabs...");
        return systemControl.captureAllBrowserTabs(browserName, filter);
    }

    // ═══ Wait / delay ═══

    @Tool(description = "Wait/pause for a specified number of seconds before continuing. "
            + "Essential for multi-step computer-use workflows: after switching browser tabs, "
            + "opening apps, navigating to URLs, or any action that needs time to render before "
            + "taking a screenshot or interacting. Max 30 seconds.")
    public String waitSeconds(
            @ToolParam(description = "Number of seconds to wait (1-30)") int seconds) {
        int clamped = Math.max(1, Math.min(30, seconds));
        notifier.notify("Waiting " + clamped + "s...");
        try {
            Thread.sleep(clamped * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Wait interrupted after partial delay.";
        }
        return "Waited " + clamped + " second(s). Ready to continue.";
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
