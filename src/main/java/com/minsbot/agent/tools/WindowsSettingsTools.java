package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Windows system-settings tools. Everything here uses Windows built-ins
 * (PowerShell, registry, netsh, powercfg, rundll32, shutdown) — no extra
 * modules to install.
 */
@Component
public class WindowsSettingsTools {

    private static final Logger log = LoggerFactory.getLogger(WindowsSettingsTools.class);
    private final ToolExecutionNotifier notifier;

    public WindowsSettingsTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    // ═════════════════════════════════════════════════════════════════
    //  Display
    // ═════════════════════════════════════════════════════════════════

    @Tool(description = "Set the laptop/monitor brightness to an exact percentage (0-100). "
            + "Use when the user says 'set brightness to 50', 'dim the screen', 'brighter', 'max brightness', 'brightness to 70%'. "
            + "Works on laptops and monitors that support WMI brightness control.")
    public String setBrightness(
            @ToolParam(description = "Brightness percentage 0-100") double percent) {
        int p = clamp((int) Math.round(percent), 0, 100);
        notifier.notify("Setting brightness to " + p + "%...");
        PsResult r = runPowerShell(
                "(Get-WmiObject -Namespace root/WMI -Class WmiMonitorBrightnessMethods -ErrorAction Stop)"
                + ".WmiSetBrightness(0," + p + ")", 5);
        if (r.ok) return "Brightness set to " + p + "%.";
        return "Brightness change failed. Your display may not support WMI brightness "
                + "(common with external desktop monitors). Try the monitor's own buttons.";
    }

    @Tool(description = "Switch Windows between dark mode and light mode. "
            + "Use when the user says 'switch to dark mode', 'enable light mode', 'dark theme', 'light theme'.")
    public String setDarkMode(
            @ToolParam(description = "true for dark mode, false for light mode") boolean dark) {
        int val = dark ? 0 : 1;  // AppsUseLightTheme: 0=dark, 1=light
        notifier.notify((dark ? "Enabling" : "Disabling") + " dark mode...");
        String key = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
        PsResult r = runPowerShell(
                "Set-ItemProperty -Path '" + key + "' -Name AppsUseLightTheme -Value " + val + ";"
                + "Set-ItemProperty -Path '" + key + "' -Name SystemUsesLightTheme -Value " + val, 4);
        return r.ok ? (dark ? "Switched to dark mode." : "Switched to light mode.")
                : "Failed to change theme: " + r.err;
    }

