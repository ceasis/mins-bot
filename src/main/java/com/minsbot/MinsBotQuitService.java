package com.minsbot;

import javafx.application.Platform;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Allows the bot to request application quit. The actual shutdown runnable is
 * registered by FloatingAppLauncher so it can clean up JavaFX and Spring.
 */
@Service
public class MinsBotQuitService {

    private final AtomicReference<Runnable> quitRunnable = new AtomicReference<>();

    /**
     * Called by FloatingAppLauncher after the stage and bridge are ready.
     * The runnable should shut down native voice, then Platform.exit() and SpringApplication.exit().
     */
    public void setQuitRunnable(Runnable runnable) {
        quitRunnable.set(runnable);
    }

    /**
     * Request the application to quit. Runs the registered runnable on the JavaFX thread.
     */
    public void requestQuit() {
        Runnable r = quitRunnable.get();
        if (r != null) {
            Platform.runLater(r);
        } else {
            System.exit(0);
        }
    }
}
