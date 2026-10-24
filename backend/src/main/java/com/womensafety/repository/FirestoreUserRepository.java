package com.womensafety.repository;

import static com.womensafety.repository.FirestoreSupport.await;
import static com.womensafety.repository.FirestoreSupport.toInstant;
import static com.womensafety.repository.FirestoreSupport.toTimestamp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.womensafety.exception.DataStoreException;
import com.womensafety.model.AccountStatus;
import com.womensafety.model.Role;
import com.womensafety.model.UserProfile;
import org.springframework.stereotype.Repository;

/**
 * Stores profiles in the Firestore {@code users} collection, keyed by Firebase uid.
 * Only the backend talks to Firestore; Firestore security rules deny all client access.
 */
@Repository
public class FirestoreUserRepository implements UserRepository {

    static final String COLLECTION = "users";

    private final Firestore firestore;

    public FirestoreUserRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<UserProfile> findByUid(String uid) {
        DocumentSnapshot snapshot = await(users().document(uid).get());
        return snapshot.exists() ? Optional.of(toProfile(snapshot)) : Optional.empty();
    }

    @Override
    public boolean createIfAbsent(UserProfile profile) {
        try {
            await(users().document(profile.uid()).create(toDocument(profile)));
            return true;
        } catch (DataStoreException e) {
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof AlreadyExistsException) {
                    return false;
                }
            }
            throw e;
        }
    }

    @Override
    public UserProfile save(UserProfile profile) {
        await(users().document(profile.uid()).set(toDocument(profile)));
        return profile;
    }

    @Override
    public List<UserProfile> findRecent(int limit) {
        var snapshot = await(users().orderBy("createdAt", Query.Direction.DESCENDING).limit(limit).get());
        return snapshot.getDocuments().stream().map(FirestoreUserRepository::toProfile).toList();
    }

    @Override
    public List<UserProfile> findCreatedSince(java.time.Instant since, int limit) {
        var snapshot = await(users().whereGreaterThanOrEqualTo("createdAt", toTimestamp(since))
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(limit).get());
        return snapshot.getDocuments().stream().map(FirestoreUserRepository::toProfile).toList();
    }

    @Override
    public long countAll() {
        return FirestoreSupport.count(users());
    }

    @Override
    public long countByStatus(AccountStatus status) {
        return FirestoreSupport.count(users().whereEqualTo("accountStatus", status.name()));
    }

    @Override
    public long countPhoneVerified() {
        return FirestoreSupport.count(users().whereEqualTo("phoneVerified", true));
    }

    private CollectionReference users() {
        return firestore.collection(COLLECTION);
    }

    static Map<String, Object> toDocument(UserProfile profile) {
        Map<String, Object> document = new HashMap<>();
        document.put("fullName", profile.fullName());
        document.put("phoneNumber", profile.phoneNumber());
        document.put("phoneVerified", profile.phoneVerified());
        document.put("role", profile.role().name());
        document.put("accountStatus", profile.accountStatus().name());
        document.put("createdAt", toTimestamp(profile.createdAt()));
        document.put("updatedAt", toTimestamp(profile.updatedAt()));
        document.put("lastLoginAt", toTimestamp(profile.lastLoginAt()));
        document.put("consentVersion", profile.consentVersion());
        document.put("consentTimestamp", toTimestamp(profile.consentTimestamp()));
        document.put("sosDrillCompletedAt", toTimestamp(profile.sosDrillCompletedAt()));
        return document;
    }

    static UserProfile toProfile(DocumentSnapshot s) {
        return new UserProfile(
                s.getId(),
                firstNonNull(s.getString("fullName"), s.getString("displayName"), ""),
                firstNonNull(s.getString("phoneNumber"), ""),
                Boolean.TRUE.equals(s.getBoolean("phoneVerified")),
                parse(Role.class, s.getString("role"), Role.USER),
                parse(AccountStatus.class, s.getString("accountStatus"), AccountStatus.ACTIVE),
                toInstant(s.getTimestamp("createdAt")),
                toInstant(s.getTimestamp("updatedAt")),
                toInstant(s.getTimestamp("lastLoginAt")),
                s.getString("consentVersion"),
                toInstant(s.getTimestamp("consentTimestamp")),
                toInstant(s.getTimestamp("sosDrillCompletedAt")));
    }

    /** Profiles written by the first (e-mail based) version have {@code displayName} instead of {@code fullName}. */
    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
