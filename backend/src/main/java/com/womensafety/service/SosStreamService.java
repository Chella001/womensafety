package com.womensafety.service;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.womensafety.exception.TooManyRequestsException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes "something changed" signals to signed-in administrators over Server-Sent Events. The events carry no personal
 * data (type, alert id, status); the console reloads the list through the normal authorised API.
 *
 * <p>Connections are authorised once, when they open, and are closed after {@link #TIMEOUT_MS} so the client reconnects
 * with a fresh ID token. Subscribers live in memory: with several backend instances an administrator only receives
 * signals from the instance they are connected to, which is why the console also polls slowly as a fallback.</p>
 */
@Service
public class SosStreamService {

    static final long TIMEOUT_MS = 4 * 60_000L;
    static final int MAX_SUBSCRIBERS = 25;

    private final Set<SseEmitter> subscribers = ConcurrentHashMap.newKeySet();
    private final Clock clock;

    public SosStreamService(Clock clock) {
        this.clock = clock;
    }

    public SseEmitter subscribe() {
        if (subscribers.size() >= MAX_SUBSCRIBERS) {
            throw new TooManyRequestsException("Too many live connections. Please try again shortly.", 15);
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(error -> subscribers.remove(emitter));
        subscribers.add(emitter);
        send(emitter, SseEmitter.event().name("hello").data(Map.of("serverTime", clock.instant().toString())));
        return emitter;
    }

    /** Tells every connected administrator that an alert was created or changed. */
    public void publish(String type, String alertId, String status) {
        var event = SseEmitter.event().name("sos").data(Map.of(
                "type", type, "alertId", alertId, "status", status == null ? "" : status, "at", clock.instant().toString()));
        for (SseEmitter emitter : subscribers) {
            send(emitter, event);
        }
    }

    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        for (SseEmitter emitter : subscribers) {
            send(emitter, SseEmitter.event().comment("keepalive"));
        }
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(emitter);
        }
    }
}
