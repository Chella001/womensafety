package com.womensafety.dto;

import java.util.List;

/** Real counts read from Firestore; nothing here is estimated or pre-filled. Trend lists cover the last 14 days. */
public record AdminDashboardResponse(
        long totalUsers,
        long verifiedUsers,
        long activeUsers,
        long suspendedUsers,
        long totalAlerts,
        long alertsLast24Hours,
        long alertsNew,
        long alertsAcknowledged,
        long alertsResolved,
        long alertsCancelled,
        long openIncidentReports,
        List<DailyPoint> registrationsByDay,
        List<DailyPoint> alertsByDay,
        List<AdminAlertSummary> recentAlerts,
        List<AuditLogResponse> recentAuditLogs) {
}
