package com.womensafety.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.womensafety.model.AlertStatus;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.NotificationJob;

public interface EmergencyAlertRepository {

    EmergencyAlert save(EmergencyAlert alert);

    /**
     * Atomically creates the alert together with its notification job (one batch), unless an alert with this id already
     * exists. The id is derived from the idempotency key, so a retry or double click can never create a second alert.
     *
     * @return true when created, false when it already existed (nothing was changed)
     */
    boolean createIfAbsent(EmergencyAlert alert, NotificationJob job);

    /**
     * Read-modify-write in ONE transaction, so two administrators (or an admin and a worker) cannot overwrite each
     * other. The mutator may throw a domain exception to abort.
     *
     * @throws com.womensafety.exception.ResourceNotFoundException when the alert does not exist
     */
    EmergencyAlert modify(String alertId, UnaryOperator<EmergencyAlert> mutator);

    Optional<EmergencyAlert> findById(String alertId);

    /** All alerts of one user (unordered; callers sort). Bounded in practice by the per-user rate limit. */
    List<EmergencyAlert> findByUid(String uid);

    /** Newest alerts first. */
    List<EmergencyAlert> findRecent(int limit);

    /** Alerts created in {@code [from, to)}, newest first, at most {@code limit}. Uses a single-field range query. */
    List<EmergencyAlert> findCreatedBetween(Instant from, Instant to, int limit);

    long countAll();

    long countByStatus(AlertStatus status);

    long countCreatedSince(Instant since);

    /** Alerts whose {@code retainUntil} is before {@code now}, oldest first, at most {@code limit}. */
    List<EmergencyAlert> findExpired(Instant now, int limit);

    /**
     * Permanently deletes one alert together with its {@code deliveries} and {@code events} subcollections and its
     * notification job. Used only by the retention purge.
     */
    void deleteWithHistory(String alertId);
}
