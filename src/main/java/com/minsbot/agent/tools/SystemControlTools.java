package com.minsbot.agent.tools;

import com.minsbot.skills.appkill.AppKillConfig;
import com.minsbot.skills.appkill.AppKillService;
import com.minsbot.skills.applauncher.AppLauncherConfig;
import com.minsbot.skills.applauncher.AppLauncherService;
import com.minsbot.skills.batterystatus.BatteryStatusConfig;
import com.minsbot.skills.batterystatus.BatteryStatusService;
import com.minsbot.skills.bigfilescan.BigFileScanConfig;
import com.minsbot.skills.bigfilescan.BigFileScanService;
import com.minsbot.skills.buildwatcher.BuildWatcherConfig;
import com.minsbot.skills.buildwatcher.BuildWatcherService;
import com.minsbot.skills.diskcleaner.DiskCleanerConfig;
import com.minsbot.skills.diskcleaner.DiskCleanerService;
import com.minsbot.skills.dockerctl.DockerCtlConfig;
import com.minsbot.skills.dockerctl.DockerCtlService;
import com.minsbot.skills.duplicatefinder.DuplicateFinderConfig;
import com.minsbot.skills.duplicatefinder.DuplicateFinderService;
import com.minsbot.skills.gitquickactions.GitQuickActionsConfig;
import com.minsbot.skills.gitquickactions.GitQuickActionsService;
import com.minsbot.skills.gpustatus.GpuStatusConfig;
import com.minsbot.skills.gpustatus.GpuStatusService;
import com.minsbot.skills.networkdiag.NetworkDiagConfig;
import com.minsbot.skills.networkdiag.NetworkDiagService;
import com.minsbot.skills.portmap.PortMapConfig;
import com.minsbot.skills.portmap.PortMapService;
import com.minsbot.skills.processkiller.ProcessKillerConfig;
import com.minsbot.skills.processkiller.ProcessKillerService;
import com.minsbot.skills.servicectl.ServiceCtlConfig;
import com.minsbot.skills.servicectl.ServiceCtlService;
import com.minsbot.skills.systemstats.SystemStatsConfig;
import com.minsbot.skills.systemstats.SystemStatsService;
import com.minsbot.skills.vpncheck.VpnCheckConfig;
import com.minsbot.skills.vpncheck.VpnCheckService;
import com.minsbot.skills.windowctl.WindowCtlConfig;
import com.minsbot.skills.windowctl.WindowCtlService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Bridges system-control skills to the chat agent so the LLM can act on
 * "kill chrome", "what's on port 8080", "free up disk", "ping google.com",
 * "battery status", etc., without telling the user to open a terminal.
 */
@Component
public class SystemControlTools {

