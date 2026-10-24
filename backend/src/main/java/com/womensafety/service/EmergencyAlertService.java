package com.womensafety.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.womensafety.config.NotificationProperties;
import com.womensafety.config.PolicyProperties;
import com.womensafety.dto.CreateAlertRequest;
import com.womensafety.dto.LocationRequest;
import com.womensafety.exception.ConflictException;
import com.womensafety.exception.InvalidRequestException;
import com.womensafety.exception.ResourceNotFoundException;
import com.womensafety.exception.TooManyRequestsException;
import com.womensafety.model.AlertEvent;
import com.womensafety.model.AlertLocation;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.Delivery;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.LocationStatus;
import com.womensafety.model.NotificationJob;
import com.womensafety.model.NotificationSummary;
import com.womensafety.repository.EmergencyAlertRepository;
import org.springframework.stereotype.Service;

/**
 * SOS alerts of the authenticated user.
 *
 * <p>Creation is idempotent: the client sends an {@code Idempotency-Key}, the alert id is derived from the user's uid
 * and that key, and Firestore creates the document only if it does not exist yet. A double click, a retry after a
 * network error, or two concurrent submissions therefore produce ONE alert, and the repeats return it unchanged.</p>
 *
 * <p>The alert and its notification job are stored together before anything is sent. Notification problems can never
 * undo an alert; they only show up in the delivery records.</p>
 */
@Service
public class EmergencyAlertService {

    private static final Duration MAX_LOCATION_AGE = Duration.ofMinutes(15);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(2);
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");
    private static final int HISTORY_LIMIT = 50;

    private final EmergencyAlertRepository repository;
    private final PolicyProperties policy;
    private final NotificationProperties notifications;
    private final AlertEventLog events;
    private final SosStreamService stream;
    private final NotificationWorker worker;
    private final Clock clock;

    public EmergencyAlertService(EmergencyAlertRepository repository, PolicyProperties policy,
                                 NotificationProperties notifications, AlertEventLog events, SosStreamService stream,
                                 NotificationWorker worker, Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.notifications = notifications;
        this.events = events;
        this.stream = stream;
        this.worker = worker;
        this.clock = clock;
    }

    /** @param replayed true when the key had been used before and the existing alert is returned */
    public record Created(EmergencyAlert alert, boolean replayed) {
    }

    public Created create(String uid, String idempotencyKey, CreateAlertRequest request) {
        String id;
        String keyHash = null;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            id = UUID.randomUUID().toString();
        } else {
            if (!KEY.matcher(idempotencyKey).matches()) {
                throw new InvalidRequestException("Idempotency-Key", "The Idempotency-Key must be 16-64 letters, digits, - or _.");
            }
            id = UUID.nameUUIDFromBytes((uid + "\n" + idempotencyKey).getBytes(StandardCharsets.UTF_8)).toString();
            keyHash = sha256(idempotencyKey);
            EmergencyAlert existing = repository.findById(id).orElse(null);
            if (existing != null) {
                return replay(uid, existing);
            }
        }
        Instant now = now();
        enforceRateLimit(uid, now);

        AlertLocation location = toLocation(request == null ? null : request.location(), now);
        EmergencyAlert alert = EmergencyAlert.builder()
                .id(id)
                .uid(uid)
                .status(AlertStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .location(location)
                .locationStatus(locationStatus(location, request))
                .retainUntil(now.plus(Duration.ofDays(policy.alertRetentionDays())))
                .idempotencyKeyHash(keyHash)
                .notificationMode(notifications.realMode() ? Delivery.Mode.REAL : Delivery.Mode.TEST)
                .notificationSummary(NotificationSummary.queued(now))
                .adminNotificationSummary(NotificationSummary.queued(now))
                .build();
        if (!repository.createIfAbsent(alert, NotificationJob.pending(id, now))) {
            // Lost a race with a concurrent submission of the same key: that alert is the result.
            return replay(uid, repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("That alert does not exist.")));
        }
        events.record(id, AlertEvent.CREATED, "user", Map.of("locationStatus", alert.locationStatus().name()));
        stream.publish("created", id, alert.status().name());
        worker.wake();
        return new Created(alert, false);
    }

    public EmergencyAlert cancel(String uid, String alertId) {
        Instant now = now();
        EmergencyAlert cancelled = repository.modify(alertId, alert -> {
            if (!alert.uid().equals(uid)) {
                throw new ResourceNotFoundException("That alert does not exist."); // never reveal other users' alerts
            }
            if (alert.status() != AlertStatus.ACTIVE) {
                throw new ConflictException("ALERT_NOT_ACTIVE", "This alert is already " + alert.status().name().toLowerCase() + ".");
            }
            return alert.cancelled(now);
        });
        events.record(alertId, AlertEvent.CANCELLATION_REQUESTED, "user", Map.of());
        stream.publish("cancelled", alertId, cancelled.status().name());
        worker.wake();
        return cancelled;
    }

    /** The caller's own alerts, newest first. */
    public List<EmergencyAlert> listOwn(String uid) {
        return repository.findByUid(uid).stream()
                .sorted(Comparator.comparing(EmergencyAlert::createdAt).reversed())
                .limit(HISTORY_LIMIT)
                .toList();
    }

    /** Someone else's alert is reported as "not found" so its existence is not revealed. */
    public EmergencyAlert getOwned(String uid, String alertId) {
        return repository.findById(alertId)
                .filter(alert -> alert.uid().equals(uid))
                .orElseThrow(() -> new ResourceNotFoundException("That alert does not exist."));
    }

    private Created replay(String uid, EmergencyAlert existing) {
        if (!existing.uid().equals(uid)) {
            throw new ConflictException("IDEMPOTENCY_KEY_REUSED", "That Idempotency-Key was already used. Use a new one.");
        }
        return new Created(existing, true);
    }

    private void enforceRateLimit(String uid, Instant now) {
        Instant windowStart = now.minus(Duration.ofMinutes(policy.alertWindowMinutes()));
        long recent = repository.findByUid(uid).stream().filter(a -> a.createdAt().isAfter(windowStart)).count();
        if (recent >= policy.maxAlertsPerWindow()) {
            throw new TooManyRequestsException(
                    "You have created several alerts in a short time. Please wait a few minutes. If you are in danger, call your emergency number.",
                    policy.alertWindowMinutes() * 60L);
        }
    }

    /** The coordinates are validated; the client's capture time is kept as information but never used as the alert time. */
    private static AlertLocation toLocation(LocationRequest request, Instant now) {
        if (request == null) {
            return null;
        }
        Instant captured = request.capturedAt();
        if (captured.isAfter(now.plus(MAX_CLOCK_SKEW)) || captured.isBefore(now.minus(MAX_LOCATION_AGE))) {
            throw new InvalidRequestException("location.capturedAt", "The location is too old. Capture it again and retry.");
        }
        return new AlertLocation(request.latitude(), request.longitude(), request.accuracyMeters(), captured);
    }

    private static LocationStatus locationStatus(AlertLocation location, CreateAlertRequest request) {
        if (location != null) {
            return LocationStatus.AVAILABLE;
        }
        LocationStatus claimed = request == null ? null : request.locationStatus();
        if (claimed == null || claimed == LocationStatus.NOT_REQUESTED) {
            return LocationStatus.NOT_REQUESTED;
        }
        // "AVAILABLE" without coordinates would be a false statement.
        return claimed == LocationStatus.AVAILABLE ? LocationStatus.UNAVAILABLE : claimed;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
