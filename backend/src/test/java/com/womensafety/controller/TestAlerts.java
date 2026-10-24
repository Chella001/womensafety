package com.womensafety.controller;

import java.time.Duration;
import java.time.Instant;

import com.womensafety.model.AlertLocation;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.Delivery;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.LocationStatus;
import com.womensafety.model.NotificationSummary;

/** Test fixtures for alerts. */
final class TestAlerts {

    private TestAlerts() {
    }

    static EmergencyAlert alert(String id, String uid, AlertStatus status, Instant createdAt, AlertLocation location) {
        return EmergencyAlert.builder()
                .id(id)
                .uid(uid)
                .status(status)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .location(location)
                .locationStatus(location == null ? LocationStatus.NOT_REQUESTED : LocationStatus.AVAILABLE)
                .retainUntil(createdAt.plus(Duration.ofDays(90)))
                .notificationMode(Delivery.Mode.TEST)
                .notificationSummary(NotificationSummary.queued(createdAt))
                .adminNotificationSummary(NotificationSummary.queued(createdAt))
                .build();
    }
}
