package com.womensafety.model;

import java.time.Instant;

/**
 * Outbox entry {@code notificationJobs/{alertId}}, written in the SAME batch as the alert, so an alert can never exist
 * without its notification work and a restart simply continues unfinished jobs. A worker owns a job only while
 * {@code leaseUntil} is in the future.
 */
public record NotificationJob(
        String alertId, State state, Instant nextRunAt, Instant leaseUntil, boolean planned, int runs, Instant createdAt) {

    public enum State { PENDING, PROCESSING }

    public static NotificationJob pending(String alertId, Instant now) {
        return new NotificationJob(alertId, State.PENDING, now, null, false, 0, now);
    }

    public NotificationJob claimed(Instant lease) {
        return new NotificationJob(alertId, State.PROCESSING, nextRunAt, lease, planned, runs + 1, createdAt);
    }

    public NotificationJob markPlanned() {
        return new NotificationJob(alertId, state, nextRunAt, leaseUntil, true, runs, createdAt);
    }

    public NotificationJob rescheduled(Instant next) {
        return new NotificationJob(alertId, State.PENDING, next, null, planned, runs, createdAt);
    }

    public boolean due(Instant now) {
        return (state == State.PENDING && !nextRunAt.isAfter(now))
                || (state == State.PROCESSING && leaseUntil != null && leaseUntil.isBefore(now));
    }
}
