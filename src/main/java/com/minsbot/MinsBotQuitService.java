package com.minsbot;

import javafx.application.Platform;
import org.springframework.stereotype.Service;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Allows the bot to request application quit. The actual shutdown runnable is
 * registered by FloatingAppLauncher so it can clean up JavaFX and Spring.
 * Supports scheduling quit in 30 seconds (canceled if the user sends another message).
 */
@Service
public class MinsBotQuitService {

    private static final int QUIT_TIMEOUT_SECONDS = 30;

    private final AtomicReference<Runnable> quitRunnable = new AtomicReference<>();
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "minsbot-quit-timer");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> pendingQuitFuture;

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

    /**
     * Schedule quit in 30 seconds. Canceled if the user sends another message (call cancelPendingQuit).
     */
    public void scheduleQuitIn30Sec() {
        cancelPendingQuit();
        pendingQuitFuture = scheduler.schedule(() -> {
            pendingQuitFuture = null;
            requestQuit();
        }, QUIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Cancel the pending 30-second quit (e.g. user sent a new message).
     */
    public void cancelPendingQuit() {
        ScheduledFuture<?> f = pendingQuitFuture;
        if (f != null) {
            f.cancel(false);
            pendingQuitFuture = null;
        }
    }

    /**
     * True when the user has been prompted with "Quit Mins Bot?" and a 30-second
     * confirmation window is active. Used as a server-side gate on the {@code quitMinsBot}
     * tool so the LLM can't autonomously quit mid-conversation on an ambiguous word.
     */
    public boolean hasPendingQuit() {
        ScheduledFuture<?> f = pendingQuitFuture;
        return f != null && !f.isDone() && !f.isCancelled();
    }
}
