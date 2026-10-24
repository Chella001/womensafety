package com.womensafety.dto;

import java.time.Instant;

import com.womensafety.model.EmergencyContact;

/**
 * A contact as shown to its OWNER (the full number is the owner's own data). {@code eligible} is true only when the
 * contact is verified AND the owner confirmed consent; only then may it receive SOS messages.
 */
public record EmergencyContactResponse(
        String id, String name, String phoneNumber, String relationship, Instant createdAt, Instant updatedAt,
        boolean verified, Instant verifiedAt, boolean notifyConsent, boolean eligible, boolean verificationPending) {

    public static EmergencyContactResponse from(EmergencyContact c) {
        return from(c, Instant.now());
    }

    public static EmergencyContactResponse from(EmergencyContact c, Instant now) {
        boolean pending = c.challenge() != null && c.challenge().expiresAt() != null && c.challenge().expiresAt().isAfter(now);
        return new EmergencyContactResponse(c.id(), c.name(), c.phoneNumber(), c.relationship(), c.createdAt(), c.updatedAt(),
                c.verified(), c.verifiedAt(), c.notifyConsent(), c.eligibleForNotifications(), pending);
    }
}
