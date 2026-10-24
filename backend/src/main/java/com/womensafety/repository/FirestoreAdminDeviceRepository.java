package com.womensafety.repository;

import static com.womensafety.repository.FirestoreSupport.await;
import static com.womensafety.repository.FirestoreSupport.toInstant;
import static com.womensafety.repository.FirestoreSupport.toTimestamp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.womensafety.model.AdminDevice;
import org.springframework.stereotype.Repository;

@Repository
public class FirestoreAdminDeviceRepository implements AdminDeviceRepository {

    private static final String COLLECTION = "adminDevices";

    private final Firestore firestore;

    public FirestoreAdminDeviceRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void save(AdminDevice device) {
        Map<String, Object> m = new HashMap<>();
        m.put("uid", device.uid());
        m.put("token", device.token());
        m.put("label", device.label());
        m.put("createdAt", toTimestamp(device.createdAt()));
        m.put("lastSeenAt", toTimestamp(device.lastSeenAt()));
        await(devices().document(device.id()).set(m));
    }

    @Override
    public Optional<AdminDevice> findById(String id) {
        DocumentSnapshot snapshot = await(devices().document(id).get());
        return snapshot.exists() ? Optional.of(toDevice(snapshot)) : Optional.empty();
    }

    @Override
    public List<AdminDevice> findAll() {
        return await(devices().limit(500).get()).getDocuments().stream().map(FirestoreAdminDeviceRepository::toDevice).toList();
    }

    @Override
    public List<AdminDevice> findByUid(String uid) {
        return await(devices().whereEqualTo("uid", uid).get()).getDocuments().stream()
                .map(FirestoreAdminDeviceRepository::toDevice).toList();
    }

    @Override
    public boolean delete(String id) {
        DocumentReference reference = devices().document(id);
        if (!await(reference.get()).exists()) {
            return false;
        }
        await(reference.delete());
        return true;
    }

    private CollectionReference devices() {
        return firestore.collection(COLLECTION);
    }

    private static AdminDevice toDevice(DocumentSnapshot s) {
        return new AdminDevice(s.getId(), s.getString("uid"), s.getString("token"), s.getString("label"),
                toInstant(s.getTimestamp("createdAt")), toInstant(s.getTimestamp("lastSeenAt")));
    }
}
