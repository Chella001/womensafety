package com.womensafety.repository;

import static com.womensafety.repository.FirestoreSupport.await;
import static com.womensafety.repository.FirestoreSupport.toInstant;
import static com.womensafety.repository.FirestoreSupport.toTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.womensafety.exception.DataStoreException;
import com.womensafety.model.Delivery;
import com.womensafety.model.DeliveryStatus;
import org.springframework.stereotype.Repository;

@Repository
public class FirestoreDeliveryRepository implements DeliveryRepository {

    private static final String COLLECTION = "deliveries";

    private final Firestore firestore;

    public FirestoreDeliveryRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public boolean createIfAbsent(String alertId, Delivery delivery) {
        try {
            await(deliveries(alertId).document(delivery.id()).create(toDocument(delivery)));
            return true;
        } catch (DataStoreException e) {
            if (FirestoreSupport.isAlreadyExists(e)) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Delivery save(String alertId, Delivery delivery) {
        await(deliveries(alertId).document(delivery.id()).set(toDocument(delivery)));
        return delivery;
    }

    @Override
    public Optional<Delivery> findById(String alertId, String deliveryId) {
        DocumentSnapshot snapshot = await(deliveries(alertId).document(deliveryId).get());
        return snapshot.exists() ? Optional.of(toDelivery(snapshot)) : Optional.empty();
    }

    @Override
    public List<Delivery> findAll(String alertId) {
        return await(deliveries(alertId).get()).getDocuments().stream()
                .map(FirestoreDeliveryRepository::toDelivery)
                .sorted(java.util.Comparator.comparing(Delivery::createdAt).thenComparing(Delivery::id))
                .toList();
    }

    private CollectionReference deliveries(String alertId) {
        return firestore.collection(FirestoreEmergencyAlertRepository.COLLECTION).document(alertId).collection(COLLECTION);
    }

    static Map<String, Object> toDocument(Delivery d) {
        Map<String, Object> m = new HashMap<>();
        m.put("recipientType", d.recipientType().name());
        m.put("recipientReference", d.recipientReference());
        m.put("recipientLabel", d.recipientLabel());
        m.put("channel", d.channel().name());
        m.put("deliveryStatus", d.status().name());
        m.put("mode", d.mode().name());
        m.put("attemptCount", (long) d.attemptCount());
        m.put("providerReference", d.providerReference());
        m.put("lastAttemptAt", toTimestamp(d.lastAttemptAt()));
        m.put("nextAttemptAt", toTimestamp(d.nextAttemptAt()));
        m.put("deliveredAt", toTimestamp(d.deliveredAt()));
        m.put("failureCode", d.failureCode());
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (Delivery.DeliveryAttempt a : d.attempts()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("at", toTimestamp(a.at()));
            entry.put("outcome", a.outcome());
            entry.put("code", a.code());
            attempts.add(entry);
        }
        m.put("attempts", attempts);
        m.put("createdAt", toTimestamp(d.createdAt()));
        m.put("updatedAt", toTimestamp(d.updatedAt()));
        return m;
    }

    @SuppressWarnings("unchecked")
    static Delivery toDelivery(DocumentSnapshot s) {
        List<Delivery.DeliveryAttempt> attempts = new ArrayList<>();
        Object raw = s.get("attempts");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> entry = (Map<String, Object>) item;
                attempts.add(new Delivery.DeliveryAttempt(toInstant((Timestamp) entry.get("at")),
                        (String) entry.get("outcome"), (String) entry.get("code")));
            }
        }
        Long count = s.getLong("attemptCount");
        Instant created = toInstant(s.getTimestamp("createdAt"));
        return new Delivery(
                s.getId(),
                FirestoreUserRepository.parse(Delivery.RecipientType.class, s.getString("recipientType"), Delivery.RecipientType.CONTACT),
                s.getString("recipientReference"),
                s.getString("recipientLabel"),
                FirestoreUserRepository.parse(Delivery.Channel.class, s.getString("channel"), Delivery.Channel.SMS),
                FirestoreUserRepository.parse(DeliveryStatus.class, s.getString("deliveryStatus"), DeliveryStatus.FAILED),
                FirestoreUserRepository.parse(Delivery.Mode.class, s.getString("mode"), Delivery.Mode.TEST),
                count == null ? 0 : count.intValue(),
                s.getString("providerReference"),
                toInstant(s.getTimestamp("lastAttemptAt")),
                toInstant(s.getTimestamp("nextAttemptAt")),
                toInstant(s.getTimestamp("deliveredAt")),
                s.getString("failureCode"),
                attempts,
                created == null ? Instant.EPOCH : created,
                toInstant(s.getTimestamp("updatedAt")));
    }
}
