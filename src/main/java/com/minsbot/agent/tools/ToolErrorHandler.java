package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Global error handling layer and tool execution safety wrapper.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code @RestControllerAdvice} — catches unhandled exceptions from all controllers
 *       and returns structured JSON instead of Spring's default HTML error page.</li>
 *   <li>{@link #safeExecute} — static utility that tools can use to wrap risky operations.</li>
 *   <li>{@code /api/health} — lightweight health-check endpoint.</li>
 * </ul>
 */
@RestControllerAdvice
public class ToolErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolErrorHandler.class);

    @Autowired
    private ApplicationContext applicationContext;

    // ───────────────────────── Exception handlers ─────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return Map.of(
                "error", true,
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleConflict(IllegalStateException ex) {
        log.warn("Illegal state: {}", ex.getMessage());
        return Map.of(
                "error", true,
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        );
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public Map<String, Object> handleNotImplemented(UnsupportedOperationException ex) {
        log.warn("Unsupported operation: {}", ex.getMessage());
        return Map.of(
                "error", true,
                "message", "Operation not supported: " + ex.getMessage(),
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Client-side disconnects ({@code IOException: connection aborted / broken pipe})
     * during SSE flushes are normal — the user closed the tab or the network blipped.
     * Don't log a stack trace and don't try to write a JSON body, since the response
     * is already mid-stream with Content-Type: text/event-stream and Jackson has no
     * converter for that combination → cascading {@link org.springframework.http.converter.HttpMessageNotWritableException}.
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Void> handleClientAbort(IOException ex, HttpServletResponse response) {
        log.debug("Client disconnect during write: {}", ex.getMessage());
        // Response is already committed for SSE; just signal end-of-stream with no body.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGenericException(Exception ex, HttpServletResponse response) {
        log.error("Unhandled exception in controller: {}", ex.getMessage(), ex);
        // If the response is mid-stream as SSE, returning a Map will fail message
        // conversion (Jackson has no text/event-stream converter). Bail empty.
        String ct = response.getContentType();
        if (ct != null && ct.startsWith("text/event-stream")) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", true,
                "message", "An internal error occurred: " + ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    // ───────────────────────── Tool execution safety wrapper ─────────────────────────

    /**
     * Wraps a tool operation so that any uncaught exception is caught and returned
     * as a user-friendly error string instead of propagating up and crashing the
     * chat flow.
     *
     * <p>Usage inside a {@code @Tool} method:
     * <pre>{@code
     * return ToolErrorHandler.safeExecute("downloadFile", () -> {
     *     // risky code here
     *     return "Done";
     * });
     * }</pre>
     *
     * @param operationName short label for log messages (e.g. tool or method name)
     * @param operation      the work to execute
     * @return the operation's result, or a {@code "FAILED: ..."} message on error
     */
    public static String safeExecute(String operationName, Supplier<String> operation) {
        try {
            return operation.get();
        } catch (Exception e) {
            log.error("[{}] Tool execution failed: {}", operationName, e.getMessage(), e);
            return "FAILED: " + operationName + " encountered an error: " + e.getMessage();
        }
    }

    // ───────────────────────── Health-check endpoint ─────────────────────────

    @GetMapping("/api/health")
    public Map<String, Object> healthCheck() {
        Runtime rt = Runtime.getRuntime();
        long usedBytes = rt.totalMemory() - rt.freeMemory();
        long maxBytes = rt.maxMemory();
        long freeBytes = maxBytes - usedBytes;

        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        String uptime = formatUptime(uptimeMs);

        int beanCount = applicationContext.getBeanDefinitionCount();

        return Map.of(
                "status", "ok",
                "uptime", uptime,
                "javaVersion", System.getProperty("java.version"),
                "memory", Map.of(
                        "used", formatMB(usedBytes),
                        "max", formatMB(maxBytes),
                        "free", formatMB(freeBytes)
                ),
                "beans", beanCount,
                "timestamp", Instant.now().toString()
        );
    }

    // ───────────────────────── Helpers ─────────────────────────

    private static String formatUptime(long ms) {
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    private static String formatMB(long bytes) {
        return (bytes / (1024 * 1024)) + "MB";
    }
}
