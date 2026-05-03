package com.minsbot.skills.systemstats;

import com.sun.management.OperatingSystemMXBean;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.*;

@Service
public class SystemStatsService {

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        result.put("arch", System.getProperty("os.arch"));
        result.put("cores", os.getAvailableProcessors());
        result.put("systemLoadAverage", os.getSystemLoadAverage());
        result.put("cpuLoadPercent", round(os.getCpuLoad() * 100, 1));
        result.put("processCpuLoadPercent", round(os.getProcessCpuLoad() * 100, 1));

        long total = os.getTotalMemorySize();
        long free = os.getFreeMemorySize();
        long used = total - free;
        result.put("memory", Map.of(
                "totalGb", round(total / 1e9, 2),
                "usedGb", round(used / 1e9, 2),
                "freeGb", round(free / 1e9, 2),
                "usedPercent", round((double) used / total * 100, 1)));

        List<Map<String, Object>> disks = new ArrayList<>();
        for (File root : File.listRoots()) {
            long t = root.getTotalSpace();
            long f = root.getFreeSpace();
            if (t == 0) continue;
            disks.add(Map.of(
                    "root", root.getAbsolutePath(),
                    "totalGb", round(t / 1e9, 2),
                    "freeGb", round(f / 1e9, 2),
                    "usedPercent", round((double) (t - f) / t * 100, 1)));
        }
        result.put("disks", disks);
        result.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        return result;
    }

    private static double round(double v, int p) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return -1;
        double f = Math.pow(10, p);
        return Math.round(v * f) / f;
    }
}