    @Tool(description = "Open the Windows Night Light / blue filter settings page. "
            + "Use when the user says 'turn on night light', 'blue filter', 'night mode'. "
            + "Opens ms-settings:nightlight so the user can toggle it — direct toggling is "
            + "blocked by Windows on most builds without admin / third-party tools.")
    public String openNightLightSettings() {
        try {
            new ProcessBuilder("cmd", "/c", "start", "", "ms-settings:nightlight").start();
            return "Opened Night Light settings.";
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Audio — mic mute + output device switch (via settings page)
    // ═════════════════════════════════════════════════════════════════

    @Tool(description = "Mute or unmute the default microphone system-wide. "
            + "Use when the user says 'mute my mic', 'unmute mic', 'mic off', 'mic on', 'turn off microphone'.")
    public String setMicMute(
            @ToolParam(description = "true to mute, false to unmute") boolean muted) {
        notifier.notify("Setting mic " + (muted ? "muted" : "unmuted") + "...");
        String script =
            "$ErrorActionPreference='Stop';" +
            "Add-Type -Language CSharp @'\n" +
            "using System;using System.Runtime.InteropServices;\n" +
            "[Guid(\"5CDF2C82-841E-4546-9722-0CF74078229A\"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n" +
            "public interface IAudioEndpointVolume{\n" +
            "  int f();int g();int h();int i();int j();int k();int l();int m();\n" +
            "  int SetMute([MarshalAs(UnmanagedType.Bool)]bool b,Guid ctx);\n" +
            "  int GetMute([MarshalAs(UnmanagedType.Bool)]out bool b);\n" +
            "}\n" +
            "[Guid(\"A95664D2-9614-4F35-A746-DE8DB63617E6\"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n" +
            "public interface IMMDeviceEnumerator{\n" +
            "  int f();int GetDefaultAudioEndpoint(int d,int r,out IMMDevice e);\n" +
            "}\n" +
            "[Guid(\"D666063F-1587-4E43-81F1-B948E807363F\"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n" +
            "public interface IMMDevice{\n" +
            "  int Activate(ref Guid id,int clsCtx,int ap,[MarshalAs(UnmanagedType.IUnknown)]out object o);\n" +
            "}\n" +
            "[ComImport,Guid(\"BCDE0395-E52F-467C-8E3D-C4579291692E\")] public class MMDE{}\n" +
            "public class Mic{\n" +
            "  public static void SetMute(bool m){\n" +
            "    var e=(IMMDeviceEnumerator)(new MMDE());\n" +
            "    IMMDevice d=null; Marshal.ThrowExceptionForHR(e.GetDefaultAudioEndpoint(1,0,out d));\n" +
            "    Guid g=typeof(IAudioEndpointVolume).GUID; object o;\n" +
            "    Marshal.ThrowExceptionForHR(d.Activate(ref g,0x17,0,out o));\n" +
            "    Marshal.ThrowExceptionForHR(((IAudioEndpointVolume)o).SetMute(m,Guid.Empty));\n" +
            "  }\n" +
            "}\n" +
            "'@ -ReferencedAssemblies System.Runtime,System.Private.CoreLib;" +
            "[Mic]::SetMute($" + (muted ? "true" : "false") + ")";
        PsResult r = runPowerShell(script, 6);
        return r.ok ? ("Microphone " + (muted ? "muted." : "unmuted."))
                : "Failed to change mic mute: " + truncate(r.err, 200);
    }

    @Tool(description = "Open the Windows Sound settings so the user can pick a different audio output device. "
            + "Use when the user says 'switch to headphones', 'switch to speakers', 'change audio output'. "
            + "Direct device switching needs an external module — this opens the UI panel.")
    public String openSoundSettings() {
        try {
            new ProcessBuilder("cmd", "/c", "start", "", "ms-settings:sound").start();
            return "Opened Sound settings — pick the output device you want from the list.";
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Network — Wi-Fi + Bluetooth
    // ═════════════════════════════════════════════════════════════════

    @Tool(description = "Turn Wi-Fi on or off by disabling/enabling the 'Wi-Fi' network interface. "
            + "Use when the user says 'turn off wifi', 'enable wifi', 'wifi on', 'wifi off', 'disconnect wifi'.")
    public String setWifi(
            @ToolParam(description = "true to enable, false to disable") boolean enabled) {
        notifier.notify((enabled ? "Enabling" : "Disabling") + " Wi-Fi...");
        String admin = enabled ? "enable" : "disable";
        PsResult r = runPowerShell(
                "netsh interface set interface name=\"Wi-Fi\" admin=" + admin, 5);
        return r.ok ? "Wi-Fi " + (enabled ? "enabled." : "disabled.")
                : "Failed — Wi-Fi control usually needs admin rights. " + truncate(r.err, 120);
    }

    @Tool(description = "Turn Bluetooth on or off using the Windows Runtime Radio API. "
            + "Use when the user says 'turn on bluetooth', 'disable bluetooth', 'bluetooth off/on'.")
    public String setBluetooth(
            @ToolParam(description = "true to enable, false to disable") boolean enabled) {
        notifier.notify((enabled ? "Enabling" : "Disabling") + " Bluetooth...");
        String state = enabled ? "On" : "Off";
        String script =
            "Add-Type -AssemblyName System.Runtime.WindowsRuntime;" +
            "$at=([System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0];" +
            "function W($t,$T){$a=$at.MakeGenericMethod($T);$n=$a.Invoke($null,@($t));$n.Wait(-1)|Out-Null;$n.Result};" +
            "[Windows.Devices.Radios.Radio,Windows.System.Devices,ContentType=WindowsRuntime]|Out-Null;" +
            "[Windows.Devices.Radios.RadioAccessStatus,Windows.System.Devices,ContentType=WindowsRuntime]|Out-Null;" +
            "W ([Windows.Devices.Radios.Radio]::RequestAccessAsync()) ([Windows.Devices.Radios.RadioAccessStatus])|Out-Null;" +
            "$rs=W ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) ([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]]);" +
            "$bt=$rs|?{$_.Kind -eq 'Bluetooth'}|Select-Object -First 1;" +
            "if($bt){W ($bt.SetStateAsync('" + state + "')) ([Windows.Devices.Radios.RadioAccessStatus])|Out-Null; 'OK'} else { throw 'No Bluetooth radio found' }";
        PsResult r = runPowerShell(script, 10);
        return r.ok ? "Bluetooth " + (enabled ? "enabled." : "disabled.")
                : "Failed: " + truncate(r.err, 200);
    }

    // ═════════════════════════════════════════════════════════════════
    //  Power plan
    // ═════════════════════════════════════════════════════════════════

    @Tool(description = "Switch the Windows power plan. Options: 'balanced', 'performance', 'powersaver'. "
            + "Use when the user says 'switch to performance mode', 'battery saver', 'balanced power plan', "
            + "'high performance', 'max performance'.")
    public String setPowerPlan(
            @ToolParam(description = "'balanced', 'performance', or 'powersaver'") String plan) {
        if (plan == null || plan.isBlank()) return "Please specify a plan (balanced / performance / powersaver).";
        String p = plan.trim().toLowerCase();
        String guid;
        String label;
        switch (p) {
            case "balanced":
                guid = "381b4222-f694-41f0-9685-ff5bb260df2e"; label = "Balanced"; break;
            case "performance":
            case "high-performance":
            case "high performance":
            case "max":
            case "max performance":
                guid = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c"; label = "High performance"; break;
            case "powersaver":
            case "power saver":
            case "battery saver":
            case "eco":
                guid = "a1841308-3541-4fab-bc81-f71556f20b4a"; label = "Power saver"; break;
            default:
                return "Unknown plan '" + plan + "'. Use: balanced, performance, or powersaver.";
        }
        notifier.notify("Switching power plan to " + label);
        try {
            Process proc = new ProcessBuilder("powercfg", "/setactive", guid).start();
            boolean done = proc.waitFor(4, TimeUnit.SECONDS);
            if (done && proc.exitValue() == 0) return "Power plan set to " + label + ".";
            return "powercfg exit " + (done ? proc.exitValue() : "timeout")
                    + ". That plan may not exist on this machine. "
                    + "Run `powercfg /list` to see available plan GUIDs.";
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Power actions — lock / sleep / shutdown / restart
    // ═════════════════════════════════════════════════════════════════

    @Tool(description = "Lock the Windows session (sends the user to the lock screen). "
            + "Use when the user says 'lock the screen', 'lock my computer', 'lock windows'.")
    public String lockScreen() {
        notifier.notify("Locking screen...");
        try {
            new ProcessBuilder("rundll32", "user32.dll,LockWorkStation").start();
            return "Screen locked.";
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Put the computer to sleep. Use when the user says 'sleep', 'sleep now', 'put the computer to sleep'.")
    public String sleepNow() {
        notifier.notify("Sleeping...");
        try {
            new ProcessBuilder("rundll32", "powrprof.dll,SetSuspendState", "0,1,0").start();
            return "Sleep requested.";
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Schedule a Windows shutdown (or cancel an already-scheduled one). "
            + "Use when the user says 'shut down in 5 minutes', 'shutdown now', 'cancel shutdown'. "
            + "Pass -1 to cancel a pending shutdown, 0 for immediate, or any positive number of minutes.")
    public String scheduleShutdown(
            @ToolParam(description = "Delay in minutes (0 = now, -1 = cancel)") int minutes) {
        try {
            ProcessBuilder pb;
            if (minutes < 0) {
                pb = new ProcessBuilder("shutdown", "/a");
            } else {
                pb = new ProcessBuilder("shutdown", "/s", "/t", String.valueOf(Math.max(0, minutes) * 60));
            }
            pb.start().waitFor(3, TimeUnit.SECONDS);
            if (minutes < 0) return "Cancelled pending shutdown.";
            if (minutes == 0) return "Shutting down now...";
            return "Shutdown scheduled in " + minutes + " minute(s). Say 'cancel shutdown' to abort.";
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Restart the computer. Use when the user says 'restart', 'reboot', 'restart my pc'.")
    public String restartNow(
            @ToolParam(description = "Delay in seconds before restart (0 for immediate, max 3600)") int delaySeconds) {
        int d = clamp(delaySeconds, 0, 3600);
        notifier.notify("Restart in " + d + "s");
        try {
            new ProcessBuilder("shutdown", "/r", "/t", String.valueOf(d)).start().waitFor(3, TimeUnit.SECONDS);
            return d == 0 ? "Restarting now..." : "Restart scheduled in " + d + " seconds. 'cancel shutdown' to abort.";
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Open a Windows Settings page directly using its ms-settings: URI. "
            + "Common pages: 'sound', 'bluetooth', 'display', 'nightlight', 'network-wifi', "
            + "'personalization-colors', 'privacy-microphone', 'about', 'apps-default'. "
            + "Use when the user says 'open display settings', 'go to network settings', etc.")
    public String openSettingsPage(
            @ToolParam(description = "Settings page name (e.g. 'sound', 'bluetooth', 'display', 'nightlight')") String page) {
        if (page == null || page.isBlank()) return "Please say which settings page to open.";
        String uri = "ms-settings:" + page.trim();
        try {
            new ProcessBuilder("cmd", "/c", "start", "", uri).start();
            return "Opened " + uri;
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Explorer & taskbar
    // ═════════════════════════════════════════════════════════════════

    @Tool(description = "Show or hide hidden files in File Explorer. "
            + "Use when the user says 'show hidden files', 'hide hidden files'.")
    public String setShowHiddenFiles(
            @ToolParam(description = "true to show hidden files, false to hide") boolean show) {
        int val = show ? 1 : 2;
        notifier.notify((show ? "Showing" : "Hiding") + " hidden files...");
        PsResult r = runPowerShell(
                "Set-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced' "
                + "-Name Hidden -Value " + val + ";"
                + "Stop-Process -Name explorer -Force -ErrorAction SilentlyContinue", 6);
        return r.ok ? "Hidden files " + (show ? "shown" : "hidden") + " (Explorer restarted)."
                : "Failed: " + truncate(r.err, 150);
    }

    @Tool(description = "Show or hide known file extensions in File Explorer. "
            + "Use when the user says 'show file extensions', 'hide extensions'.")
    public String setShowFileExtensions(
            @ToolParam(description = "true to show extensions, false to hide") boolean show) {
        int val = show ? 0 : 1;  // HideFileExt: 1=hide, 0=show
        notifier.notify((show ? "Showing" : "Hiding") + " file extensions...");
        PsResult r = runPowerShell(
                "Set-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced' "
                + "-Name HideFileExt -Value " + val + ";"
                + "Stop-Process -Name explorer -Force -ErrorAction SilentlyContinue", 6);
        return r.ok ? "File extensions " + (show ? "now visible" : "now hidden") + "."
                : "Failed: " + truncate(r.err, 150);
    }

    @Tool(description = "Set the Windows 11 taskbar alignment — 'center' (default) or 'left'. "
            + "Use when the user says 'move taskbar to the left', 'center taskbar'.")
    public String setTaskbarAlignment(
            @ToolParam(description = "'center' or 'left'") String alignment) {
        if (alignment == null) return "Specify 'center' or 'left'.";
        String a = alignment.trim().toLowerCase();
        int val;
        if (a.equals("center") || a.equals("middle")) val = 1;
        else if (a.equals("left")) val = 0;
        else return "Unknown alignment '" + alignment + "'. Use 'center' or 'left'.";
        PsResult r = runPowerShell(
                "Set-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced' "
                + "-Name TaskbarAl -Value " + val + ";"
                + "Stop-Process -Name explorer -Force -ErrorAction SilentlyContinue", 6);
        return r.ok ? "Taskbar aligned to " + (val == 1 ? "center" : "left") + "."
                : "Failed: " + truncate(r.err, 150);
    }

    // ═════════════════════════════════════════════════════════════════
    //  Focus / notifications / clipboard history
    // ═════════════════════════════════════════════════════════════════

    @Tool(description = "Turn Windows clipboard history on or off. "
            + "Use when the user says 'enable clipboard history', 'disable clipboard history'.")
    public String setClipboardHistory(
            @ToolParam(description = "true to enable, false to disable") boolean enabled) {
        int val = enabled ? 1 : 0;
        PsResult r = runPowerShell(
                "New-Item -Path 'HKCU:\\Software\\Microsoft\\Clipboard' -Force | Out-Null;"
                + "Set-ItemProperty 'HKCU:\\Software\\Microsoft\\Clipboard' -Name EnableClipboardHistory -Value "
                + val + " -Type DWord", 4);
        return r.ok ? "Clipboard history " + (enabled ? "enabled." : "disabled.")
                : "Failed: " + truncate(r.err, 150);
    }

    @Tool(description = "Turn Windows toast notifications on or off globally. "
            + "Use when the user says 'disable notifications', 'enable notifications', 'mute all notifications'.")
    public String setNotifications(
            @ToolParam(description = "true to enable, false to disable") boolean enabled) {
        int val = enabled ? 1 : 0;
        PsResult r = runPowerShell(
                "Set-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\PushNotifications' "
                + "-Name ToastEnabled -Value " + val + " -Type DWord", 4);
        return r.ok ? "Toast notifications " + (enabled ? "enabled." : "disabled.")
                : "Failed: " + truncate(r.err, 150);
    }

    @Tool(description = "Open Focus Assist / Do Not Disturb settings page — direct toggle is blocked on "
            + "most Windows builds, so this opens the panel for the user to flip.")
    public String openFocusAssistSettings() {
        try {
            new ProcessBuilder("cmd", "/c", "start", "", "ms-settings:quiethours").start();
            return "Opened Focus / Do Not Disturb settings.";
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Keyboard locks, wallpaper, recycle bin, misc
    // ═════════════════════════════════════════════════════════════════

    @Tool(description = "Toggle Caps Lock on or off. Use when the user says 'caps lock on/off', 'turn on caps lock'.")
    public String setCapsLock(
            @ToolParam(description = "true for on, false for off") boolean on) {
        try {
            java.awt.Toolkit toolkit = java.awt.Toolkit.getDefaultToolkit();
            boolean current = toolkit.getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK);
            if (current != on) {
                java.awt.Robot r = new java.awt.Robot();
                r.keyPress(java.awt.event.KeyEvent.VK_CAPS_LOCK);
                r.keyRelease(java.awt.event.KeyEvent.VK_CAPS_LOCK);
            }
            return "Caps Lock " + (on ? "ON." : "OFF.");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Toggle Num Lock on or off.")
    public String setNumLock(
            @ToolParam(description = "true for on, false for off") boolean on) {
        try {
            java.awt.Toolkit toolkit = java.awt.Toolkit.getDefaultToolkit();
            boolean current = toolkit.getLockingKeyState(java.awt.event.KeyEvent.VK_NUM_LOCK);
            if (current != on) {
                java.awt.Robot r = new java.awt.Robot();
                r.keyPress(java.awt.event.KeyEvent.VK_NUM_LOCK);
                r.keyRelease(java.awt.event.KeyEvent.VK_NUM_LOCK);
            }
            return "Num Lock " + (on ? "ON." : "OFF.");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Empty the Windows Recycle Bin (permanently deletes everything in it). "
            + "Use when the user says 'empty the recycle bin', 'clear trash'.")
    public String emptyRecycleBin() {
        notifier.notify("Emptying Recycle Bin...");
        PsResult r = runPowerShell("Clear-RecycleBin -Force -ErrorAction SilentlyContinue", 10);
        return r.ok ? "Recycle Bin emptied." : "Failed: " + truncate(r.err, 150);
    }

    @Tool(description = "Set the desktop wallpaper to an image file. "
            + "Use when the user says 'change wallpaper to X', 'set my background to X'.")
    public String setWallpaper(
            @ToolParam(description = "Absolute path to an image file (JPG, PNG, BMP)") String imagePath) {
        if (imagePath == null || imagePath.isBlank()) return "Specify the full path to an image.";
        java.io.File f = new java.io.File(imagePath.trim());
        if (!f.isFile()) return "File not found: " + imagePath;
        String safePath = f.getAbsolutePath().replace("'", "''");
        notifier.notify("Setting wallpaper to " + f.getName());
        String script =
            "$ErrorActionPreference='Stop';" +
            "Add-Type -Language CSharp @'\n" +
            "using System;using System.Runtime.InteropServices;\n" +
            "public class W{[DllImport(\"user32.dll\",CharSet=CharSet.Auto)]public static extern int SystemParametersInfo(int a,int b,string c,int d);}\n" +
            "'@;" +
            "[W]::SystemParametersInfo(20,0,'" + safePath + "',3)";
        PsResult r = runPowerShell(script, 6);
        return r.ok ? "Wallpaper set to " + f.getName() + "." : "Failed: " + truncate(r.err, 150);
    }

    // ═════════════════════════════════════════════════════════════════
    //  Helpers
    // ═════════════════════════════════════════════════════════════════

    private static int clamp(int v, int min, int max) { return Math.min(max, Math.max(min, v)); }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private record PsResult(boolean ok, String out, String err) {}

    private PsResult runPowerShell(String script, int timeoutSec) {
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
            Process p = pb.start();
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            Thread t1 = new Thread(() -> { try { p.getInputStream().transferTo(outBuf); } catch (Exception ignored) {} });
            Thread t2 = new Thread(() -> { try { p.getErrorStream().transferTo(errBuf); } catch (Exception ignored) {} });
            t1.setDaemon(true); t2.setDaemon(true);
            t1.start(); t2.start();
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            t1.join(500); t2.join(500);
            return new PsResult(done && p.exitValue() == 0,
                    outBuf.toString(StandardCharsets.UTF_8).trim(),
                    errBuf.toString(StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            log.debug("[WinSettings] PowerShell failed: {}", e.getMessage());
            return new PsResult(false, "", e.getMessage());
        }
    }
}
