package com.minsbot.agent.tools;

import com.minsbot.skills.portkiller.PortKillerConfig;
import com.minsbot.skills.portkiller.PortKillerService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lets the chat agent actually kill processes by port instead of telling the
 * user to open a terminal. Refuses to kill protected ports (the bot's own
 * port 8765 by default).
 */
@Component
public class PortKillerTools {

    @Autowired(required = false) private PortKillerService service;
    @Autowired(required = false) private PortKillerConfig.PortKillerProperties props;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    @Tool(description = "Find which process(es) are listening on a TCP port. "
            + "Use when the user says 'what's on port X', 'who's using port X', 'check port X', "
            + "'is anything on port X'. Returns PID and process name.")
    public String findOnPort(
            @ToolParam(description = "TCP port number to check, e.g. 8080") int port) {
        if (service == null || props == null) return "portkiller skill is not loaded.";
        if (!props.isEnabled()) return "portkiller skill is disabled. Set app.skills.portkiller.enabled=true.";
        try {
            Map<String, Object> r = service.findOnPort(port);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> procs = (List<Map<String, Object>>) r.get("processes");
            if (procs == null || procs.isEmpty()) return "Nothing is listening on port " + port + ".";
            StringBuilder sb = new StringBuilder("Port ").append(port).append(" is held by:\n");
            for (Map<String, Object> p : procs) {
                sb.append("  • PID ").append(p.get("pid")).append(" — ").append(p.get("name")).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to check port " + port + ": " + e.getMessage();
        }
    }

    @Tool(description = "Kill the process(es) listening on a TCP port. "
            + "Use when the user says 'kill port X', 'kill the app on port X', 'free up port X', "
            + "'stop whatever is on port X', 'kill it' (in context of a port discussion). "
            + "Force-kills by default (taskkill /F on Windows, kill -9 on *nix). "
            + "Refuses to kill the bot's own port (default 8765).")
    public String killPort(
            @ToolParam(description = "TCP port to free up, e.g. 8080") int port,
            @ToolParam(description = "Force kill (true=SIGKILL/-F, false=graceful). Default true.", required = false)
            Boolean force) {
        if (service == null || props == null) return "portkiller skill is not loaded.";
        if (!props.isEnabled()) return "portkiller skill is disabled. Set app.skills.portkiller.enabled=true.";
        boolean f = force == null || force;
        if (notifier != null) notifier.notify("☠ killing port " + port + "...");
        try {
            Map<String, Object> r = service.kill(port, f);
            if (Boolean.FALSE.equals(r.get("ok")) && r.get("error") != null) return "✗ " + r.get("error");
            int killed = ((Number) r.getOrDefault("killed", 0)).intValue();
            if (killed == 0 && r.get("message") != null) return String.valueOf(r.get("message"));
            StringBuilder sb = new StringBuilder("☠ killed ").append(killed).append(" process(es) on port ").append(port).append(":\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attempts = (List<Map<String, Object>>) r.get("attempts");
            if (attempts != null) {
                for (Map<String, Object> a : attempts) {
                    boolean ok = Boolean.TRUE.equals(a.get("killed"));
                    sb.append("  ").append(ok ? "✓" : "✗").append(" PID ").append(a.get("pid"))
                            .append(" (").append(a.get("name")).append(")");
                    if (!ok && a.get("output") != null) sb.append(" — ").append(a.get("output"));
                    if (!ok && a.get("error") != null) sb.append(" — ").append(a.get("error"));
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to kill port " + port + ": " + e.getMessage();
        }
    }
}
