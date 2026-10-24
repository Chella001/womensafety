package com.womensafety.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One recipient's notification for one alert, stored at {@code emergencyAlerts/{alertId}/deliveries/{id}}.
 * The id is deterministic per recipient and channel, so planning the same alert twice cannot create a second message.
 * No full phone number is stored here: {@code recipientLabel} is masked, the real number is read from the contact at
 * send time.
 *
 * @param recipientReference contact id (CONTACT) or admin device id (ADMIN_DEVICE)
 * @param providerReference  the provider's message id once it accepted the message
 * @param deliveredAt        set ONLY from provider delivery evidence
 * @param failureCode        a short machine code, never a provider payload or personal data
 * @param attempts           bounded attempt history (at most {@code maxAttempts} entries)
 */
public record Delivery(
        String id,
        RecipientType recipientType,
        String recipientReference,
        String recipientLabel,
        Channel channel,
        DeliveryStatus status,
        Mode mode,
        int attemptCount,
        String providerReference,
        Instant lastAttemptAt,
        Instant nextAttemptAt,
        Instant deliveredAt,
        String failureCode,
        List<DeliveryAttempt> attempts,
        Instant createdAt,
        Instant updatedAt) {

    public enum RecipientType { CONTACT, ADMIN_DEVICE }

    public enum Channel { SMS, PUSH }

    /** TEST deliveries are simulated and must always be displayed as such. */
    public enum Mode { TEST, REAL }

    public record DeliveryAttempt(Instant at, String outcome, String code) {
    }

    public static Delivery queued(String id, RecipientType type, String reference, String label, Channel channel,
                                  Mode mode, Instant now) {
        return new Delivery(id, type, reference, label, channel, DeliveryStatus.QUEUED, mode, 0, null, null, now, null,
                null, List.of(), now, now);
    }

    public static Delivery skipped(String id, RecipientType type, String reference, String label, Channel channel,
                                   Mode mode, String code, Instant now) {
        return new Delivery(id, type, reference, label, channel, DeliveryStatus.SKIPPED, mode, 0, null, null, null,
                null, code, List.of(), now, now);
    }

    /** Persisted BEFORE the provider call, so a crash leaves a SENDING record instead of an invisible send. */
    public Delivery beginAttempt(Instant now) {
        return copy(DeliveryStatus.SENDING, attemptCount + 1, providerReference, now, nextAttemptAt, deliveredAt,
                failureCode, attempts, now);
    }

    /** Records the result of an attempt (appended to the bounded history). */
    public Delivery afterAttempt(DeliveryStatus newStatus, String newProviderReference, String code,
                                 Instant newNextAttemptAt, Instant now) {
        List<DeliveryAttempt> history = new ArrayList<>(attempts);
        history.add(new DeliveryAttempt(now, newStatus.name(), code));
        return copy(newStatus, attemptCount, newProviderReference != null ? newProviderReference : providerReference,
                lastAttemptAt, newNextAttemptAt, deliveredAt, code, history, now);
    }

    /** Provider receipt: delivered. Never moves a delivery backwards. */
    public Delivery receiptDelivered(Instant at, Instant now) {
        return copy(DeliveryStatus.DELIVERED, attemptCount, providerReference, lastAttemptAt, null, at, null,
                attempts, now);
    }

    public Delivery receiptFailed(String code, Instant now) {
        return copy(DeliveryStatus.FAILED, attemptCount, providerReference, lastAttemptAt, null, deliveredAt, code,
                attempts, now);
    }

    public Delivery withStatus(DeliveryStatus newStatus, String code, Instant now) {
        return copy(newStatus, attemptCount, providerReference, lastAttemptAt, null, deliveredAt, code, attempts, now);
    }

    private Delivery copy(DeliveryStatus newStatus, int count, String reference, Instant last, Instant next,
                          Instant delivered, String code, List<DeliveryAttempt> history, Instant now) {
        return new Delivery(id, recipientType, recipientReference, recipientLabel, channel, newStatus, mode, count,
                reference, last, next, delivered, code, List.copyOf(history), createdAt, now);
    }
}
