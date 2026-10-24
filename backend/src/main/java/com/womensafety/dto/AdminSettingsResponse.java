package com.womensafety.dto;

import java.util.List;

/** Read-only view of the server's policy settings. Contains no credentials and no administrator uids. */
public record AdminSettingsResponse(
        int administratorCount,
        int alertRetentionDays,
        String consentVersion,
        boolean rateLimitEnabled,
        int authRequestsPerMinute,
        int apiRequestsPerMinute,
        int maxAlertsPerWindow,
        int alertWindowMinutes,
        boolean tokenRevocationCheck,
        List<String> allowedOrigins,
        String notificationProvider,
        String notificationNote,
        String notificationMode,
        int maxEmergencyContacts,
        int notificationMaxAttempts,
        boolean adminPushEnabled) {
}
