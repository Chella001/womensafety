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
import com.google.cloud.firestore.Query;
import com.womensafety.model.EmergencyContact;
import org.springframework.stereotype.Repository;

/**
 * Stores contacts in {@code users/{uid}/emergencyContacts}. Because the uid is part of the path,
 * one user can never read or delete another user's contacts through this class.
 */
@Repository
public class FirestoreEmergencyContactRepository implements EmergencyContactRepository {

    static final String COLLECTION = "emergencyContacts";

    private final Firestore firestore;

    public FirestoreEmergencyContactRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public List<EmergencyContact> findAll(String uid) {
        var snapshot = await(contacts(uid).orderBy("createdAt", Query.Direction.ASCENDING).get());
        return snapshot.getDocuments().stream().map(FirestoreEmergencyContactRepository::toContact).toList();
    }

    @Override
    public Optional<EmergencyContact> findById(String uid, String contactId) {
        DocumentSnapshot snapshot = await(contacts(uid).document(contactId).get());
        return snapshot.exists() ? Optional.of(toContact(snapshot)) : Optional.empty();
    }

    @Override
    public EmergencyContact save(String uid, EmergencyContact contact) {
        await(contacts(uid).document(contact.id()).set(toDocument(contact)));
        return contact;
    }

    @Override
    public boolean delete(String uid, String contactId) {
        DocumentReference document = contacts(uid).document(contactId);
        if (!await(document.get()).exists()) {
            return false;
        }
        await(document.delete());
        return true;
    }

    private CollectionReference contacts(String uid) {
        return firestore.collection(FirestoreUserRepository.COLLECTION).document(uid).collection(COLLECTION);
    }

    static Map<String, Object> toDocument(EmergencyContact contact) {
        Map<String, Object> document = new HashMap<>();
        document.put("name", contact.name());
        document.put("phoneNumber", contact.phoneNumber());
        document.put("relationship", contact.relationship());
        document.put("createdAt", toTimestamp(contact.createdAt()));
        document.put("updatedAt", toTimestamp(contact.updatedAt()));
        document.put("verified", contact.verified());
        document.put("verifiedAt", toTimestamp(contact.verifiedAt()));
        document.put("notifyConsent", contact.notifyConsent());
        document.put("consentAt", toTimestamp(contact.consentAt()));
        EmergencyContact.Challenge c = contact.challenge();
        if (c != null) {
            Map<String, Object> challenge = new HashMap<>();
            challenge.put("codeHash", c.codeHash());
            challenge.put("expiresAt", toTimestamp(c.expiresAt()));
            challenge.put("failedAttempts", (long) c.failedAttempts());
            challenge.put("sentInWindow", (long) c.sentInWindow());
            challenge.put("windowStart", toTimestamp(c.windowStart()));
            document.put("challenge", challenge);
        }
        return document;
    }

    /** Contacts saved before verification existed have no such fields: they read as unverified and without consent. */
    static EmergencyContact toContact(DocumentSnapshot s) {
        EmergencyContact.Challenge challenge = null;
        if (s.get("challenge") instanceof Map<?, ?> raw) {
            challenge = new EmergencyContact.Challenge(
                    (String) raw.get("codeHash"),
                    toInstant((com.google.cloud.Timestamp) raw.get("expiresAt")),
                    raw.get("failedAttempts") instanceof Number n ? n.intValue() : 0,
                    raw.get("sentInWindow") instanceof Number n ? n.intValue() : 0,
                    toInstant((com.google.cloud.Timestamp) raw.get("windowStart")));
        }
        return new EmergencyContact(
                s.getId(),
                s.getString("name"),
                s.getString("phoneNumber"),
                s.getString("relationship"),
                toInstant(s.getTimestamp("createdAt")),
                toInstant(s.getTimestamp("updatedAt")),
                Boolean.TRUE.equals(s.getBoolean("verified")),
                toInstant(s.getTimestamp("verifiedAt")),
                Boolean.TRUE.equals(s.getBoolean("notifyConsent")),
                toInstant(s.getTimestamp("consentAt")),
                challenge);
    }
}
