package com.womensafety.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Product policy values.
 *
 * @param consentVersion       version of the terms and privacy policy users must accept at registration
 * @param alertRetentionDays   how long an alert record is kept before it becomes eligible for deletion
 * @param maxAlertsPerWindow   SOS alerts one user may create per window (abuse protection)
 * @param alertWindowMinutes   length of that window
 * @param maxEmergencyContacts emergency contacts one user may keep
 * @param retentionPurgeEnabled delete alerts (with their deliveries and history) once {@code retainUntil} has passed
 */
@ConfigurationProperties(prefix = "app.policy")
public record PolicyProperties(
        @DefaultValue("2026-10-v1") String consentVersion,
        @DefaultValue("90") int alertRetentionDays,
        @DefaultValue("5") int maxAlertsPerWindow,
        @DefaultValue("10") int alertWindowMinutes,
        @DefaultValue("5") int maxEmergencyContacts,
        @DefaultValue("true") boolean retentionPurgeEnabled) {
}
