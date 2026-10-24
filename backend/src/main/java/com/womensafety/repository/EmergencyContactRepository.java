package com.womensafety.repository;

import java.util.List;
import java.util.Optional;

import com.womensafety.model.EmergencyContact;

/** Persistence port for a user's emergency contacts. Every method is scoped to one user. */
public interface EmergencyContactRepository {

    /** The user's contacts, oldest first. */
    List<EmergencyContact> findAll(String uid);

    Optional<EmergencyContact> findById(String uid, String contactId);

    /** Creates or replaces the contact. */
    EmergencyContact save(String uid, EmergencyContact contact);

    /** @return true when a contact was deleted, false when it did not exist for this user */
    boolean delete(String uid, String contactId);
}
