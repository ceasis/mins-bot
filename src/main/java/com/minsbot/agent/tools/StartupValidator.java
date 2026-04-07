package com.minsbot.agent.tools;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs after all beans are initialized and logs a startup health summary.
 *
 * <p>Checks:
 * <ul>
 *   <li>Java version (warns if below 17)</li>
 *   <li>Available heap memory</li>
 *   <li>API key environment variables (OPENAI_API_KEY, ANTHROPIC_API_KEY, etc.)</li>
 *   <li>External tools: ffmpeg, node, npx</li>
 *   <li>Total {@code @Tool} method count across all beans</li>
 *   <li>Startup time</li>
 * </ul>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StartupValidator {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

    private final ApplicationContext applicationContext;

    public StartupValidator(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void validate() {
        long start = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║             MINS BOT — STARTUP VALIDATION               ║");
        log.info("╚══════════════════════════════════════════════════════════╝");

        // ── Java version ──
        String javaVersion = System.getProperty("java.version");
        log.info("[Startup] Java version: {}", javaVersion);
        try {
            int major = Runtime.version().feature();
            if (major < 17) {
                warnings.add("Java version " + javaVersion + " is below the required 17+");
            }
        } catch (Exception e) {
            log.warn("[Startup] Could not parse Java version: {}", javaVersion);
        }

        // ── Memory ──
        Runtime rt = Runtime.getRuntime();
        long maxMB = rt.maxMemory() / (1024 * 1024);
        long totalMB = rt.totalMemory() / (1024 * 1024);
        long freeMB = rt.freeMemory() / (1024 * 1024);
        log.info("[Startup] Memory — max: {}MB, total: {}MB, free: {}MB", maxMB, totalMB, freeMB);

        // ── API keys ──
        Map<String, String> apiKeys = Map.of(
                "OPENAI_API_KEY", "OpenAI",
                "ANTHROPIC_API_KEY", "Anthropic",
                "GOOGLE_API_KEY", "Google AI"
        );
        for (var entry : apiKeys.entrySet()) {
            String val = System.getenv(entry.getKey());
            if (val != null && !val.isBlank()) {
                log.info("[Startup] {} ({}) — configured", entry.getValue(), entry.getKey());
            } else {
                warnings.add(entry.getValue() + " (" + entry.getKey() + ") is not set");
            }
        }

        // ── External tools ──
        checkExternalTool("ffmpeg", "-version", warnings);
        checkExternalTool("node", "--version", warnings);
        checkExternalTool("npx", "--version", warnings);

        // ── @Tool method count ──
        int toolCount = countToolMethods();
        log.info("[Startup] Registered @Tool methods: {}", toolCount);

        // ── Bean count ──
        int beanCount = applicationContext.getBeanDefinitionCount();
        log.info("[Startup] Spring beans loaded: {}", beanCount);

        // ── Warnings summary ──
        long elapsed = System.currentTimeMillis() - start;
        if (warnings.isEmpty()) {
            log.info("[Startup] No warnings — all checks passed");
        } else {
            log.warn("[Startup] {} warning(s):", warnings.size());
            for (String w : warnings) {
                log.warn("[Startup]   ⚠ {}", w);
            }
        }

        log.info("[Startup] Validation completed in {}ms", elapsed);
        log.info("══════════════════════════════════════════════════════════");
    }

    // ───────────────────────── Helpers ─────────────────────────

    private void checkExternalTool(String command, String versionFlag, List<String> warnings) {
        try {
            Process proc = new ProcessBuilder(command, versionFlag)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String firstLine = reader.readLine();
                int exitCode = proc.waitFor();
                if (exitCode == 0 && firstLine != null) {
                    log.info("[Startup] {} — available ({})", command, firstLine.trim());
                } else {
                    warnings.add(command + " returned exit code " + exitCode);
                }
            }
        } catch (Exception e) {
            warnings.add(command + " is not available on PATH (" + e.getMessage() + ")");
        }
    }

    private int countToolMethods() {
        int count = 0;
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            try {
                Object bean = applicationContext.getBean(beanName);
                for (Method method : bean.getClass().getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Tool.class)) {
                        count++;
                    }
                }
            } catch (Exception ignored) {
                // Some beans may be lazy / proxied / scope-limited — skip them
            }
        }
        return count;
    }
}
