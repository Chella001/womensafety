package com.womensafety.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.womensafety.model.AuditLogEntry;
import com.womensafety.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

/**
 * Writes the append-only audit trail. There is deliberately no update or delete operation anywhere in the code, and
 * Firestore rules deny all client access. Never pass tokens, OTPs or other secrets in {@code details}.
 * The creation time is assigned by Firestore's server clock inside the repository.
 */
@Service
public class AuditService {

    public static final String TYPE_ADMIN_ACTION = "ADMIN_ACTION";
    public static final String TYPE_SECURITY_EVENT = "SECURITY_EVENT";
    /** Automatic work done by the server itself (for example notification processing), recorded with actor "system". */
    public static final String TYPE_SYSTEM_EVENT = "SYSTEM_EVENT";
    public static final String SYSTEM_ACTOR = "system";

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void recordAdminAction(String actorUid, String action, String targetUid, Map<String, String> details) {
        append(TYPE_ADMIN_ACTION, action, actorUid, targetUid, details, AuditLogEntry.SUCCESS);
    }

    /** For an action that was attempted but did not complete (for example the Firebase update failed). */
    public void recordFailedAdminAction(String actorUid, String action, String targetUid, Map<String, String> details) {
        append(TYPE_ADMIN_ACTION, action, actorUid, targetUid, details, AuditLogEntry.FAILURE);
    }

    /** An access attempt that was refused, e.g. a regular user calling an admin endpoint. */
    public void recordSecurityEvent(String action, String actorUid, Map<String, String> details) {
        append(TYPE_SECURITY_EVENT, action, actorUid, null, details, AuditLogEntry.DENIED);
    }

    /** Outcome of work the server did on its own; {@code outcome} is SUCCESS or FAILURE. */
    public void recordSystemEvent(String action, String targetUid, Map<String, String> details, boolean success) {
        append(TYPE_SYSTEM_EVENT, action, SYSTEM_ACTOR, targetUid, details, success ? AuditLogEntry.SUCCESS : AuditLogEntry.FAILURE);
    }

    public List<AuditLogEntry> recent(int limit) {
        return repository.findRecent(limit);
    }

    private void append(String type, String action, String actorUid, String targetUid, Map<String, String> details,
                        String outcome) {
        repository.append(new AuditLogEntry(UUID.randomUUID().toString(), type, action, actorUid, targetUid,
                details == null ? Map.of() : details, null, outcome));
    }
}
