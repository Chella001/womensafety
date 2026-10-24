package com.womensafety.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.womensafety.model.AlertEvent;
import com.womensafety.model.Delivery;
import com.womensafety.model.EmergencyAlert;

/**
 * Full alert record for an administrator (the location only if the user shared it). Opening it is audited BEFORE the
 * data is returned. {@code resolutionNote} is for administrators only.
 */
public record AdminAlertDetail(
        String id,
        String uid,
        String userRef,
        String status,
        Instant createdAt,
        Instant cancellationRequestedAt,
        Instant acknowledgedAt,
        String acknowledgedBy,
        Instant resolvedAt,
        String resolvedBy,
        String resolutionNote,
        AlertResponse.LocationView location,
        String locationStatus,
        boolean testMode,
        NotificationView notification,
        NotificationView adminNotification,
        List<String> flags,
        List<EventView> events) {

    public record EventView(String type, String actor, Instant at, Map<String, String> detail) {
        static EventView from(AlertEvent e) {
            return new EventView(e.type(), e.actor(), e.at(), e.detail());
        }
    }

    public static AdminAlertDetail from(EmergencyAlert a, List<AlertEvent> events, Instant now, int attentionMinutes) {
        return new AdminAlertDetail(a.id(), a.uid(), AdminAlertSummary.userRef(a.uid()), a.status().name(), a.createdAt(),
                a.cancellationRequestedAt(), a.acknowledgedAt(), a.acknowledgedBy(), a.resolvedAt(), a.resolvedBy(),
                a.resolutionNote(), AlertResponse.from(a).location(), a.locationStatus().name(),
                a.notificationMode() == Delivery.Mode.TEST,
                NotificationView.from(a.contactSummary(), a.notificationMode()),
                NotificationView.from(a.adminSummary(), Delivery.Mode.REAL),
                AdminAlertSummary.flags(a, now, attentionMinutes),
                events.stream().map(EventView::from).toList());
    }
}
