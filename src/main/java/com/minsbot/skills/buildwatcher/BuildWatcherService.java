package com.minsbot.skills.buildwatcher;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class BuildWatcherService {
    private final BuildWatcherConfig.BuildWatcherProperties props;
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    public BuildWatcherService(BuildWatcherConfig.BuildWatcherProperties props) { this.props = props; }

    public Map<String, Object> detect(String path) {
        File dir = new File(path == null || path.isBlank() ? "." : path);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("path", dir.getAbsolutePath());
        if (new File(dir, "pom.xml").exists()) r.put("type", "maven");
        else if (new File(dir, "build.gradle").exists() || new File(dir, "build.gradle.kts").exists()) r.put("type", "gradle");
        else if (new File(dir, "package.json").exists()) r.put("type", "npm");
        else if (new File(dir, "Cargo.toml").exists()) r.put("type", "cargo");
        else if (new File(dir, "go.mod").exists()) r.put("type", "go");
        else if (new File(dir, "Makefile").exists()) r.put("type", "make");
        else r.put("type", "unknown");
        return r;
    }

    public Map<String, Object> build(String path) throws Exception {
        Map<String, Object> info = detect(path);
        String type = String.valueOf(info.get("type"));
        File dir = new File(path == null || path.isBlank() ? "." : path);
        String[] cmd = switch (type) {
            case "maven" -> WIN ? new String[]{"cmd", "/c", "mvn", "clean", "package", "-DskipTests"}
                                : new String[]{"mvn", "clean", "package", "-DskipTests"};
            case "gradle" -> WIN ? new String[]{"cmd", "/c", "gradlew.bat", "build", "-x", "test"}
                                 : new String[]{"./gradlew", "build", "-x", "test"};
            case "npm" -> WIN ? new String[]{"cmd", "/c", "npm", "run", "build"}
                              : new String[]{"npm", "run", "build"};
            case "cargo" -> new String[]{"cargo", "build", "--release"};
            case "go" -> new String[]{"go", "build", "./..."};
            case "make" -> new String[]{"make"};
            default -> throw new IllegalArgumentException("Unknown build type for " + dir.getAbsolutePath());
        };
        long start = System.currentTimeMillis();
        Process p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        boolean done = p.waitFor(props.getTimeoutSec(), TimeUnit.SECONDS);
        if (!done) p.destroyForcibly();
        long elapsed = System.currentTimeMillis() - start;
        return Map.of("type", type, "ok", done && p.exitValue() == 0,
                "exitCode", done ? p.exitValue() : -1,
                "elapsedMs", elapsed,
                "outputTail", tail(sb.toString(), 80));
    }

    private static String tail(String s, int lines) {
        String[] arr = s.split("\\R");
        if (arr.length <= lines) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = arr.length - lines; i < arr.length; i++) sb.append(arr[i]).append('\n');
        return sb.toString();
    }
}
