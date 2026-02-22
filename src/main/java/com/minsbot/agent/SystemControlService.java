package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controls Windows processes, applications, and the desktop.
 */
@Service
public class SystemControlService {

    private static final Logger log = LoggerFactory.getLogger(SystemControlService.class);

    // Processes to never kill (Mins Bot + critical system)
    private static final Set<String> PROTECTED = Set.of(
            "java.exe", "javaw.exe",          // Mins Bot itself
            "explorer.exe",                     // Windows shell
            "csrss.exe", "wininit.exe", "winlogon.exe", "smss.exe",
            "services.exe", "lsass.exe", "svchost.exe", "dwm.exe",
            "conhost.exe", "system", "registry", "idle",
            "taskhostw.exe", "sihost.exe", "fontdrvhost.exe",
            "searchhost.exe", "startmenuexperiencehost.exe",
            "shellexperiencehost.exe", "runtimebroker.exe",
            "textinputhost.exe", "ctfmon.exe", "dllhost.exe",
            "securityhealthservice.exe", "securityhealthsystray.exe",
            "msmpeng.exe", "nissrv.exe"         // Windows Defender
    );

    // Common app names mapped to their process names
    private static final Map<String, List<String>> APP_PROCESS_MAP = new LinkedHashMap<>();

    static {
        APP_PROCESS_MAP.put("chrome", List.of("chrome.exe"));
        APP_PROCESS_MAP.put("google chrome", List.of("chrome.exe"));
        APP_PROCESS_MAP.put("firefox", List.of("firefox.exe"));
        APP_PROCESS_MAP.put("edge", List.of("msedge.exe"));
        APP_PROCESS_MAP.put("microsoft edge", List.of("msedge.exe"));
        APP_PROCESS_MAP.put("brave", List.of("brave.exe"));
        APP_PROCESS_MAP.put("opera", List.of("opera.exe"));
        APP_PROCESS_MAP.put("notepad", List.of("notepad.exe", "notepad++.exe"));
        APP_PROCESS_MAP.put("vscode", List.of("code.exe"));
        APP_PROCESS_MAP.put("visual studio code", List.of("code.exe"));
        APP_PROCESS_MAP.put("word", List.of("winword.exe"));
        APP_PROCESS_MAP.put("excel", List.of("excel.exe"));
        APP_PROCESS_MAP.put("powerpoint", List.of("powerpnt.exe"));
        APP_PROCESS_MAP.put("outlook", List.of("outlook.exe"));
        APP_PROCESS_MAP.put("teams", List.of("ms-teams.exe", "teams.exe"));
        APP_PROCESS_MAP.put("discord", List.of("discord.exe"));
        APP_PROCESS_MAP.put("slack", List.of("slack.exe"));
        APP_PROCESS_MAP.put("spotify", List.of("spotify.exe"));
        APP_PROCESS_MAP.put("telegram", List.of("telegram.exe"));
        APP_PROCESS_MAP.put("steam", List.of("steam.exe"));
        APP_PROCESS_MAP.put("vlc", List.of("vlc.exe"));
        APP_PROCESS_MAP.put("obs", List.of("obs64.exe", "obs32.exe"));
        APP_PROCESS_MAP.put("eclipse", List.of("eclipse.exe", "eclipsec.exe"));
        APP_PROCESS_MAP.put("intellij", List.of("idea64.exe", "idea.exe"));
        APP_PROCESS_MAP.put("cursor", List.of("cursor.exe"));
        APP_PROCESS_MAP.put("file explorer", List.of("explorer.exe"));
        APP_PROCESS_MAP.put("task manager", List.of("taskmgr.exe"));
        APP_PROCESS_MAP.put("calculator", List.of("calculator.exe", "calc.exe"));
        APP_PROCESS_MAP.put("paint", List.of("mspaint.exe"));
        APP_PROCESS_MAP.put("terminal", List.of("windowsterminal.exe", "wt.exe"));
        APP_PROCESS_MAP.put("powershell", List.of("powershell.exe", "pwsh.exe"));
        APP_PROCESS_MAP.put("cmd", List.of("cmd.exe"));
    }

