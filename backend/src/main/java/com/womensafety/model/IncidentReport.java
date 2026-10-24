package com.womensafety.model;

import java.time.Instant;

/** Stored in {@code incidentReports/{id}}. */
public record IncidentReport(
        String id,
        String uid,
        String category,
        String description,
        Instant occurredAt,
        ReportStatus status,
        Instant createdAt,
        Instant resolvedAt,
        String resolvedBy,
        String resolutionNote) {

    public IncidentReport resolved(String adminUid, String note, Instant now) {
        return new IncidentReport(id, uid, category, description, occurredAt, ReportStatus.RESOLVED, createdAt, now,
                adminUid, note);
    }
}
