package com.minsbot.skills.batterystatus;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

@Service
public class BatteryStatusService {
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    public Map<String, Object> get() throws Exception {
        if (WIN) {
            String out = run("wmic", "path", "Win32_Battery", "get", "EstimatedChargeRemaining,BatteryStatus,EstimatedRunTime", "/format:csv");
            for (String line : out.split("\\R")) {
                String[] f = line.split(",");
                if (f.length < 4 || !f[3].matches("\\d+")) continue;
                int statusCode = Integer.parseInt(f[1]);
                int charge = Integer.parseInt(f[3]);
                int runtime = Integer.parseInt(f[2]);
                String state = switch (statusCode) {
                    case 1 -> "discharging";
                    case 2 -> "plugged in";
                    case 3 -> "fully charged";
                    case 4 -> "low";
                    case 5 -> "critical";
                    case 6 -> "charging";
                    default -> "unknown(" + statusCode + ")";
                };
                return Map.of("hasBattery", true, "chargePercent", charge, "state", state,
                        "estimatedRuntimeMinutes", runtime == 71582788 ? -1 : runtime);
            }
            return Map.of("hasBattery", false, "note", "No battery detected (desktop?)");
        } else if (MAC) {
            String out = run("pmset", "-g", "batt");
            return Map.of("hasBattery", true, "raw", out.trim());
        } else {
            try {
                String out = run("upower", "-i", "/org/freedesktop/UPower/devices/battery_BAT0");
                return Map.of("hasBattery", true, "raw", out.trim());
            } catch (Exception e) {
                return Map.of("hasBattery", false, "note", "Could not read battery info: " + e.getMessage());
            }
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
}
