package com.womensafety.model;

import java.time.Instant;
import java.util.Map;

/**
 * An administrative or security event in {@code adminAuditLogs}. {@code createdAt} is set by the Firestore
 * server when the entry is written (it is null on the object passed to the repository).
 *
 * @param outcome SUCCESS when the action completed, FAILURE when it was attempted but failed, DENIED when access was refused
 */
public record AuditLogEntry(
        String id, String type, String action, String actorUid, String targetUid,
        Map<String, String> details, Instant createdAt, String outcome) {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";
    public static final String DENIED = "DENIED";

    /** Convenience for the common case of a successful action. */
    public AuditLogEntry(String id, String type, String action, String actorUid, String targetUid,
                         Map<String, String> details, Instant createdAt) {
        this(id, type, action, actorUid, targetUid, details, createdAt, SUCCESS);
    }
}
