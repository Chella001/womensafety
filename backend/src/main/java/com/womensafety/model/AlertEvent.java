package com.womensafety.model;

import java.time.Instant;
import java.util.Map;

/**
 * One entry in an alert's history ({@code emergencyAlerts/{id}/events/{eventId}}), written only by the server.
 * {@code actor} is "user", "system" or an administrator uid; {@code detail} holds short non-sensitive facts (no phone
 * numbers, no coordinates, no notes).
 */
public record AlertEvent(String id, String type, String actor, Instant at, Map<String, String> detail) {

    public static final String CREATED = "ALERT_CREATED";
    public static final String NOTIFICATIONS_PLANNED = "NOTIFICATIONS_PLANNED";
    public static final String NOTIFICATIONS_UPDATED = "NOTIFICATIONS_UPDATED";
    public static final String DELIVERY_RECEIPT = "DELIVERY_RECEIPT";
    public static final String ACKNOWLEDGED = "ALERT_ACKNOWLEDGED";
    public static final String RESOLVED = "ALERT_RESOLVED";
    public static final String CANCELLATION_REQUESTED = "CANCELLATION_REQUESTED";
}
