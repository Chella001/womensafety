package com.womensafety.model;

import java.time.Instant;

/**
 * An SOS alert stored in {@code emergencyAlerts/{id}}. Creating it is separate from notifying anybody: what was
 * actually attempted and what the providers reported lives in the per-recipient delivery records, and
 * {@code notificationSummary} / {@code adminNotificationSummary} are derived from them.
 *
 * @param status                    ACTIVE is shown as "new"; see {@link AlertStatus}
 * @param cancellationRequestedAt   when the user asked to cancel. This does NOT recall messages already sent.
 * @param location                  null unless the user explicitly shared it
 * @param locationStatus            why the location is, or is not, present
 * @param retainUntil               when the record becomes eligible for deletion
 * @param resolutionNote            the administrator's note (never shown to the alert's owner)
 * @param idempotencyKeyHash        SHA-256 of the client's Idempotency-Key (the key itself is not stored)
 * @param notificationMode          TEST (simulated) or REAL, fixed when the alert was created
 * @param notificationSummary       emergency-contact notifications
 * @param adminNotificationSummary  administrator push notifications (kept separate from the contacts)
 */
public record EmergencyAlert(
        String id,
        String uid,
        AlertStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant cancellationRequestedAt,
        AlertLocation location,
        LocationStatus locationStatus,
        Instant retainUntil,
        Instant acknowledgedAt,
        String acknowledgedBy,
        Instant resolvedAt,
        String resolvedBy,
        String resolutionNote,
        String idempotencyKeyHash,
        Delivery.Mode notificationMode,
        NotificationSummary notificationSummary,
        NotificationSummary adminNotificationSummary,
        int schemaVersion) {

    public static final int SCHEMA_VERSION = 2;

    public EmergencyAlert cancelled(Instant now) {
        return toBuilder().status(AlertStatus.CANCELLED).updatedAt(now).cancellationRequestedAt(now).build();
    }

    public EmergencyAlert acknowledged(String adminUid, Instant now) {
        return toBuilder().status(AlertStatus.ACKNOWLEDGED).updatedAt(now).acknowledgedAt(now).acknowledgedBy(adminUid).build();
    }

    public EmergencyAlert resolved(String adminUid, String note, Instant now) {
        return toBuilder().status(AlertStatus.RESOLVED).updatedAt(now).resolvedAt(now).resolvedBy(adminUid).resolutionNote(note).build();
    }

    public EmergencyAlert withSummaries(NotificationSummary contacts, NotificationSummary admins, Instant now) {
        return toBuilder().updatedAt(now).notificationSummary(contacts).adminNotificationSummary(admins).build();
    }

    public NotificationSummary contactSummary() {
        return notificationSummary == null ? NotificationSummary.unavailable() : notificationSummary;
    }

    public NotificationSummary adminSummary() {
        return adminNotificationSummary == null ? NotificationSummary.unavailable() : adminNotificationSummary;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder(null);
    }

    /** Small copy-on-write helper; a record has no "with" methods and this one has many components. */
    public static final class Builder {
        private String id;
        private String uid;
        private AlertStatus status = AlertStatus.ACTIVE;
        private Instant createdAt;
        private Instant updatedAt;
        private Instant cancellationRequestedAt;
        private AlertLocation location;
        private LocationStatus locationStatus = LocationStatus.NOT_REQUESTED;
        private Instant retainUntil;
        private Instant acknowledgedAt;
        private String acknowledgedBy;
        private Instant resolvedAt;
        private String resolvedBy;
        private String resolutionNote;
        private String idempotencyKeyHash;
        private Delivery.Mode notificationMode = Delivery.Mode.TEST;
        private NotificationSummary notificationSummary;
        private NotificationSummary adminNotificationSummary;
        private int schemaVersion = SCHEMA_VERSION;

        private Builder(EmergencyAlert a) {
            if (a != null) {
                id = a.id;
                uid = a.uid;
                status = a.status;
                createdAt = a.createdAt;
                updatedAt = a.updatedAt;
                cancellationRequestedAt = a.cancellationRequestedAt;
                location = a.location;
                locationStatus = a.locationStatus;
                retainUntil = a.retainUntil;
                acknowledgedAt = a.acknowledgedAt;
                acknowledgedBy = a.acknowledgedBy;
                resolvedAt = a.resolvedAt;
                resolvedBy = a.resolvedBy;
                resolutionNote = a.resolutionNote;
                idempotencyKeyHash = a.idempotencyKeyHash;
                notificationMode = a.notificationMode;
                notificationSummary = a.notificationSummary;
                adminNotificationSummary = a.adminNotificationSummary;
                schemaVersion = a.schemaVersion;
            }
        }

        public Builder id(String v) { id = v; return this; }
        public Builder uid(String v) { uid = v; return this; }
        public Builder status(AlertStatus v) { status = v; return this; }
        public Builder createdAt(Instant v) { createdAt = v; return this; }
        public Builder updatedAt(Instant v) { updatedAt = v; return this; }
        public Builder cancellationRequestedAt(Instant v) { cancellationRequestedAt = v; return this; }
        public Builder location(AlertLocation v) { location = v; return this; }
        public Builder locationStatus(LocationStatus v) { locationStatus = v; return this; }
        public Builder retainUntil(Instant v) { retainUntil = v; return this; }
        public Builder acknowledgedAt(Instant v) { acknowledgedAt = v; return this; }
        public Builder acknowledgedBy(String v) { acknowledgedBy = v; return this; }
        public Builder resolvedAt(Instant v) { resolvedAt = v; return this; }
        public Builder resolvedBy(String v) { resolvedBy = v; return this; }
        public Builder resolutionNote(String v) { resolutionNote = v; return this; }
        public Builder idempotencyKeyHash(String v) { idempotencyKeyHash = v; return this; }
        public Builder notificationMode(Delivery.Mode v) { notificationMode = v; return this; }
        public Builder notificationSummary(NotificationSummary v) { notificationSummary = v; return this; }
        public Builder adminNotificationSummary(NotificationSummary v) { adminNotificationSummary = v; return this; }

        public EmergencyAlert build() {
            return new EmergencyAlert(id, uid, status, createdAt, updatedAt, cancellationRequestedAt, location,
                    locationStatus, retainUntil, acknowledgedAt, acknowledgedBy, resolvedAt, resolvedBy, resolutionNote,
                    idempotencyKeyHash, notificationMode, notificationSummary, adminNotificationSummary, schemaVersion);
        }
    }
}