    @Autowired(required = false) private ProcessKillerService pk;
    @Autowired(required = false) private ProcessKillerConfig.ProcessKillerProperties pkProps;
    @Autowired(required = false) private PortMapService pm;
    @Autowired(required = false) private PortMapConfig.PortMapProperties pmProps;
    @Autowired(required = false) private ServiceCtlService sc;
    @Autowired(required = false) private ServiceCtlConfig.ServiceCtlProperties scProps;
    @Autowired(required = false) private NetworkDiagService nd;
    @Autowired(required = false) private NetworkDiagConfig.NetworkDiagProperties ndProps;
    @Autowired(required = false) private VpnCheckService vpn;
    @Autowired(required = false) private VpnCheckConfig.VpnCheckProperties vpnProps;
    @Autowired(required = false) private SystemStatsService ss;
    @Autowired(required = false) private SystemStatsConfig.SystemStatsProperties ssProps;
    @Autowired(required = false) private DiskCleanerService dc;
    @Autowired(required = false) private DiskCleanerConfig.DiskCleanerProperties dcProps;
    @Autowired(required = false) private BatteryStatusService bat;
    @Autowired(required = false) private BatteryStatusConfig.BatteryStatusProperties batProps;
    @Autowired(required = false) private GpuStatusService gpu;
    @Autowired(required = false) private GpuStatusConfig.GpuStatusProperties gpuProps;
    @Autowired(required = false) private AppKillService ak;
    @Autowired(required = false) private AppKillConfig.AppKillProperties akProps;
    @Autowired(required = false) private AppLauncherService al;
    @Autowired(required = false) private AppLauncherConfig.AppLauncherProperties alProps;
    @Autowired(required = false) private WindowCtlService wc;
    @Autowired(required = false) private WindowCtlConfig.WindowCtlProperties wcProps;
    @Autowired(required = false) private BigFileScanService bfs;
    @Autowired(required = false) private BigFileScanConfig.BigFileScanProperties bfsProps;
    @Autowired(required = false) private DuplicateFinderService df;
    @Autowired(required = false) private DuplicateFinderConfig.DuplicateFinderProperties dfProps;
    @Autowired(required = false) private DockerCtlService dk;
    @Autowired(required = false) private DockerCtlConfig.DockerCtlProperties dkProps;
    @Autowired(required = false) private GitQuickActionsService gq;
    @Autowired(required = false) private GitQuickActionsConfig.GitQuickActionsProperties gqProps;
    @Autowired(required = false) private BuildWatcherService bw;
    @Autowired(required = false) private BuildWatcherConfig.BuildWatcherProperties bwProps;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    @Tool(description = "Kill an app or process by name (e.g. 'chrome', 'notepad', 'spotify'). "
            + "Use when the user says 'kill chrome', 'close all firefox', 'force quit notepad'.")
    public String killApp(@ToolParam(description = "Process or app name, e.g. 'chrome'") String name) {
        if (ak == null || akProps == null || !akProps.isEnabled()) return "appkill skill is disabled.";
        if (notifier != null) notifier.notify("☠ killing " + name + "...");
        try {
            Map<String, Object> r = ak.killByExeName(name);
            return Boolean.TRUE.equals(r.get("ok")) ? "✓ killed " + name : "✗ " + r.get("output");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "List all processes matching a name pattern. Use when the user says "
            + "'show all chrome processes', 'is X running', 'what java processes'.")
    public String findProcesses(@ToolParam(description = "Substring of process name") String pattern) {
        if (pk == null || pkProps == null || !pkProps.isEnabled()) return "processkiller skill is disabled.";
        try {
            Map<String, Object> r = pk.findByName(pattern);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ms = (List<Map<String, Object>>) r.get("matches");
            if (ms.isEmpty()) return "No process matched '" + pattern + "'";
            StringBuilder sb = new StringBuilder(ms.size() + " process(es) matching '" + pattern + "':\n");
            for (Map<String, Object> m : ms) sb.append("  • PID ").append(m.get("pid")).append(" — ").append(m.get("name")).append("\n");
            return sb.toString();
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "List the top processes by CPU or memory. Use when the user says 'what's "
            + "hogging my CPU', 'top processes', 'what's using memory'.")
    public String topProcesses(@ToolParam(description = "'cpu' or 'memory'") String by,
                               @ToolParam(description = "Number to return, default 10", required = false) Integer n) {
        if (pk == null || pkProps == null || !pkProps.isEnabled()) return "processkiller skill is disabled.";
        try {
            int count = n == null ? 10 : n;
            List<Map<String, Object>> rows = "memory".equalsIgnoreCase(by) ? pk.topByMemory(count) : pk.topByCpu(count);
            StringBuilder sb = new StringBuilder("Top ").append(rows.size()).append(" by ").append(by).append(":\n");
            for (Map<String, Object> r : rows) sb.append("  • PID ").append(r.get("pid")).append(" ").append(r.get("name")).append("\n");
            return sb.toString();
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "List ALL listening TCP ports + which app holds each. Use when the user "
            + "says 'what's running on my machine', 'list open ports', 'show all listeners'.")
    public String listAllPorts() {
        if (pm == null || pmProps == null || !pmProps.isEnabled()) return "portmap skill is disabled.";
        try {
            Map<String, Object> r = pm.listAll();
            @SuppressWarnings("unchecked")
            var ports = (Iterable<Map<String, Object>>) r.get("ports");
            if (ports == null) return "No ports listening.";
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Map<String, Object> p : ports) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> procs = (List<Map<String, Object>>) p.get("processes");
                String names = procs == null ? "?" : procs.stream().map(x -> x.get("name") + "(" + x.get("pid") + ")").reduce((a, b) -> a + ", " + b).orElse("?");
                sb.append("  ").append(p.get("port")).append(" → ").append(names).append("\n");
                count++;
            }
            return count + " listening ports:\n" + sb;
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Diagnose connectivity to a URL or host (DNS + TCP + HTTP, with timing). "
            + "Use when the user says 'why can't I reach X', 'is X down', 'ping X'.")
    public String diagnose(@ToolParam(description = "URL or host, e.g. 'https://google.com'") String url) {
        if (nd == null || ndProps == null || !ndProps.isEnabled()) return "networkdiag skill is disabled.";
        try {
            Map<String, Object> r = nd.diagnose(url);
            return "Verdict: " + r.get("verdict") + "\nDNS: " + r.get("dns") + "\nTCP: " + r.get("tcp") + "\nHTTP: " + r.get("http");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Show CPU, memory, disk, and load snapshot of THIS machine. Use when the user "
            + "says 'how's my machine', 'system stats', 'is my computer slow', 'memory usage'.")
    public String machineStats() {
        if (ss == null || ssProps == null || !ssProps.isEnabled()) return "systemstats skill is disabled.";
        Map<String, Object> r = ss.snapshot();
        return "OS: " + r.get("os") + " · cores=" + r.get("cores") + "\n"
                + "CPU: " + r.get("cpuLoadPercent") + "% · load=" + r.get("systemLoadAverage") + "\n"
                + "RAM: " + r.get("memory") + "\n"
                + "Disks: " + r.get("disks");
    }

    @Tool(description = "Show battery status (laptop only). Use when the user says 'battery', "
            + "'how much battery left', 'am I charging'.")
    public String battery() {
        if (bat == null || batProps == null || !batProps.isEnabled()) return "batterystatus skill is disabled.";
        try { return bat.get().toString(); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Show GPU usage / VRAM / temperature (NVIDIA only via nvidia-smi). Use when "
            + "the user says 'gpu', 'is my gpu hot', 'vram usage'.")
    public String gpu() {
        if (gpu == null || gpuProps == null || !gpuProps.isEnabled()) return "gpustatus skill is disabled.";
        return gpu.get().toString();
    }

    @Tool(description = "Check if a VPN is active and report public IP + geo location. Use when the "
            + "user says 'am I on VPN', 'what's my IP', 'check my location'.")
    public String vpnStatus() {
        if (vpn == null || vpnProps == null || !vpnProps.isEnabled()) return "vpncheck skill is disabled.";
        try { return vpn.check().toString(); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Find big files under a directory. Use when the user says 'find big files', "
            + "'what's taking up space in folder X', 'biggest files in X'.")
    public String findBigFiles(@ToolParam(description = "Directory to scan") String path,
                               @ToolParam(description = "Min file size in MB, default 50", required = false) Integer minMb) {
        if (bfs == null || bfsProps == null || !bfsProps.isEnabled()) return "bigfilescan skill is disabled.";
        try {
            Long min = minMb == null ? null : minMb * 1_000_000L;
            Map<String, Object> r = bfs.scan(path, min, 30);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) r.get("files");
            if (files.isEmpty()) return "No files >= threshold under " + path;
            StringBuilder sb = new StringBuilder("Top " + files.size() + " big files in " + path + " (total " + r.get("totalSizeMb") + "MB):\n");
            for (Map<String, Object> f : files) sb.append("  ").append(f.get("sizeMb")).append("MB · ").append(f.get("path")).append("\n");
            return sb.toString();
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "List temp/cache directories that could be cleaned. Use when the user says "
            + "'free up disk', 'clean temp files', 'where's wasted disk space'.")
    public String listTempDirs() {
        if (dc == null || dcProps == null || !dcProps.isEnabled()) return "diskcleaner skill is disabled.";
        Map<String, Object> r = dc.tempDirs();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dirs = (List<Map<String, Object>>) r.get("dirs");
        StringBuilder sb = new StringBuilder("Temp / cache directories:\n");
        for (Map<String, Object> d : dirs) {
            long b = d.get("sizeBytes") instanceof Number n ? n.longValue() : 0;
            sb.append("  ").append(b / 1_000_000).append("MB · ").append(d.get("path")).append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Find duplicate files (by hash) in a directory tree. Use when the user says "
            + "'find duplicates in X', 'what files are duplicated'.")
    public String findDuplicates(@ToolParam(description = "Directory to scan") String path) {
        if (df == null || dfProps == null || !dfProps.isEnabled()) return "duplicatefinder skill is disabled.";
        try {
            Map<String, Object> r = df.find(path);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) r.get("duplicateGroups");
            if (groups.isEmpty()) return "No duplicates found in " + path;
            return "Found " + groups.size() + " duplicate groups · ~" + r.get("wastedMb") + "MB wasted in " + path;
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Launch an app, file, or URL. Use when the user says 'open chrome', "
            + "'launch X', 'open https://...', 'open file Y'.")
    public String launchApp(@ToolParam(description = "Target name, path, or URL") String target) {
        if (al == null || alProps == null || !alProps.isEnabled()) return "applauncher skill is disabled.";
        try { return al.launch(target).toString(); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "List visible application windows. Use when the user says 'list windows', "
            + "'what's open', 'show open apps'.")
    public String listWindows() {
        if (wc == null || wcProps == null || !wcProps.isEnabled()) return "windowctl skill is disabled.";
        try {
            List<Map<String, Object>> ws = wc.list();
            if (ws.isEmpty()) return "No visible windows.";
            StringBuilder sb = new StringBuilder(ws.size() + " window(s):\n");
            for (Map<String, Object> w : ws) sb.append("  • ").append(w.get("title")).append("\n");
            return sb.toString();
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Bring a window to the foreground by title substring. Use when the user says "
            + "'focus chrome', 'switch to slack', 'bring up X'.")
    public String focusWindow(@ToolParam(description = "Substring of window title") String title) {
        if (wc == null || wcProps == null || !wcProps.isEnabled()) return "windowctl skill is disabled.";
        try { return wc.focus(title).toString(); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "List/start/stop/restart a Windows service or systemd unit. Use when the "
            + "user says 'restart docker service', 'stop sshd', 'start postgres'.")
    public String controlService(@ToolParam(description = "'list', 'start', 'stop', or 'restart'") String op,
                                 @ToolParam(description = "Service name (required for start/stop/restart, optional filter for list)", required = false) String name) {
        if (sc == null || scProps == null || !scProps.isEnabled()) return "servicectl skill is disabled.";
        try {
            if ("list".equalsIgnoreCase(op)) return sc.list(name).toString();
            return sc.action(name, op).toString();
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "List Docker containers. Use when the user says 'list containers', "
            + "'what's running in docker'.")
    public String dockerPs(@ToolParam(description = "Include stopped containers", required = false) Boolean all) {
        if (dk == null || dkProps == null || !dkProps.isEnabled()) return "dockerctl skill is disabled.";
        try { return dk.ps(all != null && all).get("output").toString(); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Stop / start / restart a Docker container by name. Use when the user says "
            + "'restart container X', 'stop container Y'.")
    public String dockerControl(@ToolParam(description = "'start','stop','restart'") String op,
                                @ToolParam(description = "Container name") String name) {
        if (dk == null || dkProps == null || !dkProps.isEnabled()) return "dockerctl skill is disabled.";
        try {
            return switch (op) {
                case "stop" -> dk.stop(name).toString();
                case "start" -> dk.start(name).toString();
                case "restart" -> dk.restart(name).toString();
                default -> "Unknown op: " + op;
            };
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Quick git status snapshot for a repo path. Use when the user says "
            + "'git status', 'what's the state of X repo', 'any uncommitted'.")
    public String gitSnapshot(@ToolParam(description = "Repo path; default = current dir", required = false) String path) {
        if (gq == null || gqProps == null || !gqProps.isEnabled()) return "gitquickactions skill is disabled.";
        try { return gq.snapshot(path).toString(); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Detect project type (mvn/gradle/npm/cargo/go/make) and run the build. Use "
            + "when the user says 'build the project', 'compile X'.")
    public String build(@ToolParam(description = "Project path; default = current dir", required = false) String path) {
        if (bw == null || bwProps == null || !bwProps.isEnabled()) return "buildwatcher skill is disabled.";
        try {
            Map<String, Object> r = bw.build(path);
            return "Build " + (Boolean.TRUE.equals(r.get("ok")) ? "✓ ok" : "✗ failed") + " (" + r.get("type") + ", "
                    + r.get("elapsedMs") + "ms)\n\n" + r.get("outputTail");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }
}
