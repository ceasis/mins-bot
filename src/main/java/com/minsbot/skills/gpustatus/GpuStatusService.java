package com.minsbot.skills.gpustatus;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

@Service
public class GpuStatusService {
    private final GpuStatusConfig.GpuStatusProperties props;
    public GpuStatusService(GpuStatusConfig.GpuStatusProperties props) { this.props = props; }

    public Map<String, Object> get() {
        // Try nvidia-smi first
        try {
            String out = run("nvidia-smi", "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu",
                    "--format=csv,noheader,nounits");
            List<Map<String, Object>> gpus = new ArrayList<>();
            boolean hot = false;
            for (String line : out.split("\\R")) {
                String[] f = line.split(",");
                if (f.length < 5) continue;
                int temp = Integer.parseInt(f[4].trim());
                if (temp > props.getHotThresholdC()) hot = true;
                gpus.add(Map.of(
                        "vendor", "nvidia",
                        "name", f[0].trim(),
                        "utilizationPercent", Integer.parseInt(f[1].trim()),
                        "memoryUsedMb", Integer.parseInt(f[2].trim()),
                        "memoryTotalMb", Integer.parseInt(f[3].trim()),
                        "temperatureC", temp));
            }
            return Map.of("ok", true, "gpus", gpus, "anyHot", hot, "hotThresholdC", props.getHotThresholdC());
        } catch (Exception e) {
            return Map.of("ok", false, "error", "nvidia-smi unavailable: " + e.getMessage(),
                    "note", "AMD/Intel GPU monitoring needs additional tooling (radeontop, intel_gpu_top)");
        }
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("exit " + code + ": " + sb);
        return sb.toString();
    }
}
