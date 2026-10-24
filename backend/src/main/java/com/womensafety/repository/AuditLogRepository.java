package com.womensafety.repository;

import java.util.List;

import com.womensafety.model.AuditLogEntry;

public interface AuditLogRepository {

    /** Appends an entry; the creation time is assigned by the Firestore server, not by this application. */
    void append(AuditLogEntry entry);

    /** Newest entries first. */
    List<AuditLogEntry> findRecent(int limit);
}
