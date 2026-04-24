package com.minsbot.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Catches uncaught exceptions and writes crash reports to
 * {@code ~/mins_bot_data/crashes/crash-<timestamp>.log}. Registered from
 * {@code main()} before Spring starts so early failures are also captured.
 */
public final class CrashReporter {

    private static final Logger log = LoggerFactory.getLogger(CrashReporter.class);

    private static final Path CRASH_DIR = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "crashes");

    private CrashReporter() {}

    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> writeCrash("thread:" + t.getName(), e));
    }

    public static void writeCrash(String source, Throwable ex) {
        try {
            Files.createDirectories(CRASH_DIR);
            String ts = Instant.now().toString().replace(':', '-');
            Path f = CRASH_DIR.resolve("crash-" + ts + ".log");

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("mins-bot crash report");
            pw.println("timestamp: " + Instant.now());
            pw.println("source: " + source);
            pw.println("os: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
            pw.println("java: " + System.getProperty("java.version"));
            pw.println("arch: " + System.getProperty("os.arch"));
            pw.println();
            if (ex != null) ex.printStackTrace(pw);

            Files.writeString(f, sw.toString());
            log.error("Crash written to {}", f, ex);
        } catch (Exception writeFailure) {
            log.error("Failed to write crash report", writeFailure);
        }
    }

    public static List<Path> listCrashes() {
        if (!Files.isDirectory(CRASH_DIR)) return List.of();
        try (Stream<Path> s = Files.list(CRASH_DIR)) {
            return s.filter(p -> p.getFileName().toString().startsWith("crash-"))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public static Path crashDir() { return CRASH_DIR; }
}
