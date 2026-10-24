package com.womensafety.dto;

import java.time.Instant;
import java.util.List;

import com.womensafety.model.Delivery;

/**
 * One recipient's delivery as an administrator sees it: the recipient is masked (never a full phone number), the
 * status is the recorded one, and {@code statusLabel} says in words what that status does and does not prove.
 */
public record DeliveryView(
        String id, String recipientType, String recipient, String channel, String status, String statusLabel, String mode,
        int attemptCount, String providerReference, Instant lastAttemptAt, Instant nextAttemptAt, Instant deliveredAt,
        String failureCode, List<Attempt> attempts) {

    public record Attempt(Instant at, String outcome, String code) {
    }

    public static DeliveryView from(Delivery d) {
        return new DeliveryView(d.id(), d.recipientType().name(), d.recipientLabel(), d.channel().name(), d.status().name(),
                label(d), d.mode().name(), d.attemptCount(), d.providerReference(), d.lastAttemptAt(), d.nextAttemptAt(),
                d.deliveredAt(), d.failureCode(),
                d.attempts().stream().map(a -> new Attempt(a.at(), a.outcome(), a.code())).toList());
    }

    private static String label(Delivery d) {
        boolean push = d.channel() == Delivery.Channel.PUSH;
        return switch (d.status()) {
            case QUEUED -> "Waiting to be sent";
            case SENDING -> "Sending…";
            case RETRY_SCHEDULED -> "Attempt failed; a retry is scheduled";
            case SUBMITTED -> push ? "Accepted by the push service — not proof anyone saw it"
                    : "Accepted by the SMS provider — receipt not confirmed";
            case DELIVERED -> "Delivery confirmed by the provider";
            case SIMULATED -> "TEST MODE — simulated, nothing was sent";
            case FAILED -> "Failed";
            case SKIPPED -> "Not attempted";
            case CANCELLED -> "Stopped because the user cancelled the alert";
        };
    }
}
