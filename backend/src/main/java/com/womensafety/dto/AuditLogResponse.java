package com.womensafety.dto;

import java.time.Instant;
import java.util.Map;

import com.womensafety.model.AuditLogEntry;

public record AuditLogResponse(
        String id, String type, String action, String actorUid, String targetUid, Map<String, String> details,
        Instant createdAt, String outcome) {

    public static AuditLogResponse from(AuditLogEntry e) {
        return new AuditLogResponse(e.id(), e.type(), e.action(), e.actorUid(), e.targetUid(), e.details(),
                e.createdAt(), e.outcome());
    }
}
