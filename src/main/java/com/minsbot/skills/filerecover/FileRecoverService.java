package com.minsbot.skills.filerecover;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Lists Recycle Bin contents and restores files. Windows uses the Shell COM
 * via PowerShell; mac uses AppleScript; Linux uses gio trash.
 */
@Service
public class FileRecoverService {
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    public List<Map<String, Object>> list() throws Exception {
        if (WIN) {
            String ps = "$shell = New-Object -ComObject Shell.Application; "
                    + "$shell.Namespace(0xA).Items() | ForEach-Object { "
                    + "[PSCustomObject]@{ Name=$_.Name; Path=$_.Path; Size=$_.Size; ModifyDate=$_.ModifyDate } "
                    + "} | ConvertTo-Csv -NoTypeInformation";
            String out = run("powershell", "-NoProfile", "-Command", ps);
            List<Map<String, Object>> items = new ArrayList<>();
            String[] lines = out.split("\\R");
            for (int i = 1; i < lines.length; i++) {
                String[] f = csv(lines[i]);
                if (f.length < 4) continue;
                items.add(Map.of("name", f[0], "originalPath", f[1], "size", f[2], "deletedAt", f[3]));
            }
            return items;
        } else if (MAC) {
            return List.of(Map.of("note", "macOS Trash listing requires AppleScript permission grants"));
        } else {
            String out = run("gio", "trash", "--list");
            List<Map<String, Object>> items = new ArrayList<>();
            for (String line : out.split("\\R")) {
                String t = line.trim();
                if (!t.isEmpty()) items.add(Map.of("entry", t));
            }
            return items;
        }
    }

    public Map<String, Object> restore(String name) throws Exception {
        if (WIN) {
            String ps = "$shell = New-Object -ComObject Shell.Application; "
                    + "$item = $shell.Namespace(0xA).Items() | Where-Object { $_.Name -eq '" + name.replace("'", "''") + "' } | Select-Object -First 1; "
                    + "if ($item) { $item.InvokeVerb('undelete'); 'OK' } else { 'NOT_FOUND' }";
            String out = run("powershell", "-NoProfile", "-Command", ps).trim();
            return Map.of("ok", "OK".equals(out), "result", out);
        } else {
            return Map.of("ok", false, "note", "Linux/Mac: use 'gio trash --restore <id>' or Finder GUI");
        }
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
