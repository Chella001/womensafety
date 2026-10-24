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

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.womensafety.model.NotificationJob;
import org.springframework.stereotype.Repository;

/**
 * Outbox in {@code notificationJobs}. Only the open jobs are queried (single-field "in" filter, no composite index);
 * the due-time check happens in memory because that set is tiny.
 */
@Repository
public class FirestoreNotificationJobRepository implements NotificationJobRepository {

    static final String COLLECTION = "notificationJobs";

    private final Firestore firestore;

    public FirestoreNotificationJobRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public List<NotificationJob> claimDue(Instant now, Instant leaseUntil, int limit) {
        List<NotificationJob> claimed = new ArrayList<>();
        for (QueryDocumentSnapshot candidate : await(jobs().whereIn("state", List.of("PENDING", "PROCESSING")).limit(100).get()).getDocuments()) {
            if (claimed.size() >= limit) {
                break;
            }
            if (!toJob(candidate).due(now)) {
                continue;
            }
            DocumentReference reference = candidate.getReference();
            // Re-check inside a transaction: if two instances race, exactly one of them wins the lease.
            NotificationJob taken = awaitTransaction(firestore.runTransaction(transaction -> {
                DocumentSnapshot fresh = transaction.get(reference).get();
                if (!fresh.exists() || !toJob(fresh).due(now)) {
                    return null;
                }
                NotificationJob leased = toJob(fresh).claimed(leaseUntil);
                transaction.set(reference, toDocument(leased));
                return leased;
            }));
            if (taken != null) {
                claimed.add(taken);
            }
        }
        return claimed;
    }

    @Override
    public Optional<NotificationJob> findById(String alertId) {
        DocumentSnapshot snapshot = await(jobs().document(alertId).get());
        return snapshot.exists() ? Optional.of(toJob(snapshot)) : Optional.empty();
    }

    @Override
    public void save(NotificationJob job) {
        await(jobs().document(job.alertId()).set(toDocument(job)));
    }

    @Override
    public void delete(String alertId) {
        await(jobs().document(alertId).delete());
    }

    @Override
    public long countOpen() {
        return FirestoreSupport.count(jobs());
    }

    private CollectionReference jobs() {
        return firestore.collection(COLLECTION);
    }

    static Map<String, Object> toDocument(NotificationJob job) {
        Map<String, Object> m = new HashMap<>();
        m.put("alertId", job.alertId());
        m.put("state", job.state().name());
        m.put("nextRunAt", toTimestamp(job.nextRunAt()));
        m.put("leaseUntil", toTimestamp(job.leaseUntil()));
        m.put("planned", job.planned());
        m.put("runs", (long) job.runs());
        m.put("createdAt", toTimestamp(job.createdAt()));
        return m;
    }

    static NotificationJob toJob(DocumentSnapshot s) {
        Long runs = s.getLong("runs");
        return new NotificationJob(
                s.getId(),
                FirestoreUserRepository.parse(NotificationJob.State.class, s.getString("state"), NotificationJob.State.PENDING),
                toInstant(s.getTimestamp("nextRunAt")),
                toInstant(s.getTimestamp("leaseUntil")),
                Boolean.TRUE.equals(s.getBoolean("planned")),
                runs == null ? 0 : runs.intValue(),
                toInstant(s.getTimestamp("createdAt")));
    }
}
