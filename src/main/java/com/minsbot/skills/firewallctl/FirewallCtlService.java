package com.minsbot.skills.firewallctl;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Manages OS firewall rules. Windows: netsh advfirewall (requires admin).
 * Linux: iptables (requires sudo). Most operations need elevated privileges.
 */
@Service
public class FirewallCtlService {
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");

    public Map<String, Object> listRules(String filter) throws Exception {
        if (WIN) {
            String out = run("netsh", "advfirewall", "firewall", "show", "rule",
                    filter == null || filter.isBlank() ? "name=all" : "name=" + filter);
            return Map.of("ok", true, "raw", out.trim());
        } else {
            String out = run("iptables", "-L", "-n", "-v");
            return Map.of("ok", true, "raw", out.trim());
        }
    }

    public Map<String, Object> blockPort(int port, String direction, String name) throws Exception {
        if (name == null || name.isBlank()) name = "minsbot-block-" + port;
        if (WIN) {
            String dir = "out".equalsIgnoreCase(direction) ? "out" : "in";
            String[] cmd = {"netsh", "advfirewall", "firewall", "add", "rule",
                    "name=" + name, "dir=" + dir, "action=block", "protocol=TCP", "localport=" + port};
            return shellResult(cmd);
        } else {
            String chain = "out".equalsIgnoreCase(direction) ? "OUTPUT" : "INPUT";
            return shellResult(new String[]{"iptables", "-A", chain, "-p", "tcp", "--dport", String.valueOf(port), "-j", "DROP"});
        }
    }

    public Map<String, Object> deleteRule(String name) throws Exception {
        if (WIN) {
            return shellResult(new String[]{"netsh", "advfirewall", "firewall", "delete", "rule", "name=" + name});
        } else {
            // can't easily delete by name on iptables — user provides full rule spec
            throw new UnsupportedOperationException("Linux: pass exact rule via 'iptables -D'");
        }
    }

    private static Map<String, Object> shellResult(String[] cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        int code = p.waitFor();
        return Map.of("ok", code == 0, "exitCode", code, "output", sb.toString().trim(),
                "note", code != 0 ? "May require admin/sudo privileges" : "");
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
}
