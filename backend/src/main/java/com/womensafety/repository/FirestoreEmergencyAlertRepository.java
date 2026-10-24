package com.womensafety.repository;

import static com.womensafety.repository.FirestoreSupport.await;
import static com.womensafety.repository.FirestoreSupport.awaitTransaction;
import static com.womensafety.repository.FirestoreSupport.toInstant;
import static com.womensafety.repository.FirestoreSupport.toTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.WriteBatch;
import com.womensafety.exception.DataStoreException;
import com.womensafety.exception.ResourceNotFoundException;
import com.womensafety.model.AlertLocation;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.Delivery;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.LocationStatus;
import com.womensafety.model.NotificationJob;
import com.womensafety.model.NotificationSummary;
import org.springframework.stereotype.Repository;

/** Alerts live in the top-level {@code emergencyAlerts} collection so administrators can review them. */
@Repository
public class FirestoreEmergencyAlertRepository implements EmergencyAlertRepository {

    static final String COLLECTION = "emergencyAlerts";
    private static final int MAX_BATCH_WRITES = 400; // Firestore allows 500 writes per batch

    private final Firestore firestore;

    public FirestoreEmergencyAlertRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public EmergencyAlert save(EmergencyAlert alert) {
        await(alerts().document(alert.id()).set(toDocument(alert)));
        return alert;
    }

    @Override
    public boolean createIfAbsent(EmergencyAlert alert, NotificationJob job) {
        WriteBatch batch = firestore.batch();
        batch.create(alerts().document(alert.id()), toDocument(alert));
        batch.create(firestore.collection(FirestoreNotificationJobRepository.COLLECTION).document(job.alertId()),
                FirestoreNotificationJobRepository.toDocument(job));
        try {
            await(batch.commit());
            return true;
        } catch (DataStoreException e) {
            if (FirestoreSupport.isAlreadyExists(e)) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public EmergencyAlert modify(String alertId, UnaryOperator<EmergencyAlert> mutator) {
        DocumentReference reference = alerts().document(alertId);
        return awaitTransaction(firestore.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(reference).get();
            if (!snapshot.exists()) {
                throw new ResourceNotFoundException("That alert does not exist.");
            }
            EmergencyAlert next = mutator.apply(toAlert(snapshot));
            transaction.set(reference, toDocument(next));
            return next;
        }));
    }

    @Override
    public Optional<EmergencyAlert> findById(String alertId) {
        DocumentSnapshot snapshot = await(alerts().document(alertId).get());
        return snapshot.exists() ? Optional.of(toAlert(snapshot)) : Optional.empty();
    }

    @Override
    public List<EmergencyAlert> findByUid(String uid) {
        return await(alerts().whereEqualTo("uid", uid).get()).getDocuments().stream()
                .map(FirestoreEmergencyAlertRepository::toAlert).toList();
    }

    @Override
    public List<EmergencyAlert> findRecent(int limit) {
        return await(alerts().orderBy("createdAt", Query.Direction.DESCENDING).limit(limit).get()).getDocuments()
                .stream().map(FirestoreEmergencyAlertRepository::toAlert).toList();
    }

    @Override
    public List<EmergencyAlert> findCreatedBetween(Instant from, Instant to, int limit) {
        return await(alerts().whereGreaterThanOrEqualTo("createdAt", toTimestamp(from))
                .whereLessThan("createdAt", toTimestamp(to))
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(limit).get()).getDocuments()
                .stream().map(FirestoreEmergencyAlertRepository::toAlert).toList();
    }

    @Override
    public long countByStatus(AlertStatus status) {
        return FirestoreSupport.count(alerts().whereEqualTo("status", status.name()));
    }

    @Override
    public long countAll() {
        return FirestoreSupport.count(alerts());
    }

    @Override
    public long countCreatedSince(Instant since) {
        return FirestoreSupport.count(alerts().whereGreaterThanOrEqualTo("createdAt", toTimestamp(since)));
    }

    @Override
    public List<EmergencyAlert> findExpired(Instant now, int limit) {
        return await(alerts().whereLessThan("retainUntil", toTimestamp(now))
                .orderBy("retainUntil", Query.Direction.ASCENDING).limit(limit).get()).getDocuments()
                .stream().map(FirestoreEmergencyAlertRepository::toAlert).toList();
    }

    @Override
    public void deleteWithHistory(String alertId) {
        DocumentReference alert = alerts().document(alertId);
        List<DocumentReference> doomed = new ArrayList<>();
        for (String sub : List.of("deliveries", "events")) {
            for (DocumentReference child : alert.collection(sub).listDocuments()) {
                doomed.add(child);
            }
        }
        doomed.add(firestore.collection(FirestoreNotificationJobRepository.COLLECTION).document(alertId));
        // Children first, the alert itself last: an interrupted purge leaves the alert visible and is simply retried.
        for (int from = 0; from < doomed.size(); from += MAX_BATCH_WRITES) {
            WriteBatch batch = firestore.batch();
            doomed.subList(from, Math.min(from + MAX_BATCH_WRITES, doomed.size())).forEach(batch::delete);
            await(batch.commit());
        }
        await(alert.delete());
    }

