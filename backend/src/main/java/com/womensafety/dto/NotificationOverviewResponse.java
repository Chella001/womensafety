package com.womensafety.dto;

import java.time.Instant;
import java.util.List;

/**
 * What an administrator needs to judge whether notifications can work right now.
 *
 * @param mode               TEST (simulated SMS) or REAL
 * @param smsProvider        provider name; "mock" in TEST mode
 * @param fcmConfigured      whether this deployment can send push notifications at all
 * @param registeredDevices  administrator devices registered for push (all administrators)
 * @param yourDevices        devices registered by the calling administrator
 * @param openJobs           notification jobs still waiting or in progress
 * @param recentProblems     recent alerts whose notifications failed or only partly succeeded
 */
public record NotificationOverviewResponse(
        String mode, String smsProvider, boolean fcmConfigured, boolean adminPushEnabled, int registeredDevices,
        int yourDevices, long openJobs, String note, List<Problem> recentProblems) {

    public record Problem(String alertId, Instant createdAt, String contactState, String adminState) {
    }
}
