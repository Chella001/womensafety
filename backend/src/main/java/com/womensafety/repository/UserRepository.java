package com.womensafety.repository;

import java.util.List;
import java.util.Optional;

import com.womensafety.model.AccountStatus;
import com.womensafety.model.UserProfile;

/** Persistence port for user profiles. */
public interface UserRepository {

    Optional<UserProfile> findByUid(String uid);

    /**
     * Atomically creates the profile.
     *
     * @return false (and changes nothing) when a profile already exists for {@code profile.uid()}
     */
    boolean createIfAbsent(UserProfile profile);

    /** Replaces an existing profile. */
    UserProfile save(UserProfile profile);

    /** Newest accounts first. */
    List<UserProfile> findRecent(int limit);

    /** Accounts created at or after {@code since}, newest first, at most {@code limit} (used for registration trends). */
    List<UserProfile> findCreatedSince(java.time.Instant since, int limit);

    long countAll();

    long countByStatus(AccountStatus status);

    long countPhoneVerified();
}
