package com.minsbot;

import com.minsbot.release.CrashReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MinsbotApplication {

    private static final Logger log = LoggerFactory.getLogger(MinsbotApplication.class);

    public static void main(String[] args) {
        CrashReporter.install();
        SpringApplication.run(MinsbotApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("""

                ╔══════════════════════════════════════════════════════════╗
                ║                                                          ║
                ║   ██████  ███████  █████  ██████  ██    ██               ║
                ║   ██   ██ ██      ██   ██ ██   ██  ██  ██                ║
                ║   ██████  █████   ███████ ██   ██   ████                 ║
                ║   ██   ██ ██      ██   ██ ██   ██    ██                  ║
                ║   ██   ██ ███████ ██   ██ ██████     ██                  ║
                ║                                                          ║
                ║              Mins Bot is ready!                          ║
                ╚══════════════════════════════════════════════════════════╝
                """);
    }
}
