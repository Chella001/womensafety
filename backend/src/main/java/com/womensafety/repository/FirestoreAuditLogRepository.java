package com.womensafety.repository;

import static com.womensafety.repository.FirestoreSupport.await;
import static com.womensafety.repository.FirestoreSupport.toInstant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.womensafety.model.AuditLogEntry;
import org.springframework.stereotype.Repository;

/** Append-only audit trail in {@code adminAuditLogs}. Timestamps come from the Firestore server clock. */
@Repository
public class FirestoreAuditLogRepository implements AuditLogRepository {

    static final String COLLECTION = "adminAuditLogs";

    private final Firestore firestore;

    public FirestoreAuditLogRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void append(AuditLogEntry entry) {
        Map<String, Object> d = new HashMap<>();
        d.put("type", entry.type());
        d.put("action", entry.action());
        d.put("actorUid", entry.actorUid());
        d.put("targetUid", entry.targetUid());
        d.put("outcome", entry.outcome() == null ? AuditLogEntry.SUCCESS : entry.outcome());
        d.put("details", entry.details() == null ? Map.of() : entry.details());
        d.put("createdAt", FieldValue.serverTimestamp());
        await(logs().document(entry.id()).create(d));
    }

    @Override
    public List<AuditLogEntry> findRecent(int limit) {
        return await(logs().orderBy("createdAt", Query.Direction.DESCENDING).limit(limit).get()).getDocuments()
                .stream().map(FirestoreAuditLogRepository::toEntry).toList();
    }

    private CollectionReference logs() {
        return firestore.collection(COLLECTION);
    }

    @SuppressWarnings("unchecked")
    static AuditLogEntry toEntry(DocumentSnapshot s) {
        Object details = s.get("details");
        return new AuditLogEntry(
                s.getId(),
                s.getString("type"),
                s.getString("action"),
                s.getString("actorUid"),
                s.getString("targetUid"),
                details instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of(),
                toInstant(s.getTimestamp("createdAt")),
                s.getString("outcome") == null ? AuditLogEntry.SUCCESS : s.getString("outcome"));
    }
}
