package com.womensafety.dto;

import java.time.Instant;

import com.womensafety.model.IncidentReport;

public record IncidentReportResponse(
        String id,
        String uid,
        String category,
        String description,
        Instant occurredAt,
        String status,
        Instant createdAt,
        Instant resolvedAt,
        String resolutionNote) {

    public static IncidentReportResponse from(IncidentReport r) {
        return new IncidentReportResponse(r.id(), r.uid(), r.category(), r.description(), r.occurredAt(),
                r.status().name(), r.createdAt(), r.resolvedAt(), r.resolutionNote());
    }
}
