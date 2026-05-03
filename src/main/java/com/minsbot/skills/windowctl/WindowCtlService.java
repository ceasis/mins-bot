package com.minsbot.skills.windowctl;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * List/focus/min/max windows. Windows: PowerShell + Get-Process MainWindowTitle.
 * Linux: wmctrl. Mac: AppleScript.
 */
@Service
public class WindowCtlService {
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    public List<Map<String, Object>> list() throws Exception {
        if (WIN) {
            String ps = "Get-Process | Where-Object {$_.MainWindowTitle -ne ''} | "
                    + "Select-Object Id,ProcessName,MainWindowTitle | ConvertTo-Csv -NoTypeInformation";
            String out = run("powershell", "-NoProfile", "-Command", ps);
            List<Map<String, Object>> wins = new ArrayList<>();
            String[] lines = out.split("\\R");
            for (int i = 1; i < lines.length; i++) {
                String[] f = csv(lines[i]);
                if (f.length < 3) continue;
                try {
                    wins.add(Map.of("pid", Integer.parseInt(f[0]), "process", f[1], "title", f[2]));
                } catch (Exception ignored) {}
            }
            return wins;
        } else if (MAC) {
            String out = run("osascript", "-e",
                    "tell application \"System Events\" to get the name of every process whose visible is true");
            List<Map<String, Object>> wins = new ArrayList<>();
            for (String n : out.split(", ")) wins.add(Map.of("title", n.trim()));
            return wins;
        } else {
            String out = run("wmctrl", "-l");
            List<Map<String, Object>> wins = new ArrayList<>();
            for (String line : out.split("\\R")) {
                String[] f = line.trim().split("\\s+", 4);
                if (f.length < 4) continue;
                wins.add(Map.of("id", f[0], "desktop", f[1], "host", f[2], "title", f[3]));
            }
            return wins;
        }
    }

    public Map<String, Object> focus(String titleSubstring) throws Exception {
        if (WIN) {
            String ps = "$ws=Add-Type -Name W -Namespace Native -PassThru -MemberDefinition '"
                    + "[DllImport(\"user32.dll\")] public static extern bool SetForegroundWindow(IntPtr hWnd);"
                    + "'; "
                    + "$p = Get-Process | Where-Object { $_.MainWindowTitle -like '*" + titleSubstring.replace("'", "''") + "*' } | Select-Object -First 1; "
                    + "if ($p) { [Native.W]::SetForegroundWindow($p.MainWindowHandle); 'OK:'+$p.MainWindowTitle } else { 'NOT_FOUND' }";
            String out = run("powershell", "-NoProfile", "-Command", ps).trim();
            return Map.of("ok", out.startsWith("OK:"), "result", out);
        } else {
            return shellResult(new String[]{"wmctrl", "-a", titleSubstring});
        }
    }

    private static Map<String, Object> shellResult(String[] cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        int code = p.waitFor();
        return Map.of("ok", code == 0, "exitCode", code, "output", sb.toString().trim());
    }
    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }
    private static String[] csv(String line) {
        List<String> out = new ArrayList<>(); StringBuilder c = new StringBuilder(); boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') q = !q;
            else if (ch == ',' && !q) { out.add(c.toString()); c.setLength(0); }
            else c.append(ch);
        }
        out.add(c.toString());
        return out.toArray(new String[0]);
    }
}
