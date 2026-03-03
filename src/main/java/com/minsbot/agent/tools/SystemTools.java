package com.minsbot.agent.tools;

import com.microsoft.playwright.Page;
import com.minsbot.MinsBotQuitService;
import com.minsbot.agent.ChromeCdpService;
import com.minsbot.agent.DocumentAiService;
import com.minsbot.agent.GeminiVisionService;
import com.minsbot.agent.ScreenMemoryService;
import com.minsbot.agent.SystemControlService;
import com.minsbot.agent.TextractService;
import com.minsbot.agent.VisionService;
import com.sun.management.OperatingSystemMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SystemTools.class);

    private final SystemControlService systemControl;
    private final ToolExecutionNotifier notifier;
    private final MinsBotQuitService quitService;
    private final VisionService visionService;
    private final GeminiVisionService geminiVisionService;
    private final ScreenMemoryService screenMemoryService;
    private final DocumentAiService documentAiService;
    private final TextractService textractService;
    private final ChromeCdpService cdpService;
    private final ChromeCdpTools chromeCdpTools;

    public SystemTools(SystemControlService systemControl, ToolExecutionNotifier notifier,
                       MinsBotQuitService quitService, VisionService visionService,
                       GeminiVisionService geminiVisionService,
                       ScreenMemoryService screenMemoryService, DocumentAiService documentAiService,
                       TextractService textractService,
                       ChromeCdpService cdpService, ChromeCdpTools chromeCdpTools) {
        this.systemControl = systemControl;
        this.notifier = notifier;
        this.quitService = quitService;
        this.visionService = visionService;
        this.geminiVisionService = geminiVisionService;
        this.screenMemoryService = screenMemoryService;
        this.documentAiService = documentAiService;
        this.textractService = textractService;
        this.cdpService = cdpService;
        this.chromeCdpTools = chromeCdpTools;
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

    @Tool(description = "Take a screenshot of the entire screen right now, analyze it with AI vision, "
            + "and return a detailed description of what is visible. Use this to SEE the current screen state, "
            + "verify actions completed correctly, and decide what to do next. "
            + "Returns: visual description of everything on screen (windows, text, shapes, colors, UI elements).")
    public String takeScreenshot() {
        notifier.notify("Taking screenshot...");
        log.info("[takeScreenshot] Capturing screen for verification...");
        // Hide Mins Bot window to avoid capturing it
        try { com.minsbot.FloatingAppLauncher.hideWindow(); } catch (Exception ignored) {}
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}

        String result = systemControl.takeScreenshot();

        // Restore window
        try { com.minsbot.FloatingAppLauncher.showWindow(); } catch (Exception ignored) {}

        if (result == null || !result.startsWith("Screenshot saved:")) {
            log.warn("[takeScreenshot] Screenshot FAILED: {}", result);
            return "Screenshot failed: " + result;
        }

        // Parse the screenshot path
        String pathStr = result.replace("Screenshot saved: ", "").trim();
        java.nio.file.Path screenshotPath = java.nio.file.Paths.get(pathStr);
        log.info("[takeScreenshot] Screenshot saved: {}", screenshotPath.getFileName());

        // Analyze with vision AI (Gemini first, then OpenAI Vision fallback)
        log.info("[takeScreenshot] Starting AI vision analysis for verification...");
        long startMs = System.currentTimeMillis();
        String analysis = analyzeScreenshotForVerification(screenshotPath);
        long elapsedMs = System.currentTimeMillis() - startMs;

        if (analysis != null && !analysis.isBlank()) {
            log.info("[takeScreenshot] Vision analysis complete in {}ms ({} chars)",
                    elapsedMs, analysis.length());
            log.debug("[takeScreenshot] Analysis result: {}",
                    analysis.length() > 300 ? analysis.substring(0, 300) + "..." : analysis);
            return "Screenshot saved: " + pathStr + "\n\nWHAT IS ON SCREEN:\n" + analysis;
        }

        // Fallback: return just the path if vision unavailable
        log.warn("[takeScreenshot] Vision analysis unavailable after {}ms — returning path only", elapsedMs);
        return result + "\n(Vision analysis unavailable — could not describe screen contents)";
    }

    /**
     * Analyze a screenshot with Gemini or OpenAI Vision for detailed content description.
     * This is a general-purpose analysis that describes EVERYTHING visible — shapes, text,
     * colors, UI elements, windows, etc. Enables the AI to verify task completion.
     */
    private String analyzeScreenshotForVerification(java.nio.file.Path screenshotPath) {
        String verificationPrompt = """
                Analyze this screenshot in DETAIL. Describe EVERYTHING you see:
                1. WINDOWS: What applications are open? Window titles?
                2. CONTENT: What is the main content on screen? Describe shapes, drawings, text, images, \
                colors, layouts — be SPECIFIC. If there is a drawing, describe the EXACT shape \
                (circle, square, rectangle, triangle, line, etc.), its position, and color.
                3. UI ELEMENTS: Buttons, menus, toolbars, selected tools, highlighted items.
                4. TEXT: All readable text on screen (preserve exact wording).
                5. FILES/FOLDERS: Any visible file or folder names on the desktop or in file explorers.

                Be PRECISE and HONEST about what you see. If something looks like a rectangle, say rectangle \
                — do NOT call it a circle. If text is blurry, say it's blurry. Accuracy is critical \
                because this description is used to verify if tasks were completed correctly.""";

        log.info("[Verification] Verification prompt ({} chars):\n{}", verificationPrompt.length(), verificationPrompt);

        // Try Gemini first
        if (geminiVisionService.isAvailable()) {
            log.info("[Verification] Sending screenshot + prompt to Gemini for analysis...");
            try {
                long t0 = System.currentTimeMillis();
                String geminiResult = geminiVisionService.analyze(screenshotPath, verificationPrompt);
                long dt = System.currentTimeMillis() - t0;
                if (geminiResult != null && !geminiResult.isBlank()) {
                    log.info("[Verification] Gemini analysis SUCCESS in {}ms ({} chars)", dt, geminiResult.length());
                    return geminiResult;
                }
                log.warn("[Verification] Gemini returned null/empty after {}ms — falling back", dt);
            } catch (Exception e) {
                log.warn("[Verification] Gemini FAILED: {} — falling back to OpenAI Vision", e.getMessage());
            }
        } else {
            log.info("[Verification] Gemini not available — trying OpenAI Vision");
        }

        // Try OpenAI Vision
        if (visionService.isAvailable()) {
            log.info("[Verification] Sending screenshot to OpenAI Vision for analysis...");
            try {
                long t0 = System.currentTimeMillis();
                String visionResult = visionService.analyzeScreenshot(screenshotPath);
                long dt = System.currentTimeMillis() - t0;
                if (visionResult != null && !visionResult.isBlank()) {
                    log.info("[Verification] OpenAI Vision analysis SUCCESS in {}ms ({} chars)", dt, visionResult.length());
                    return visionResult;
                }
                log.warn("[Verification] OpenAI Vision returned null/empty after {}ms — falling back to OCR", dt);
            } catch (Exception e) {
                log.warn("[Verification] OpenAI Vision FAILED: {} — falling back to OCR", e.getMessage());
            }
        } else {
            log.info("[Verification] OpenAI Vision not available — trying OCR");
        }

        // Last resort: OCR text extraction
        log.info("[Verification] Using OCR fallback for text extraction...");
        try {
            java.util.List<ScreenMemoryService.OcrWord> ocrWords =
                    screenMemoryService.runOcrWithBounds(screenshotPath);
            if (!ocrWords.isEmpty()) {
                StringBuilder sb = new StringBuilder("Visible text (OCR): ");
                java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                for (ScreenMemoryService.OcrWord w : ocrWords) {
                    if (!w.text().isBlank() && w.text().length() > 1) seen.add(w.text().trim());
                }
                sb.append(String.join(", ", seen));
                log.info("[Verification] OCR extracted {} text items", seen.size());
                return sb.toString();
            }
            log.warn("[Verification] OCR returned no text items");
        } catch (Exception e) {
            log.warn("[Verification] OCR FAILED: {}", e.getMessage());
        }

        log.warn("[Verification] All vision methods failed — no analysis available");
        return null;
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

    @Tool(description = "Restart the computer. Optionally delay in minutes (0 = immediately).")
    public String restartComputer(
            @ToolParam(description = "Minutes to wait before restarting (0 for immediately)") double delayMinutesRaw) {
        notifier.notify("Restarting...");
        int delayMinutes = (int) Math.round(delayMinutesRaw);
        int seconds = Math.max(0, delayMinutes) * 60;
        return systemControl.runCmd("shutdown /r /t " + seconds);
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

    @Tool(description = "Open a URL in the user's default PC browser (Chrome, Edge, Firefox, etc.). "
            + "Automatically checks if the browser already has a tab with the same site open — "
            + "if so, reuses it instead of opening a duplicate tab. "
            + "Do NOT use this if the user explicitly says 'in-browser' or 'chat browser' — "
            + "those go to the built-in Mins Bot browser tab instead.")
    public String openUrl(
            @ToolParam(description = "The URL to open, e.g. 'https://www.youtube.com' or 'https://google.com'") String url) {
        notifier.notify("Opening in PC browser: " + url);

        // Normalize target URL
        String normalizedUrl = url;
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "https://" + normalizedUrl;
        }
        String targetDomain = extractDomain(normalizedUrl);

        // Check if browser already has this site open in the current tab
        if (targetDomain != null && !targetDomain.isEmpty()) {
            // Try common browsers in order
            for (String browser : new String[]{"chrome", "msedge", "firefox"}) {
                if (systemControl.isBrowserRunning(browser)) {
                    String currentUrl = systemControl.getCurrentBrowserUrl(browser);
                    if (currentUrl != null) {
                        String currentDomain = extractDomain(currentUrl);
                        if (targetDomain.equalsIgnoreCase(currentDomain)) {
                            // Same site already open — just focus and navigate if URL differs
                            if (currentUrl.equalsIgnoreCase(normalizedUrl)
                                    || currentUrl.equalsIgnoreCase(normalizedUrl + "/")) {
                                System.out.println("[openUrl] " + targetDomain + " already open — reusing tab");
                                return "Browser already has " + targetDomain + " open. Focused existing tab.";
                            }
                            // Same domain but different page — navigate within existing tab
                            System.out.println("[openUrl] Same domain " + targetDomain + " — navigating existing tab");
                            return systemControl.browserNavigate(browser, normalizedUrl);
                        }
                    }
                    break; // found a running browser, but different site — open new tab
                }
            }
        }

        return systemControl.openUrl(url);
    }

    /** Extract the domain from a URL (e.g. "https://www.youtube.com/watch?v=..." → "youtube.com"). */
    private static String extractDomain(String url) {
        try {
            String s = url.trim();
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
            java.net.URI uri = new java.net.URI(s);
            String host = uri.getHost();
            if (host == null) return null;
            // Strip "www." prefix for comparison
            if (host.startsWith("www.")) host = host.substring(4);
            return host.toLowerCase();
        } catch (Exception e) {
            return null;
        }
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

    // ═══ User abort detection ═══

    /**
     * Two-sample approach: check if the user is actively moving the mouse.
     * Samples cursor position, waits briefly, re-samples.
     * If the cursor moved significantly, the user is active — abort.
     */
    private boolean checkUserAbort() {
        try {
            java.awt.Point p1 = java.awt.MouseInfo.getPointerInfo().getLocation();
            Thread.sleep(80);
            java.awt.Point p2 = java.awt.MouseInfo.getPointerInfo().getLocation();
            if (Math.abs(p2.x - p1.x) > 3 || Math.abs(p2.y - p1.y) > 3) {
                System.out.println("[abort] User mouse activity detected: (" + p1.x + "," + p1.y
                        + ") → (" + p2.x + "," + p2.y + ")");
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Post-move check: the bot moved the cursor to (expectedX, expectedY).
     * If the actual cursor is far from there, the user moved it — abort.
     */
    private boolean checkUserAbort(int expectedX, int expectedY) {
        if (systemControl.isUserOverriding(expectedX, expectedY)) {
            System.out.println("[abort] User mouse movement detected — aborting action");
            return true;
        }
        return false;
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

        int imgW = ctx.imgWidth() > 0 ? ctx.imgWidth() : ctx.logicalWidth();
        int imgH = ctx.imgHeight() > 0 ? ctx.imgHeight() : ctx.logicalHeight();

        // 1st: OpenAI Vision (primary AI vision)
        if (visionService.isAvailable()) {
            System.out.println("[locate] Trying OpenAI Vision for '" + elementDescription + "'...");
            int[] visionCoords = visionService.findElementCoordinates(
                    ctx.imagePath(), elementDescription, imgW, imgH);
            if (visionCoords != null) {
                int sx = (int) Math.round(visionCoords[0] * ctx.scaleX());
                int sy = (int) Math.round(visionCoords[1] * ctx.scaleY());
                System.out.println("[locate] OPENAI VISION MATCH: '" + elementDescription + "' → screen(" + sx + "," + sy + ")");
                return new ElementLocation(sx, sy, "OpenAI vision", ctx.imgWidth(), ctx.imgHeight(),
                        ctx.logicalWidth(), ctx.logicalHeight(), ctx.scaleX(), ctx.scaleY());
            }
        }

        // 2nd: Google Cloud Document AI (cloud OCR with bounding boxes)
        if (documentAiService.isAvailable()) {
            System.out.println("[locate] OpenAI miss — trying Google Document AI for '" + searchText + "'...");
            double[] docAiCoords = documentAiService.findTextOnScreen(ctx.imagePath(), searchText);
            if (docAiCoords != null) {
                int sx = (int) Math.round(docAiCoords[0] * ctx.scaleX());
                int sy = (int) Math.round(docAiCoords[1] * ctx.scaleY());
                System.out.println("[locate] DOCUMENT_AI MATCH: '" + searchText + "' → screen(" + sx + "," + sy + ")");
                return new ElementLocation(sx, sy, "DocumentAI", ctx.imgWidth(), ctx.imgHeight(),
                        ctx.logicalWidth(), ctx.logicalHeight(), ctx.scaleX(), ctx.scaleY());
            }

            // Document AI fallback: try filename stem
            if (searchText.contains(".")) {
                String stem = searchText.substring(0, searchText.lastIndexOf('.'));
                if (!stem.isBlank()) {
                    docAiCoords = documentAiService.findTextOnScreen(ctx.imagePath(), stem);
                    if (docAiCoords != null) {
                        int sx = (int) Math.round(docAiCoords[0] * ctx.scaleX());
                        int sy = (int) Math.round(docAiCoords[1] * ctx.scaleY());
                        System.out.println("[locate] DOCUMENT_AI STEM MATCH: '" + stem + "' → screen(" + sx + "," + sy + ")");
                        return new ElementLocation(sx, sy, "DocumentAI(stem)", ctx.imgWidth(), ctx.imgHeight(),
                                ctx.logicalWidth(), ctx.logicalHeight(), ctx.scaleX(), ctx.scaleY());
                    }
                }
            }
        }

        // 3rd: Gemini Flash (AI vision fallback)
        if (geminiVisionService.isAvailable()) {
            System.out.println("[locate] OpenAI+DocAI miss — trying Gemini Flash for '" + elementDescription + "'...");
            int[] geminiCoords = geminiVisionService.findElementCoordinates(
                    ctx.imagePath(), elementDescription, imgW, imgH);
            if (geminiCoords != null) {
                int sx = (int) Math.round(geminiCoords[0] * ctx.scaleX());
                int sy = (int) Math.round(geminiCoords[1] * ctx.scaleY());
                System.out.println("[locate] GEMINI MATCH: '" + elementDescription + "' → screen(" + sx + "," + sy + ")");
                return new ElementLocation(sx, sy, "Gemini", ctx.imgWidth(), ctx.imgHeight(),
                        ctx.logicalWidth(), ctx.logicalHeight(), ctx.scaleX(), ctx.scaleY());
            }
        }

        // 4th: AWS Textract (cloud OCR fallback)
        if (textractService.isAvailable()) {
            System.out.println("[locate] OpenAI+DocAI+Gemini miss — trying AWS Textract for '" + searchText + "'...");
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

        // 5th: Windows OCR (local, free — last resort)
        double[] ocrCoords = screenMemoryService.findTextOnScreen(ctx.imagePath(), searchText);
        if (ocrCoords != null) {
            int sx = (int) Math.round(ocrCoords[0] * ctx.scaleX());
            int sy = (int) Math.round(ocrCoords[1] * ctx.scaleY());
            System.out.println("[locate] OCR MATCH: '" + searchText + "' → screen(" + sx + "," + sy + ")");
            return new ElementLocation(sx, sy, "OCR", ctx.imgWidth(), ctx.imgHeight(),
                    ctx.logicalWidth(), ctx.logicalHeight(), ctx.scaleX(), ctx.scaleY());
        }
        if (searchText.contains(".")) {
            String stem = searchText.substring(0, searchText.lastIndexOf('.'));
            if (!stem.isBlank()) {
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

        System.out.println("[locate] All methods missed for '" + searchText + "'");
        return null;
    }

    // ═══ Element click: OCR-first, AI vision fallback ═══

    @Tool(description = "Find a UI element on screen by description and click on it. Takes a screenshot, "
            + "uses OCR/Textract/Gemini/AI vision to locate the element. Retries up to 5 times with fresh screenshots. "
            + "Automatically hides the Mins Bot window during capture. "
            + "Use when you need to click a button, link, input field, tab, icon, or any "
            + "visible element. Example: findAndClickElement('the Submit button').")
    public String findAndClickElement(
            @ToolParam(description = "Description of the element to find and click, e.g. 'the Submit button', "
                    + "'the search input field', 'the Selection tab', 'the close icon'") String elementDescription) {
        notifier.notify("Finding: " + elementDescription);

        // ── CDP-first: try Playwright DOM-level click if Chrome is the foreground window ──
        try {
            String windowTitle = systemControl.getForegroundWindowTitle();
            if (windowTitle != null && (windowTitle.contains("Chrome") || windowTitle.contains("Google")
                    || windowTitle.contains("- chrome") || windowTitle.contains("Chromium"))) {
                cdpService.ensureConnected();
                Page activePage = cdpService.getActivePage(windowTitle);
                if (activePage != null) {
                    System.out.println("[click] Chrome detected — trying Playwright CDP smart click for '" + elementDescription + "'...");
                    String cdpResult = chromeCdpTools.smartClick(activePage, elementDescription);
                    if (cdpResult.startsWith("OK:")) {
                        System.out.println("[click] " + cdpResult);
                        return cdpResult;
                    }
                    System.out.println("[click] CDP smart click failed — falling back to screenshot approach");
                }
            }
        } catch (Exception e) {
            System.out.println("[click] CDP attempt failed: " + e.getMessage() + " — falling back to screenshot approach");
        }

        hideMinsBotWindow();
        try {
            for (int attempt = 1; attempt <= 5; attempt++) {
                if (attempt > 1) {
                    System.out.println("[click] Retry " + attempt + "/5 for '" + elementDescription + "'...");
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }

                // Check if user is actively moving mouse before we act
                if (checkUserAbort()) {
                    return "Click aborted: user mouse movement detected.";
                }

                ScreenshotContext ctx = captureScreen();
                if (ctx == null) continue;

                ElementLocation loc = locateOnImage(ctx, elementDescription);
                if (loc != null) {
                    // Final check right before clicking
                    if (checkUserAbort()) {
                        return "Click aborted: user moved the mouse.";
                    }
                    String clickResult = systemControl.mouseClick(loc.screenX(), loc.screenY(), "left");
                    return loc.summary(elementDescription) + ". " + clickResult;
                }

                if (attempt == 5) {
                    return "Could not find '" + elementDescription + "' on screen after 5 attempts. "
                            + screenshotUrl(ctx.imagePath());
                }
            }
            return "Could not find '" + elementDescription + "' on screen after 5 attempts.";
        } finally {
            showMinsBotWindow();
        }
    }

    // ═══ Composite: click input + type text (single tool call) ═══

    @Tool(description = "PREFERRED tool for typing text into a browser input field (search box, form field, address bar, etc.). "
            + "Combines click + type into ONE reliable action using clipboard paste. "
            + "Finds the input element on screen via OCR/AI vision, clicks it, pastes the text via Ctrl+V, "
            + "and optionally presses Enter. Use this INSTEAD of separate findAndClickElement + sendKeys calls. "
            + "Example: typeInBrowserInput('the search box', 'voice tools', true) — clicks search box, types 'voice tools', presses Enter.")
    public String typeInBrowserInput(
            @ToolParam(description = "Description of the input element to click, e.g. 'the search box', "
                    + "'the email input field', 'the search input', 'Search...' placeholder text") String inputElementDescription,
            @ToolParam(description = "The text to type into the input field") String textToType,
            @ToolParam(description = "Whether to press Enter after typing (true for search forms, false otherwise)") boolean pressEnter) {
        notifier.notify("Typing '" + textToType + "' into: " + inputElementDescription);

        hideMinsBotWindow();
        try {
            String text = textToType != null ? textToType : "";
            int clickX, clickY;

            // Step 1: Locate the input element on screen
            ScreenshotContext ctx = captureScreen();
            if (ctx == null) return "FAILED: Could not take screenshot.";

            ElementLocation loc = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                if (attempt > 1) {
                    System.out.println("[typeInput] Retry " + attempt + "/2 for '" + inputElementDescription + "'...");
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    ctx = captureScreen();
                    if (ctx == null) continue;
                }
                loc = locateOnImage(ctx, inputElementDescription);
                if (loc != null) break;
            }

            if (loc != null) {
                clickX = loc.screenX();
                clickY = loc.screenY();
                System.out.println("[typeInput] Found '" + inputElementDescription + "' at (" + clickX + "," + clickY + ")");
            } else {
                if (ctx == null) return "FAILED: Could not locate '" + inputElementDescription + "'.";
                clickX = ctx.logicalWidth() / 2;
                clickY = ctx.logicalHeight() / 3;
                System.out.println("[typeInput] Fallback click at center-screen (" + clickX + "," + clickY + ")");
            }

            // Step 2: Focus the browser window, then focus the input element
            // Click #1 — brings the browser window to foreground (gets OS focus)
            systemControl.mouseClick(clickX, clickY, "left");
            try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            // Click #2 — focuses the actual input element within the page
            systemControl.mouseClick(clickX, clickY, "left");
            try { Thread.sleep(400); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            System.out.println("[typeInput] Double-clicked input at (" + clickX + "," + clickY + ")");

            // Step 3: Paste via clipboard (Ctrl+A to select existing text, Ctrl+V to paste new text)
            boolean pasteVerified = false;
            for (int pasteAttempt = 1; pasteAttempt <= 2; pasteAttempt++) {
                try {
                    java.awt.Robot robot = new java.awt.Robot();

                    // Put text on clipboard
                    java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(text);
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                    Thread.sleep(100);

                    // Ctrl+A (select all in input) then Ctrl+V (paste)
                    robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
                    robot.keyPress(java.awt.event.KeyEvent.VK_A);
                    robot.keyRelease(java.awt.event.KeyEvent.VK_A);
                    robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
                    robot.delay(50);

                    robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
                    robot.keyPress(java.awt.event.KeyEvent.VK_V);
                    robot.keyRelease(java.awt.event.KeyEvent.VK_V);
                    robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
                    robot.delay(300);

                    System.out.println("[typeInput] Paste attempt " + pasteAttempt + ": '" + text + "' via Ctrl+V");
                } catch (Exception e) {
                    System.out.println("[typeInput] Paste attempt " + pasteAttempt + " failed: " + e.getMessage());
                    continue;
                }

                // Step 4: Verify the text appeared on screen via OCR
                try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                ScreenshotContext verifyCtx = captureScreen();
                if (verifyCtx != null) {
                    // Use first significant word from the text (3+ chars) for OCR search
                    String searchWord = extractVerifyWord(text);
                    if (searchWord != null) {
                        double[] found = screenMemoryService.findTextOnScreen(verifyCtx.imagePath(), searchWord);
                        if (found != null) {
                            System.out.println("[typeInput] VERIFIED: '" + searchWord + "' found on screen at ("
                                    + found[0] + "," + found[1] + ")");
                            pasteVerified = true;
                            break;
                        } else {
                            System.out.println("[typeInput] Verify FAILED: '" + searchWord + "' NOT found on screen (attempt "
                                    + pasteAttempt + ")");
                        }
                    } else {
                        // Text too short to verify via OCR — assume success
                        pasteVerified = true;
                        break;
                    }
                }

                if (pasteAttempt == 1) {
                    // Retry: click the input again before second paste attempt
                    System.out.println("[typeInput] Retrying — clicking input again and re-pasting...");
                    systemControl.mouseClick(clickX, clickY, "left");
                    try { Thread.sleep(400); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }

            if (!pasteVerified) {
                return "FAILED: Typed '" + text + "' but could NOT verify it appeared on screen. "
                        + "The input element may not have received focus. Try clicking the input manually first.";
            }

            // Step 5: Press Enter if requested
            if (pressEnter) {
                try {
                    java.awt.Robot robot = new java.awt.Robot();
                    robot.delay(200);
                    robot.keyPress(java.awt.event.KeyEvent.VK_ENTER);
                    robot.keyRelease(java.awt.event.KeyEvent.VK_ENTER);
                    System.out.println("[typeInput] Pressed Enter");
                } catch (Exception e) {
                    System.out.println("[typeInput] Enter key failed: " + e.getMessage());
                }
            }

            String locMethod = (loc != null) ? "Found and clicked '" + inputElementDescription + "'" : "Used center-screen fallback";
            String action = pressEnter ? " and pressed Enter" : "";
            return locMethod + ". Successfully typed '" + text + "'" + action + " (verified on screen).";
        } finally {
            showMinsBotWindow();
        }
    }

    /** Extract a word (3+ chars) from text that's suitable for OCR verification. */
    private String extractVerifyWord(String text) {
        if (text == null || text.isBlank()) return null;
        String[] words = text.trim().split("\\s+");
        // Prefer longer words — more reliable for OCR matching
        String best = null;
        for (String w : words) {
            if (w.length() >= 3 && (best == null || w.length() > best.length())) {
                best = w;
            }
        }
        return best;
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

    @Tool(description = "Find two elements on screen and drag one to the other. Takes a screenshot, "
            + "locates both the source and target via OCR/Textract/Gemini/AI vision, and performs the mouse drag. "
            + "Automatically hides the Mins Bot window during capture to avoid OCR interference. "
            + "Retries up to 5 times with fresh screenshots. ALWAYS use this tool for moving files on screen. "
            + "Use when the user says 'drag X into Y', 'move X into Y', 'put X in Y'. "
            + "Example: findAndDragElement('ANIMALS.txt', 'LIVING') drags the file into the folder.")
    public String findAndDragElement(
            @ToolParam(description = "Description of the source element to drag, e.g. 'ANIMALS.txt', 'my_file.txt'") String sourceDescription,
            @ToolParam(description = "Description of the target to drag onto, e.g. 'LIVING', 'TARGET folder'") String targetDescription) {
        notifier.notify("Dragging: " + sourceDescription + " → " + targetDescription);

        hideMinsBotWindow();
        try {
            int maxAttempts = 5;
            boolean dragAttempted = false;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                System.out.println("[drag] Attempt " + attempt + "/" + maxAttempts);

                if (attempt > 1) {
                    notifier.notify("Retry " + attempt + "/" + maxAttempts + ": " + sourceDescription + " → " + targetDescription);
                    // Wait before retry to let screen settle
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }

                // Before taking screenshot, check if user is actively moving mouse
                if (checkUserAbort()) {
                    return "Drag aborted: user mouse movement detected.";
                }

                // Take a fresh screenshot each attempt
                ScreenshotContext ctx = captureScreen();
                if (ctx == null) {
                    System.out.println("[drag] Screenshot failed on attempt " + attempt + " — retrying...");
                    continue;
                }

                ElementLocation source = locateOnImage(ctx, sourceDescription);
                if (source == null) {
                    if (dragAttempted) {
                        // Source not found after a drag was attempted — likely moved successfully
                        System.out.println("[drag] Source '" + sourceDescription + "' no longer found after drag — likely moved.");
                        return "Drag verified: '" + sourceDescription + "' is no longer visible on screen (moved successfully).";
                    }
                    System.out.println("[drag] Source '" + sourceDescription + "' not found on attempt " + attempt
                            + " — retrying with fresh screenshot...");
                    continue; // retry with a new screenshot
                }

                ElementLocation target = locateOnImage(ctx, targetDescription);
                if (target == null) {
                    System.out.println("[drag] Target '" + targetDescription + "' not found on attempt " + attempt
                            + " — retrying...");
                    continue; // retry with a new screenshot
                }

                System.out.println("[drag] Attempt " + attempt + ": dragging from (" + source.screenX() + "," + source.screenY()
                        + ") to (" + target.screenX() + "," + target.screenY() + ")");

                String dragResult = systemControl.mouseDrag(
                        source.screenX(), source.screenY(), target.screenX(), target.screenY());
                dragAttempted = true;

                // Check if user moved mouse during/after drag
                if (checkUserAbort(target.screenX(), target.screenY())) {
                    return "Drag aborted: user moved the mouse during drag.";
                }

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
                    continue;
                }
            }

            // All attempts exhausted
            String failureMsg = dragAttempted
                    ? "Dragged '" + sourceDescription + "' → '" + targetDescription
                        + "' but after " + maxAttempts + " attempts, the drag may not have succeeded."
                    : "Could not find '" + sourceDescription + "' or '" + targetDescription
                        + "' on screen after " + maxAttempts + " attempts with fresh screenshots.";
            return failureMsg;
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

            Path cropPath = cropAroundSource(verifyCtx, sourceScreenX, sourceScreenY);
            Path verifyImage = (cropPath != null) ? cropPath : verifyCtx.imagePath();

            // ── Phase 3: Gemini reasoning model (gemini-2.5-pro) ──
            if (geminiVisionService.isAvailable()) {
                System.out.println("[drag] Trying Gemini reasoning verify for '" + sourceDescription + "'...");
                String geminiResponse = geminiVisionService.verifyDrag(verifyImage, sourceDescription);
                if (geminiResponse != null && !geminiResponse.isBlank()) {
                    String geminiFirst = geminiResponse.split("\\r?\\n")[0].trim().toUpperCase();
                    System.out.println("[drag] Gemini verify: " + geminiResponse.replace("\n", " | "));

                    if (geminiFirst.startsWith("YES")) {
                        System.out.println("[drag] OCR + Gemini confirm source is gone → SUCCESS");
                        return DragVerifyResult.SUCCESS;
                    } else if (geminiFirst.startsWith("NO")) {
                        System.out.println("[drag] Gemini says source still visible → FAILED");
                        return DragVerifyResult.FAILED;
                    }
                }
            }

            // ── Phase 4: OpenAI gpt-4o fallback ──
            if (!visionService.isAvailable()) {
                System.out.println("[drag] No AI vision available — trusting OCR result → SUCCESS");
                return DragVerifyResult.SUCCESS;
            }

            String aiResponse = visionService.verifyDragStrong(verifyImage, sourceDescription);
            if (aiResponse == null || aiResponse.isBlank()) {
                System.out.println("[drag] OpenAI vision returned empty — trusting OCR result → SUCCESS");
                return DragVerifyResult.SUCCESS;
            }

            String firstLine = aiResponse.split("\\r?\\n")[0].trim().toUpperCase();
            System.out.println("[drag] OpenAI vision (strong): " + aiResponse.replace("\n", " | "));

            if (firstLine.startsWith("YES")) {
                System.out.println("[drag] OCR + OpenAI confirm source is gone → SUCCESS");
                return DragVerifyResult.SUCCESS;
            } else if (firstLine.startsWith("NO")) {
                System.out.println("[drag] OpenAI says source still visible → FAILED");
                return DragVerifyResult.FAILED;
            }

            // AI returned something ambiguous — trust OCR
            System.out.println("[drag] AI ambiguous — trusting OCR result → SUCCESS");
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
