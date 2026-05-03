package com.minsbot.skills.autostartmanager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;

@Service
public class AutoStartManagerService {
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");

    @Autowired(required = false) private AutoStartManagerConfig.AutoStartManagerProperties props;

    public Map<String, Object> list() throws Exception {
        if (WIN) {
            List<Map<String, Object>> entries = new ArrayList<>();
            // HKLM Run keys
            for (String hive : new String[]{"HKCU", "HKLM"}) {
                String out = run("reg", "query", hive + "\\Software\\Microsoft\\Windows\\CurrentVersion\\Run");
                String key = "(none)";
                for (String line : out.split("\\R")) {
                    String t = line.trim();
                    if (t.startsWith(hive + "\\")) { key = t; continue; }
                    if (t.isEmpty()) continue;
                    String[] f = t.split("\\s{2,}", 3);
                    if (f.length >= 3)
                        entries.add(Map.of("source", "registry-run", "hive", hive, "name", f[0], "type", f[1], "command", f[2]));
                }
            }
            // Startup folder
            String startup = System.getenv("APPDATA") + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup";
            String out = run("cmd", "/c", "dir", "/B", startup);
            for (String line : out.split("\\R")) {
                String t = line.trim();
                if (!t.isEmpty()) entries.add(Map.of("source", "startup-folder", "name", t, "path", startup + "\\" + t));
            }
            return Map.of("entries", entries, "count", entries.size());
        } else {
            List<Map<String, Object>> entries = new ArrayList<>();
            // ~/.config/autostart
            String home = System.getProperty("user.home");
            String out = run("ls", home + "/.config/autostart");
            for (String line : out.split("\\R")) {
                String t = line.trim();
                if (!t.isEmpty()) entries.add(Map.of("source", "autostart", "name", t));
            }
            // systemd user units
            try {
                String su = run("systemctl", "--user", "list-unit-files", "--type=service", "--state=enabled", "--no-legend");
                for (String line : su.split("\\R")) {
                    String[] f = line.trim().split("\\s+", 2);
                    if (f.length >= 1 && !f[0].isEmpty()) entries.add(Map.of("source", "systemd-user", "name", f[0]));
                }
            } catch (Exception ignored) {}
            return Map.of("entries", entries, "count", entries.size());
        }
    }

    /** Register an entry to run at login. Windows = HKCU\Run reg add (no admin).
     *  Linux = ~/.config/autostart/{name}.desktop. Mac = ~/Library/LaunchAgents (best-effort). */
    public Map<String, Object> addEntry(String name, String command) throws Exception {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (command == null || command.isBlank()) throw new IllegalArgumentException("command required");
        if (WIN) {
            Process p = new ProcessBuilder("reg", "add",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                    "/V", name, "/T", "REG_SZ", "/D", command, "/F").redirectErrorStream(true).start();
            String out = readAll(p);
            int code = p.waitFor();
            return Map.of("ok", code == 0, "added", name, "where", "HKCU\\...\\Run", "command", command, "output", out.trim());
        } else {
            // Write a .desktop file under ~/.config/autostart/
            String home = System.getProperty("user.home");
            Path dir = Paths.get(home, ".config", "autostart");
            Files.createDirectories(dir);
            Path file = dir.resolve(name.replaceAll("[^A-Za-z0-9._-]", "_") + ".desktop");
            String content = "[Desktop Entry]\n"
                    + "Type=Application\n"
                    + "Name=" + name + "\n"
                    + "Exec=" + command + "\n"
                    + "X-GNOME-Autostart-enabled=true\n"
                    + "Terminal=false\n";
            Files.writeString(file, content);
            return Map.of("ok", true, "added", name, "where", file.toAbsolutePath().toString(), "command", command);
        }
    }

    /** Install the bot itself to auto-start. Uses props.selfCommand if set;
     *  otherwise auto-detects <projectRoot>/restart.bat (Windows) or constructs a
     *  java -jar line from the running JAR. */
    public Map<String, Object> installSelf() throws Exception {
        String name = props == null || props.getSelfEntryName() == null || props.getSelfEntryName().isBlank()
                ? "MinsBot" : props.getSelfEntryName();
        String cmd = props == null ? "" : props.getSelfCommand();
        if (cmd == null || cmd.isBlank()) cmd = autodetectSelfCommand();
        if (cmd == null || cmd.isBlank())
            throw new RuntimeException("Could not auto-detect a launch command. Set app.skills.autostartmanager.self-command in application.properties.");
        Map<String, Object> r = addEntry(name, cmd);
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("autoDetectedCommand", cmd);
        out.put("entryName", name);
        return out;
    }

    public Map<String, Object> uninstallSelf() throws Exception {
        String name = props == null || props.getSelfEntryName() == null || props.getSelfEntryName().isBlank()
                ? "MinsBot" : props.getSelfEntryName();
        return disableEntry(name);
    }

    private static String autodetectSelfCommand() {
        // 1. project root restart.bat (Windows) or restart.sh (*nix)
        String userDir = System.getProperty("user.dir", ".");
        File bat = new File(userDir, WIN ? "restart.bat" : "restart.sh");
        if (bat.exists() && bat.canRead()) {
            return WIN ? "cmd /c \"" + bat.getAbsolutePath() + "\"" : "bash " + bat.getAbsolutePath();
        }
        // 2. running JAR — look in target/
        File targetDir = new File(userDir, "target");
        if (targetDir.isDirectory()) {
            File[] jars = targetDir.listFiles((d, n) -> n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar"));
            if (jars != null && jars.length > 0) {
                String javaBin = System.getProperty("java.home") + (WIN ? "\\bin\\javaw.exe" : "/bin/java");
                return "\"" + javaBin + "\" -jar \"" + jars[0].getAbsolutePath() + "\"";
            }
        }
        return null;
    }

    public Map<String, Object> disableEntry(String name) throws Exception {
        if (WIN) {
            try {
                Process p = new ProcessBuilder("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                        "/V", name, "/F").redirectErrorStream(true).start();
                String out = readAll(p);
                int code = p.waitFor();
                if (code == 0) return Map.of("ok", true, "removedFrom", "HKCU run", "output", out.trim());
            } catch (Exception ignored) {}
            return Map.of("ok", false, "error", "could not remove '" + name + "' — may need admin or be in HKLM/startup folder");
        } else {
            String home = System.getProperty("user.home");
            Path candidate = Paths.get(home, ".config", "autostart",
                    name.replaceAll("[^A-Za-z0-9._-]", "_") + ".desktop");
            if (Files.exists(candidate)) {
                Files.delete(candidate);
                return Map.of("ok", true, "removed", candidate.toString());
            }
            return Map.of("ok", false, "error", "no autostart entry found at " + candidate);
        }
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String s = readAll(p);
        p.waitFor();
        return s;
    }
    private static String readAll(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
