package com.minsbot.skills.powerctl;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

@Service
public class PowerCtlService {
    private final PowerCtlConfig.PowerCtlProperties props;
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    public PowerCtlService(PowerCtlConfig.PowerCtlProperties props) { this.props = props; }

    public Map<String, Object> lock() throws Exception {
        return shell(WIN ? new String[]{"rundll32.exe", "user32.dll,LockWorkStation"}
                : MAC ? new String[]{"pmset", "displaysleepnow"}
                : new String[]{"loginctl", "lock-session"});
    }

    public Map<String, Object> sleep() throws Exception {
        return shell(WIN ? new String[]{"powershell", "-NoProfile", "-Command", "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState('Suspend',$false,$false)"}
                : MAC ? new String[]{"pmset", "sleepnow"}
                : new String[]{"systemctl", "suspend"});
    }

    public Map<String, Object> hibernate() throws Exception {
        return shell(WIN ? new String[]{"shutdown", "/h"}
                : MAC ? new String[]{"pmset", "sleepnow"}
                : new String[]{"systemctl", "hibernate"});
    }

    public Map<String, Object> shutdown(int delaySeconds) throws Exception {
        if (!props.isAllowShutdown()) throw new RuntimeException("Shutdown blocked. Set app.skills.powerctl.allow-shutdown=true to permit.");
        return shell(WIN ? new String[]{"shutdown", "/s", "/t", String.valueOf(delaySeconds)}
                : new String[]{"shutdown", "-h", "+" + Math.max(1, delaySeconds / 60)});
    }

    public Map<String, Object> cancelShutdown() throws Exception {
        return shell(WIN ? new String[]{"shutdown", "/a"} : new String[]{"shutdown", "-c"});
    }

    public Map<String, Object> restart(int delaySeconds) throws Exception {
        if (!props.isAllowShutdown()) throw new RuntimeException("Restart blocked. Set app.skills.powerctl.allow-shutdown=true to permit.");
        return shell(WIN ? new String[]{"shutdown", "/r", "/t", String.valueOf(delaySeconds)}
                : new String[]{"shutdown", "-r", "+" + Math.max(1, delaySeconds / 60)});
    }

    private static Map<String, Object> shell(String[] cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        int code = p.waitFor();
        return Map.of("ok", code == 0, "exitCode", code, "output", sb.toString().trim());
    }
}