    /**
     * Close all user application windows except Mins Bot and system-critical processes.
     */
    public String closeAllWindows() {
        List<ProcessInfo> running = getRunningUserProcesses();
        if (running.isEmpty()) {
            return "No user applications to close.";
        }

        int closed = 0;
        int failed = 0;
        List<String> closedNames = new ArrayList<>();

        for (ProcessInfo proc : running) {
            try {
                // Graceful close first via taskkill (no /F)
                Process p = new ProcessBuilder("taskkill", "/PID", String.valueOf(proc.pid))
                        .redirectErrorStream(true).start();
                p.waitFor();
                if (p.exitValue() == 0) {
                    closed++;
                    closedNames.add(proc.name);
                } else {
                    // Force kill as fallback
                    Process pf = new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(proc.pid))
                            .redirectErrorStream(true).start();
                    pf.waitFor();
                    if (pf.exitValue() == 0) {
                        closed++;
                        closedNames.add(proc.name + " (forced)");
                    } else {
                        failed++;
                    }
                }
            } catch (Exception e) {
                failed++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Closed ").append(closed).append(" application(s)");
        if (failed > 0) sb.append(", ").append(failed).append(" failed");
        sb.append(".\n");
        if (!closedNames.isEmpty()) {
            sb.append("Closed: ").append(String.join(", ", closedNames));
        }
        return sb.toString();
    }

    /**
     * Close a specific application by name.
     */
    public String closeApp(String appName) {
        String lower = appName.toLowerCase().trim();

        // Check known app map
        List<String> processNames = null;
        for (Map.Entry<String, List<String>> entry : APP_PROCESS_MAP.entrySet()) {
            if (lower.contains(entry.getKey())) {
                processNames = entry.getValue();
                break;
            }
        }

        if (processNames == null) {
            // Try direct process name
            String guess = lower.replaceAll("\\s+", "") + ".exe";
            processNames = List.of(guess);
        }

        int closed = 0;
        for (String proc : processNames) {
            if (PROTECTED.contains(proc.toLowerCase())) continue;
            try {
                Process p = new ProcessBuilder("taskkill", "/IM", proc, "/F")
                        .redirectErrorStream(true).start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor();
                if (p.exitValue() == 0) {
                    closed++;
                }
            } catch (Exception e) {
                // try next
            }
        }

        if (closed > 0) {
            return "Closed " + appName + ".";
        }
        return appName + " is not running or could not be closed.";
    }

    /**
     * Open an application by name.
     */
    public String openApp(String appName) {
        String lower = appName.toLowerCase().trim();

        // Common app launch commands — more specific entries first to avoid partial matches
        // (e.g. "notepad++" must come before "notepad")
        Map<String, String[]> launchCommands = new LinkedHashMap<>();
        launchCommands.put("google chrome", new String[]{"cmd", "/c", "start", "chrome"});
        launchCommands.put("chrome", new String[]{"cmd", "/c", "start", "chrome"});
        launchCommands.put("firefox", new String[]{"cmd", "/c", "start", "firefox"});
        launchCommands.put("microsoft edge", new String[]{"cmd", "/c", "start", "msedge:"});
        launchCommands.put("edge", new String[]{"cmd", "/c", "start", "msedge:"});
        launchCommands.put("brave", new String[]{"cmd", "/c", "start", "brave"});
        launchCommands.put("notepad++", new String[]{"cmd", "/c", "start", "", "notepad++"});
        launchCommands.put("notepad", new String[]{"notepad.exe"});
        launchCommands.put("calculator", new String[]{"calc.exe"});
        launchCommands.put("paint", new String[]{"mspaint.exe"});
        launchCommands.put("file explorer", new String[]{"explorer.exe"});
        launchCommands.put("explorer", new String[]{"explorer.exe"});
        launchCommands.put("windows terminal", new String[]{"cmd", "/c", "start", "", "wt.exe"});
        launchCommands.put("terminal", new String[]{"cmd", "/c", "start", "", "wt.exe"});
        launchCommands.put("command prompt", new String[]{"cmd", "/c", "start", "", "cmd.exe"});
        launchCommands.put("cmd", new String[]{"cmd", "/c", "start", "", "cmd.exe"});
        launchCommands.put("powershell", new String[]{"cmd", "/c", "start", "", "powershell.exe"});
        launchCommands.put("task manager", new String[]{"taskmgr.exe"});
        launchCommands.put("settings", new String[]{"cmd", "/c", "start", "ms-settings:"});
        launchCommands.put("control panel", new String[]{"control.exe"});
        launchCommands.put("spotify", new String[]{"cmd", "/c", "start", "spotify:"});
        launchCommands.put("discord", new String[]{"cmd", "/c", "start", "", "discord"});
        launchCommands.put("slack", new String[]{"cmd", "/c", "start", "", "slack"});
        launchCommands.put("teams", new String[]{"cmd", "/c", "start", "", "ms-teams"});
        launchCommands.put("vscode", new String[]{"cmd", "/c", "start", "", "code"});
        launchCommands.put("visual studio code", new String[]{"cmd", "/c", "start", "", "code"});
        launchCommands.put("eclipse", new String[]{"cmd", "/c", "start", "", "eclipse"});
        launchCommands.put("cursor", new String[]{"cmd", "/c", "start", "", "cursor"});
        launchCommands.put("word", new String[]{"cmd", "/c", "start", "", "winword"});
        launchCommands.put("excel", new String[]{"cmd", "/c", "start", "", "excel"});
        launchCommands.put("powerpoint", new String[]{"cmd", "/c", "start", "", "powerpnt"});
        launchCommands.put("outlook", new String[]{"cmd", "/c", "start", "", "outlook"});

        // Match longest key first to avoid partial matches
        String matchedKey = null;
        for (String key : launchCommands.keySet()) {
            if (lower.contains(key)) {
                if (matchedKey == null || key.length() > matchedKey.length()) {
                    matchedKey = key;
                }
            }
        }
        if (matchedKey != null) {
            try {
                new ProcessBuilder(launchCommands.get(matchedKey)).start();
                return "Opened " + matchedKey + ".";
            } catch (Exception e) {
                return "Failed to open " + matchedKey + ": " + e.getMessage();
            }
        }

        // Try generic "start" for anything else
        try {
            new ProcessBuilder("cmd", "/c", "start", "", appName).start();
            return "Trying to open " + appName + "...";
        } catch (Exception e) {
            return "Could not open " + appName + ": " + e.getMessage();
        }
    }

    /**
     * Open a URL in the default browser.
     */
    public String openUrl(String url) {
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                new ProcessBuilder("cmd", "/c", "start", "", url).start();
            }
            return "Opened " + url + " in your browser.";
        } catch (Exception e) {
            return "Failed to open URL: " + e.getMessage();
        }
    }

    /**
     * Minimize all windows (show desktop).
     */
    public String minimizeAll() {
        try {
            // PowerShell: send Win+D to show desktop
            new ProcessBuilder("powershell", "-Command",
                    "(New-Object -ComObject Shell.Application).MinimizeAll()")
                    .start();
            return "Minimized all windows.";
        } catch (Exception e) {
            return "Failed to minimize: " + e.getMessage();
        }
    }

    /**
     * Lock the workstation.
     */
    public String lockScreen() {
        try {
            new ProcessBuilder("rundll32.exe", "user32.dll,LockWorkStation").start();
            return "Screen locked.";
        } catch (Exception e) {
            return "Failed to lock: " + e.getMessage();
        }
    }

    /**
     * List running user applications (non-system).
     */
    public String listRunningApps() {
        List<ProcessInfo> procs = getRunningUserProcesses();
        if (procs.isEmpty()) {
            return "No user applications currently running.";
        }

        // Group by name and count instances
        Map<String, Long> grouped = procs.stream()
                .collect(Collectors.groupingBy(p -> p.name, LinkedHashMap::new, Collectors.counting()));

        StringBuilder sb = new StringBuilder("Running applications (" + procs.size() + " processes):\n");
        for (Map.Entry<String, Long> entry : grouped.entrySet()) {
            sb.append("  ").append(entry.getKey());
            if (entry.getValue() > 1) sb.append(" (x").append(entry.getValue()).append(")");
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Take a screenshot (immediate, separate from the timed ScreenshotService).
     */
    public String takeScreenshot() {
        try {
            java.awt.Rectangle screenRect = new java.awt.Rectangle(
                    java.awt.Toolkit.getDefaultToolkit().getScreenSize());
            java.awt.image.BufferedImage image = new java.awt.Robot().createScreenCapture(screenRect);
            java.nio.file.Path dir = java.nio.file.Paths.get(
                    System.getProperty("user.home"), "mins_bot_data", "screenshots");
            java.nio.file.Files.createDirectories(dir);
            String filename = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                    + "_manual.png";
            java.nio.file.Path file = dir.resolve(filename);
            javax.imageio.ImageIO.write(image, "png", file.toFile());
            return "Screenshot saved: " + file.toAbsolutePath();
        } catch (Exception e) {
            return "Screenshot failed: " + e.getMessage();
        }
    }

    /**
     * Run an arbitrary PowerShell command and return output.
     */
    public String runPowerShell(String command) {
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command", command)
                    .redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            p.waitFor();
            if (output.length() > 2000) {
                output = output.substring(0, 2000) + "\n... (truncated)";
            }
            return output.isBlank() ? "Command completed (no output)." : output;
        } catch (Exception e) {
            return "PowerShell error: " + e.getMessage();
        }
    }

    /**
     * List open windows (processes that have a visible window with a title).
     * Returns process name, PID, and window title for each.
     */
    public String listOpenWindows() {
        String ps = "Get-Process | Where-Object { $_.MainWindowTitle -ne '' } | " +
                "Select-Object -Property ProcessName, Id, MainWindowTitle | " +
                "Format-Table -AutoSize -HideTableHeaders | Out-String -Width 200";
        return runPowerShell(ps);
    }

    /**
     * Bring a window to the foreground by searching for its title or process name.
     * Uses the first matching window (case-insensitive partial match).
     * Employs AttachThreadInput trick to bypass Windows' SetForegroundWindow restrictions.
     */
    public String focusWindow(String search) {
        if (search == null || search.isBlank()) {
            return "Provide a window title or process name to focus (e.g. 'notepad', 'chrome').";
        }
        String safe = search.trim().replace("'", "''");
        String ps = "Add-Type -TypeDefinition @\"\n" +
                "using System; using System.Runtime.InteropServices;\n" +
                "public class Win32Focus {\n" +
                "  [DllImport(\\\"user32.dll\\\")] public static extern bool SetForegroundWindow(IntPtr hwnd);\n" +
                "  [DllImport(\\\"user32.dll\\\")] public static extern bool ShowWindow(IntPtr hwnd, int cmd);\n" +
                "  [DllImport(\\\"user32.dll\\\")] public static extern bool IsIconic(IntPtr hwnd);\n" +
                "  [DllImport(\\\"user32.dll\\\")] public static extern IntPtr GetForegroundWindow();\n" +
                "  [DllImport(\\\"user32.dll\\\")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);\n" +
                "  [DllImport(\\\"kernel32.dll\\\")] public static extern uint GetCurrentThreadId();\n" +
                "  [DllImport(\\\"user32.dll\\\")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);\n" +
                "  public static void ForceForeground(IntPtr hwnd) {\n" +
                "    if (IsIconic(hwnd)) ShowWindow(hwnd, 9);\n" + // SW_RESTORE
                "    IntPtr fg = GetForegroundWindow();\n" +
                "    uint fgPid = 0;\n" +
                "    uint fgTid = GetWindowThreadProcessId(fg, out fgPid);\n" +
                "    uint ourTid = GetCurrentThreadId();\n" +
                "    if (fgTid != ourTid) AttachThreadInput(ourTid, fgTid, true);\n" +
                "    SetForegroundWindow(hwnd);\n" +
                "    ShowWindow(hwnd, 5);\n" + // SW_SHOW
                "    if (fgTid != ourTid) AttachThreadInput(ourTid, fgTid, false);\n" +
                "  }\n" +
                "}\n" +
                "\"@ -ErrorAction SilentlyContinue; " +
                "$procs = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and " +
                "($_.MainWindowTitle -like '*" + safe + "*' -or $_.ProcessName -like '*" + safe + "*') }; " +
                "if ($procs) { [Win32Focus]::ForceForeground($procs[0].MainWindowHandle); 'Focused: ' + $procs[0].ProcessName + ' - ' + $procs[0].MainWindowTitle } " +
                "else { 'No window found matching: " + safe.replace("'", "''") + "' }";
        return runPowerShell(ps);
    }

    /**
     * Send keystrokes to the currently focused window. Use for shortcuts or typing.
     * Special keys: + (Shift), ^ (Ctrl), % (Alt), {ENTER}, {TAB}, {ESC}, {DOWN}, {UP}, etc.
     * Example: sendKeys("^v") = Ctrl+V (paste), sendKeys("Hello{ENTER}") = type Hello and Enter.
     */
    public String sendKeys(String keys) {
        if (keys == null) keys = "";
        String escaped = keys.replace("'", "''").replace("`", "``").replace("\"", "`\"");
        String ps = "$wshell = New-Object -ComObject WScript.Shell; $wshell.SendKeys('" + escaped + "')";
        runPowerShell(ps);
        return "Sent keystrokes to the focused window.";
    }

    /**
     * Open an application with optional arguments (e.g. a file path or URL).
     * Example: openAppWithArgs("notepad", "C:\\file.txt") or openAppWithArgs("chrome", "https://example.com").
     */
    public String openAppWithArgs(String appName, String args) {
        String lower = (appName != null ? appName : "").toLowerCase().trim();
        String argStr = (args != null ? args.trim() : "");
        try {
            if (lower.contains("notepad") && !argStr.isEmpty()) {
                new ProcessBuilder("notepad.exe", argStr).start();
                return "Opened Notepad with: " + argStr;
            }
            if ((lower.contains("chrome") || lower.contains("edge") || lower.contains("firefox")) && !argStr.isEmpty()) {
                if (!argStr.startsWith("http://") && !argStr.startsWith("https://") && !argStr.contains("."))
                    argStr = "https://" + argStr;
                new ProcessBuilder("cmd", "/c", "start", "", argStr).start();
                return "Opened URL in browser: " + argStr;
            }
            if (lower.contains("explorer") || lower.contains("file explorer")) {
                new ProcessBuilder("explorer.exe", argStr.isEmpty() ? "." : argStr).start();
                return "Opened Explorer" + (argStr.isEmpty() ? "." : ": " + argStr);
            }
            // Generic: start app with args
            new ProcessBuilder("cmd", "/c", "start", "", appName, argStr).start();
            return "Started " + appName + (argStr.isEmpty() ? "" : " with: " + argStr);
        } catch (Exception e) {
            return "Failed to open: " + e.getMessage();
        }
    }

    /**
     * Run an arbitrary CMD command and return output.
     */
    public String runCmd(String command) {
        try {
            Process p = new ProcessBuilder("cmd", "/c", command)
                    .redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            p.waitFor();
            if (output.length() > 2000) {
                output = output.substring(0, 2000) + "\n... (truncated)";
            }
            return output.isBlank() ? "Command completed (no output)." : output;
        } catch (Exception e) {
            return "CMD error: " + e.getMessage();
        }
    }

    // ── Mouse control ──

    /**
     * Move the mouse cursor to the given screen coordinates.
     */
    public String mouseMove(int x, int y) {
        try {
            Robot robot = new Robot();
            robot.mouseMove(x, y);
            return "Moved mouse to (" + x + ", " + y + ").";
        } catch (Exception e) {
            return "Mouse move failed: " + e.getMessage();
        }
    }

    /**
     * Click the mouse at the given screen coordinates.
     * button: "left" (default), "right", or "middle".
     */
    public String mouseClick(int x, int y, String button) {
        try {
            Robot robot = new Robot();
            robot.mouseMove(x, y);
            robot.delay(50);
            int mask = switch (button != null ? button.toLowerCase() : "left") {
                case "right" -> InputEvent.BUTTON3_DOWN_MASK;
                case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
                default -> InputEvent.BUTTON1_DOWN_MASK;
            };
            robot.mousePress(mask);
            robot.delay(30);
            robot.mouseRelease(mask);
            return "Clicked " + (button != null ? button : "left") + " at (" + x + ", " + y + ").";
        } catch (Exception e) {
            return "Mouse click failed: " + e.getMessage();
        }
    }

    /**
     * Double-click the mouse at the given screen coordinates.
     */
    public String mouseDoubleClick(int x, int y) {
        try {
            Robot robot = new Robot();
            robot.mouseMove(x, y);
            robot.delay(50);
            int mask = InputEvent.BUTTON1_DOWN_MASK;
            robot.mousePress(mask);
            robot.delay(30);
            robot.mouseRelease(mask);
            robot.delay(80);
            robot.mousePress(mask);
            robot.delay(30);
            robot.mouseRelease(mask);
            return "Double-clicked at (" + x + ", " + y + ").";
        } catch (Exception e) {
            return "Double-click failed: " + e.getMessage();
        }
    }

    /**
     * Drag the mouse from one point to another (left button).
     */
    public String mouseDrag(int fromX, int fromY, int toX, int toY) {
        try {
            Robot robot = new Robot();
            robot.mouseMove(fromX, fromY);
            robot.delay(50);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(50);
            // Smooth drag in steps
            int steps = 20;
            for (int i = 1; i <= steps; i++) {
                int cx = fromX + (toX - fromX) * i / steps;
                int cy = fromY + (toY - fromY) * i / steps;
                robot.mouseMove(cx, cy);
                robot.delay(10);
            }
            robot.delay(50);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            return "Dragged from (" + fromX + "," + fromY + ") to (" + toX + "," + toY + ").";
        } catch (Exception e) {
            return "Mouse drag failed: " + e.getMessage();
        }
    }

    /**
     * Scroll the mouse wheel at the current position.
     * Positive = scroll down, negative = scroll up.
     */
    public String mouseScroll(int amount) {
        try {
            Robot robot = new Robot();
            robot.mouseWheel(amount);
            return "Scrolled " + (amount > 0 ? "down" : "up") + " by " + Math.abs(amount) + " notches.";
        } catch (Exception e) {
            return "Mouse scroll failed: " + e.getMessage();
        }
    }

    /**
     * Get current mouse position.
     */
    public String getMousePosition() {
        java.awt.Point p = java.awt.MouseInfo.getPointerInfo().getLocation();
        return "Mouse is at (" + p.x + ", " + p.y + ").";
    }

    /**
     * Get screen resolution.
     */
    public String getScreenSize() {
        java.awt.Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        return "Screen resolution: " + d.width + "x" + d.height + ".";
    }

    // ── Browser keyboard shortcuts ──

    /**
     * Navigate the PC browser to a URL by focusing it, hitting Ctrl+L, typing the URL, and pressing Enter.
     */
    public String browserNavigate(String browserName, String url) {
        focusWindow(browserName != null ? browserName : "chrome");
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        sendKeys("^l"); // Focus address bar
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        sendKeys(url + "{ENTER}");
        return "Navigated " + (browserName != null ? browserName : "browser") + " to: " + url;
    }

    /**
     * Open a new tab in the PC browser.
     */
    public String browserNewTab(String browserName) {
        focusWindow(browserName != null ? browserName : "chrome");
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        sendKeys("^t");
        return "Opened new tab in " + (browserName != null ? browserName : "browser") + ".";
    }

    /**
     * Close the current tab in the PC browser.
     */
    public String browserCloseTab(String browserName) {
        focusWindow(browserName != null ? browserName : "chrome");
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        sendKeys("^w");
        return "Closed current tab.";
    }

    /**
     * Switch to the next/previous tab in the PC browser.
     */
    public String browserSwitchTab(String browserName, String direction) {
        focusWindow(browserName != null ? browserName : "chrome");
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        if ("previous".equalsIgnoreCase(direction) || "prev".equalsIgnoreCase(direction)
                || "left".equalsIgnoreCase(direction)) {
            sendKeys("^+{TAB}");
        } else {
            sendKeys("^{TAB}");
        }
        return "Switched to " + (direction != null ? direction : "next") + " tab.";
    }

    /**
     * Refresh the current page in the PC browser.
     */
    public String browserRefresh(String browserName) {
        focusWindow(browserName != null ? browserName : "chrome");
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        sendKeys("{F5}");
        return "Refreshed browser page.";
    }

    /**
     * Go back in the PC browser.
     */
    public String browserBack(String browserName) {
        focusWindow(browserName != null ? browserName : "chrome");
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        sendKeys("%{LEFT}");
        return "Went back in browser.";
    }

    /**
     * Go forward in the PC browser.
     */
    public String browserForward(String browserName) {
        focusWindow(browserName != null ? browserName : "chrome");
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        sendKeys("%{RIGHT}");
        return "Went forward in browser.";
    }

    // ── Internals ──

    private List<ProcessInfo> getRunningUserProcesses() {
        List<ProcessInfo> result = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH")
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Format: "name.exe","PID","Session","Session#","MemUsage"
                    String[] parts = line.split("\",\"");
                    if (parts.length < 2) continue;
                    String name = parts[0].replace("\"", "").trim();
                    String pidStr = parts[1].replace("\"", "").trim();
                    if (name.isEmpty()) continue;

                    // Skip protected / system processes
                    if (PROTECTED.contains(name.toLowerCase())) continue;

                    try {
                        long pid = Long.parseLong(pidStr);
                        // Skip PID 0 and 4 (System)
                        if (pid <= 4) continue;
                        result.add(new ProcessInfo(name, pid));
                    } catch (NumberFormatException e) {
                        // skip
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log.error("Failed to list processes", e);
        }
        return result;
    }

    private record ProcessInfo(String name, long pid) {}
}
