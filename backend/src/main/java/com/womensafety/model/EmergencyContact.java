package com.womensafety.model;

import java.time.Instant;

/**
 * Stored at {@code users/{uid}/emergencyContacts/{id}}, so a contact belongs to exactly one user.
 *
 * <p>A contact receives real SOS messages only when it is BOTH {@code verified} (it proved it controls the number by
 * entering a code sent to it) AND {@code notifyConsent} (the user confirms the person agreed to receive them). The
 * user's own login number never verifies somebody else's number.</p>
 *
 * @param challenge a pending verification code (hashed); never exposed through any API
 */
public record EmergencyContact(
        String id, String name, String phoneNumber, String relationship, Instant createdAt, Instant updatedAt,
        boolean verified, Instant verifiedAt, boolean notifyConsent, Instant consentAt, Challenge challenge) {

    /** @param codeHash SHA-256 of contactId + ":" + code; sentInWindow/windowStart bound how many codes can be sent */
    public record Challenge(String codeHash, Instant expiresAt, int failedAttempts, int sentInWindow, Instant windowStart) {
    }

    /** A new, unverified contact without consent (the legacy shape). */
    public EmergencyContact(String id, String name, String phoneNumber, String relationship, Instant createdAt, Instant updatedAt) {
        this(id, name, phoneNumber, relationship, createdAt, updatedAt, false, null, false, null, null);
    }

    public boolean eligibleForNotifications() {
        return verified && notifyConsent;
    }

    public EmergencyContact withDetails(String newName, String newPhone, String newRelationship, Instant now) {
        boolean samePhone = newPhone.equals(phoneNumber);
        // A different number is a different person: verification, consent and any pending code do not carry over.
        return new EmergencyContact(id, newName, newPhone, newRelationship, createdAt, now,
                samePhone && verified, samePhone ? verifiedAt : null,
                samePhone && notifyConsent, samePhone ? consentAt : null, samePhone ? challenge : null);
    }

    public EmergencyContact withConsent(boolean consent, Instant now) {
        return new EmergencyContact(id, name, phoneNumber, relationship, createdAt, now, verified, verifiedAt,
                consent, consent ? now : null, challenge);
    }

    public EmergencyContact withChallenge(Challenge newChallenge, Instant now) {
        return new EmergencyContact(id, name, phoneNumber, relationship, createdAt, now, verified, verifiedAt,
                notifyConsent, consentAt, newChallenge);
    }

    public EmergencyContact markVerified(Instant now) {
        return new EmergencyContact(id, name, phoneNumber, relationship, createdAt, now, true, now, notifyConsent,
                consentAt, null);
    }
}
