package com.womensafety.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.womensafety.model.NotificationJob;

/** The notification outbox ({@code notificationJobs/{alertId}}). */
public interface NotificationJobRepository {

    /**
     * Atomically takes ownership of due jobs: PENDING ones whose time has come, and PROCESSING ones whose lease expired
     * (their worker died). Each returned job is leased until {@code leaseUntil}.
     */
    List<NotificationJob> claimDue(Instant now, Instant leaseUntil, int limit);

    Optional<NotificationJob> findById(String alertId);

    void save(NotificationJob job);

    /** The job is finished: nothing is left to do for this alert. */
    void delete(String alertId);

    long countOpen();
}
