package com.womensafety.repository;

import static com.womensafety.repository.FirestoreSupport.await;
import static com.womensafety.repository.FirestoreSupport.toInstant;
import static com.womensafety.repository.FirestoreSupport.toTimestamp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.womensafety.model.AlertEvent;
import org.springframework.stereotype.Repository;

@Repository
public class FirestoreAlertEventRepository implements AlertEventRepository {

    private static final String COLLECTION = "events";

    private final Firestore firestore;

    public FirestoreAlertEventRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void append(String alertId, AlertEvent event) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", event.type());
        m.put("actor", event.actor());
        m.put("at", toTimestamp(event.at()));
        m.put("detail", event.detail());
        await(events(alertId).document(event.id()).create(m));
    }

    @Override
    public List<AlertEvent> findAll(String alertId) {
        return await(events(alertId).orderBy("at", Query.Direction.ASCENDING).limit(200).get()).getDocuments().stream()
                .map(FirestoreAlertEventRepository::toEvent).toList();
    }

    private CollectionReference events(String alertId) {
        return firestore.collection(FirestoreEmergencyAlertRepository.COLLECTION).document(alertId).collection(COLLECTION);
    }

    @SuppressWarnings("unchecked")
    private static AlertEvent toEvent(DocumentSnapshot s) {
        Object detail = s.get("detail");
        return new AlertEvent(s.getId(), s.getString("type"), s.getString("actor"), toInstant(s.getTimestamp("at")),
                detail instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of());
    }
}
