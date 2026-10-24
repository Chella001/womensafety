package com.womensafety.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.womensafety.config.NotificationProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link NotificationProcessor}: a short polling loop plus an immediate {@link #wake()} after a new alert so the
 * first attempt does not wait for the next tick. The polling loop is also the recovery path after a restart.
 */
@Component
public class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

    private final NotificationProcessor processor;
    private final NotificationProperties properties;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "notification-worker");
        thread.setDaemon(true);
        return thread;
    });

    public NotificationWorker(NotificationProcessor processor, NotificationProperties properties) {
        this.processor = processor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.notifications.worker-interval-ms:3000}", initialDelayString = "${app.notifications.worker-interval-ms:3000}")
    void poll() {
        if (properties.workerEnabled()) {
            runSafely();
        }
    }

    /** Starts a run now, in the background. Never blocks or fails the request that created the alert. */
    public void wake() {
        if (properties.workerEnabled()) {
            executor.execute(this::runSafely);
        }
    }

    private void runSafely() {
        try {
            processor.runOnce();
        } catch (RuntimeException e) {
            log.error("Notification worker run failed: {}", e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
