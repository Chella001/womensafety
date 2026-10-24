package com.womensafety.model;

import java.time.Instant;
import java.util.List;

/**
 * Aggregate of one group of deliveries (the emergency contacts, or the administrator devices) of an alert. It is
 * always derived from the delivery records, never set by hand, so it cannot claim more than the evidence shows.
 *
 * <ul>
 *   <li>QUEUED — the alert is saved; recipients have not been planned yet</li>
 *   <li>PROCESSING — at least one recipient is still being sent or retried</li>
 *   <li>NO_RECIPIENTS — nobody was eligible (no verified, consenting contact / no registered device)</li>
 *   <li>SIMULATED — TEST mode, nothing was sent</li>
 *   <li>SUBMITTED — the provider accepted the message(s); receipt is NOT confirmed</li>
 *   <li>DELIVERED — every attempted recipient has a provider delivery receipt</li>
 *   <li>PARTIAL — some recipients succeeded and some failed</li>
 *   <li>FAILED — every attempted recipient failed</li>
 *   <li>UNAVAILABLE — no notification information exists (alert created before notifications existed)</li>
 * </ul>
 */
public record NotificationSummary(
        State state, int total, int queued, int submitted, int delivered, int simulated, int failed, int skipped,
        Instant updatedAt) {

    public enum State { QUEUED, PROCESSING, NO_RECIPIENTS, SIMULATED, SUBMITTED, DELIVERED, PARTIAL, FAILED, UNAVAILABLE }

    public static NotificationSummary queued(Instant now) {
        return new NotificationSummary(State.QUEUED, 0, 0, 0, 0, 0, 0, 0, now);
    }

    public static NotificationSummary unavailable() {
        return new NotificationSummary(State.UNAVAILABLE, 0, 0, 0, 0, 0, 0, 0, null);
    }

    public static NotificationSummary of(List<Delivery> deliveries, Instant now) {
        int queued = 0, submitted = 0, delivered = 0, simulated = 0, failed = 0, skipped = 0, cancelled = 0;
        for (Delivery d : deliveries) {
            switch (d.status()) {
                case QUEUED, SENDING, RETRY_SCHEDULED -> queued++;
                case SUBMITTED -> submitted++;
                case DELIVERED -> delivered++;
                case SIMULATED -> simulated++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                case CANCELLED -> cancelled++;
            }
        }
        int attempted = submitted + delivered + simulated + failed;
        int total = deliveries.size();
        State state;
        if (queued > 0) {
            state = State.PROCESSING;
        } else if (attempted == 0) {
            state = State.NO_RECIPIENTS;
        } else if (failed == attempted) {
            state = State.FAILED;
        } else if (failed > 0) {
            state = State.PARTIAL;
        } else if (simulated == attempted) {
            state = State.SIMULATED;
        } else if (delivered == attempted) {
            state = State.DELIVERED;
        } else {
            state = State.SUBMITTED;
        }
        return new NotificationSummary(state, total, queued, submitted, delivered, simulated, failed, skipped + cancelled, now);
    }

    /** True when something went wrong that a person should look at. */
    public boolean needsAttention() {
        return state == State.FAILED || state == State.PARTIAL;
    }
}
