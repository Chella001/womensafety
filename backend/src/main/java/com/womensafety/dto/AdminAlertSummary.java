package com.womensafety.dto;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.womensafety.model.AlertStatus;
import com.womensafety.model.Delivery;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.NotificationSummary;

/**
 * An alert in the admin list. Coordinates and the full user id are deliberately absent: opening the detail reveals
 * them and is audited.
 *
 * @param userRef masked reference to the user (first characters of the uid)
 * @param flags   grounded attention flags, never an invented severity: UNACKNOWLEDGED (still new after the configured
 *                time), CONTACT_NOTIFICATION_PROBLEM, ADMIN_NOT_NOTIFIED
 */
public record AdminAlertSummary(
        String id, String userRef, String status, Instant createdAt, boolean hasLocation, String locationStatus,
        boolean testMode, NotificationView notification, String adminNotificationState, Instant acknowledgedAt,
        Instant resolvedAt, List<String> flags) {

    public static AdminAlertSummary from(EmergencyAlert a, Instant now, int attentionMinutes) {
        return new AdminAlertSummary(a.id(), userRef(a.uid()), a.status().name(), a.createdAt(), a.location() != null,
                a.locationStatus().name(), a.notificationMode() == Delivery.Mode.TEST,
                NotificationView.from(a.contactSummary(), a.notificationMode()), a.adminSummary().state().name(),
                a.acknowledgedAt(), a.resolvedAt(), flags(a, now, attentionMinutes));
    }

    public static String userRef(String uid) {
        if (uid == null) {
            return "—";
        }
        return uid.length() <= 8 ? uid : uid.substring(0, 8) + "…";
    }

    static List<String> flags(EmergencyAlert a, Instant now, int attentionMinutes) {
        List<String> flags = new ArrayList<>();
        if (a.status() == AlertStatus.ACTIVE && Duration.between(a.createdAt(), now).toMinutes() >= attentionMinutes) {
            flags.add("UNACKNOWLEDGED");
        }
        NotificationSummary contacts = a.contactSummary();
        if (contacts.needsAttention()) {
            flags.add("CONTACT_NOTIFICATION_PROBLEM");
        }
        NotificationSummary admins = a.adminSummary();
        if (admins.state() == NotificationSummary.State.FAILED || admins.state() == NotificationSummary.State.NO_RECIPIENTS) {
            flags.add("ADMIN_NOT_NOTIFIED");
        }
        return flags;
    }
}
