package com.minsbot.agent.tools;

import com.minsbot.skills.autostartmanager.AutoStartManagerConfig;
import com.minsbot.skills.autostartmanager.AutoStartManagerService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lets the chat agent register/unregister apps to auto-launch on system login.
 * Includes a self-install path so the user can say "auto-start yourself" and
 * the bot adds itself to startup.
 */
@Component
public class AutoStartTools {

    @Autowired(required = false) private AutoStartManagerService svc;
    @Autowired(required = false) private AutoStartManagerConfig.AutoStartManagerProperties props;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    @Tool(description = "Register an app or command to auto-launch every time the user logs in. "
            + "Use when the user says 'auto-open chrome on restart', 'auto-start <app> when I "
            + "log in', 'launch X every boot'. Writes to HKCU\\Run on Windows (no admin needed) "
            + "or ~/.config/autostart/ on Linux.")
    public String addToAutoStart(
            @ToolParam(description = "Friendly name (used as the registry key / .desktop filename)") String name,
            @ToolParam(description = "Full command or path to the executable, e.g. 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'") String command) {
        if (svc == null || props == null) return "autostartmanager skill not loaded.";
        if (!props.isEnabled()) return "autostartmanager skill is disabled. Set app.skills.autostartmanager.enabled=true.";
        if (notifier != null) notifier.notify("⚙ adding " + name + " to startup...");
        try {
            Map<String, Object> r = svc.addEntry(name, command);
            return Boolean.TRUE.equals(r.get("ok"))
                    ? "✓ '" + name + "' will now auto-start on login → " + r.get("where")
                    : "✗ " + r.get("output");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Remove an app from the auto-start list. Use when the user says "
            + "'don't auto-start chrome anymore', 'remove X from startup', 'stop X from auto-launching'.")
    public String removeFromAutoStart(
            @ToolParam(description = "Name of the entry to remove (must match what was added)") String name) {
        if (svc == null || props == null) return "autostartmanager skill not loaded.";
        if (!props.isEnabled()) return "autostartmanager skill is disabled.";
        try {
            Map<String, Object> r = svc.disableEntry(name);
            return Boolean.TRUE.equals(r.get("ok")) ? "✓ removed '" + name + "' from auto-start"
                    : "✗ " + r.get("error");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "List everything currently set to auto-start at login (registry Run keys, "
            + "Startup folder, ~/.config/autostart, systemd user units). Use when the user says "
            + "'what auto-starts on my computer', 'show startup apps', 'list login items'.")
    public String listAutoStart() {
        if (svc == null || props == null) return "autostartmanager skill not loaded.";
        if (!props.isEnabled()) return "autostartmanager skill is disabled.";
        try {
            Map<String, Object> r = svc.list();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) r.get("entries");
            if (entries == null || entries.isEmpty()) return "Nothing is set to auto-start.";
            StringBuilder sb = new StringBuilder(entries.size() + " auto-start entries:\n");
            for (Map<String, Object> e : entries) {
                sb.append("  • [").append(e.get("source")).append("] ").append(e.get("name"));
                if (e.get("command") != null) sb.append(" → ").append(e.get("command"));
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Make THE BOT ITSELF (Mins Bot) auto-launch every time the user logs in to "
            + "their computer. Use when the user says 'auto-start yourself', 'open yourself on "
            + "restart', 'auto-launch the bot', 'launch yourself when I log in'. Auto-detects the "
            + "launch command from restart.bat or the built JAR; configurable via "
            + "app.skills.autostartmanager.self-command.")
    public String autoStartSelf() {
        if (svc == null || props == null) return "autostartmanager skill not loaded.";
        if (!props.isEnabled()) return "autostartmanager skill is disabled. Set app.skills.autostartmanager.enabled=true.";
        if (notifier != null) notifier.notify("⚙ installing self to startup...");
        try {
            Map<String, Object> r = svc.installSelf();
            if (Boolean.TRUE.equals(r.get("ok")))
                return "✓ I'll now launch automatically when you log in.\n  Entry: " + r.get("entryName")
                        + "\n  Command: " + r.get("autoDetectedCommand")
                        + "\n  Where: " + r.get("where");
            return "✗ Couldn't install: " + r.get("output");
        } catch (Exception e) {
            return "Failed: " + e.getMessage()
                    + "\n(If auto-detect fails, set app.skills.autostartmanager.self-command in application.properties.)";
        }
    }

    @Tool(description = "Stop the bot from auto-launching at login. Use when the user says "
            + "'don't auto-start yourself anymore', 'remove yourself from startup'.")
    public String removeAutoStartSelf() {
        if (svc == null || props == null) return "autostartmanager skill not loaded.";
        if (!props.isEnabled()) return "autostartmanager skill is disabled.";
        try {
            Map<String, Object> r = svc.uninstallSelf();
            return Boolean.TRUE.equals(r.get("ok")) ? "✓ I'll no longer auto-launch on login."
                    : "✗ " + r.get("error");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }
}
