package com.womensafety.dto;

import java.time.Instant;

import com.womensafety.model.AlertLocation;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.Delivery;
import com.womensafety.model.EmergencyAlert;

/**
 * An alert as shown to its owner. Creation and notification are reported separately: {@code status} says the alert
 * exists, {@code notification} says what happened to the messages.
 *
 * @param testMode true when messages are only simulated (nothing is sent)
 * @param cancellationNote set after a cancellation, to say plainly that sent messages are not recalled
 */
public record AlertResponse(
        String id,
        String uid,
        String status,
        Instant createdAt,
        Instant cancellationRequestedAt,
        LocationView location,
        String locationStatus,
        boolean testMode,
        NotificationView notification,
        String cancellationNote) {

    public record LocationView(double latitude, double longitude, Double accuracyMeters, Instant capturedAt) {
        static LocationView from(AlertLocation l) {
            return l == null ? null : new LocationView(l.latitude(), l.longitude(), l.accuracyMeters(), l.capturedAt());
        }
    }

    public static AlertResponse from(EmergencyAlert a) {
        return new AlertResponse(a.id(), a.uid(), a.status().name(), a.createdAt(), a.cancellationRequestedAt(),
                LocationView.from(a.location()), a.locationStatus().name(), a.notificationMode() == Delivery.Mode.TEST,
                NotificationView.from(a.contactSummary(), a.notificationMode()),
                a.status() == AlertStatus.CANCELLED
                        ? "Cancellation recorded. Messages that were already sent cannot be recalled; messages not yet sent were stopped."
                        : null);
    }
}