    private CollectionReference alerts() {
        return firestore.collection(COLLECTION);
    }

    static Map<String, Object> toDocument(EmergencyAlert a) {
        Map<String, Object> d = new HashMap<>();
        d.put("alertId", a.id());
        d.put("uid", a.uid());
        d.put("status", a.status().name());
        d.put("createdAt", toTimestamp(a.createdAt()));
        d.put("updatedAt", toTimestamp(a.updatedAt()));
        d.put("cancellationRequestedAt", toTimestamp(a.cancellationRequestedAt()));
        d.put("locationStatus", a.locationStatus().name());
        d.put("retainUntil", toTimestamp(a.retainUntil()));
        d.put("acknowledgedAt", toTimestamp(a.acknowledgedAt()));
        d.put("acknowledgedBy", a.acknowledgedBy());
        d.put("resolvedAt", toTimestamp(a.resolvedAt()));
        d.put("resolvedBy", a.resolvedBy());
        d.put("resolutionNote", a.resolutionNote());
        d.put("idempotencyKeyHash", a.idempotencyKeyHash());
        d.put("notificationMode", a.notificationMode().name());
        d.put("notificationSummary", summaryToMap(a.notificationSummary()));
        d.put("adminNotificationSummary", summaryToMap(a.adminNotificationSummary()));
        d.put("schemaVersion", (long) a.schemaVersion());
        if (a.location() != null) {
            Map<String, Object> l = new HashMap<>();
            l.put("latitude", a.location().latitude());
            l.put("longitude", a.location().longitude());
            l.put("accuracyMeters", a.location().accuracyMeters());
            l.put("capturedAt", toTimestamp(a.location().capturedAt()));
            d.put("location", l);
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    static EmergencyAlert toAlert(DocumentSnapshot s) {
        AlertLocation location = null;
        Object raw = s.get("location");
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> l = (Map<String, Object>) map;
            location = new AlertLocation(
                    ((Number) l.get("latitude")).doubleValue(),
                    ((Number) l.get("longitude")).doubleValue(),
                    l.get("accuracyMeters") == null ? null : ((Number) l.get("accuracyMeters")).doubleValue(),
                    toInstant((Timestamp) l.get("capturedAt")));
        }
        // Alerts written before notifications existed used "cancelledAt" and carry no summaries or location status.
        Instant cancellation = toInstant(s.getTimestamp("cancellationRequestedAt"));
        if (cancellation == null) {
            cancellation = toInstant(s.getTimestamp("cancelledAt"));
        }
        LocationStatus locationStatus = FirestoreUserRepository.parse(LocationStatus.class, s.getString("locationStatus"),
                location != null ? LocationStatus.AVAILABLE : LocationStatus.NOT_REQUESTED);
        return EmergencyAlert.builder()
                .id(s.getId())
                .uid(s.getString("uid"))
                .status(FirestoreUserRepository.parse(AlertStatus.class, s.getString("status"), AlertStatus.ACTIVE))
                .createdAt(toInstant(s.getTimestamp("createdAt")))
                .updatedAt(toInstant(s.getTimestamp("updatedAt")))
                .cancellationRequestedAt(cancellation)
                .location(location)
                .locationStatus(locationStatus)
                .retainUntil(toInstant(s.getTimestamp("retainUntil")))
                .acknowledgedAt(toInstant(s.getTimestamp("acknowledgedAt")))
                .acknowledgedBy(s.getString("acknowledgedBy"))
                .resolvedAt(toInstant(s.getTimestamp("resolvedAt")))
                .resolvedBy(s.getString("resolvedBy"))
                .resolutionNote(s.getString("resolutionNote"))
                .idempotencyKeyHash(s.getString("idempotencyKeyHash"))
                .notificationMode(FirestoreUserRepository.parse(Delivery.Mode.class, s.getString("notificationMode"), Delivery.Mode.TEST))
                .notificationSummary(summaryFromMap(s.get("notificationSummary")))
                .adminNotificationSummary(summaryFromMap(s.get("adminNotificationSummary")))
                .build();
    }

    static Map<String, Object> summaryToMap(NotificationSummary s) {
        if (s == null) {
            return null;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("state", s.state().name());
        m.put("total", (long) s.total());
        m.put("queued", (long) s.queued());
        m.put("submitted", (long) s.submitted());
        m.put("delivered", (long) s.delivered());
        m.put("simulated", (long) s.simulated());
        m.put("failed", (long) s.failed());
        m.put("skipped", (long) s.skipped());
        m.put("updatedAt", toTimestamp(s.updatedAt()));
        return m;
    }

    static NotificationSummary summaryFromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null; // legacy alert: reported as UNAVAILABLE by the model
        }
        return new NotificationSummary(
                FirestoreUserRepository.parse(NotificationSummary.State.class, (String) m.get("state"), NotificationSummary.State.UNAVAILABLE),
                num(m.get("total")), num(m.get("queued")), num(m.get("submitted")), num(m.get("delivered")),
                num(m.get("simulated")), num(m.get("failed")), num(m.get("skipped")),
                toInstant((Timestamp) m.get("updatedAt")));
    }

    private static int num(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
