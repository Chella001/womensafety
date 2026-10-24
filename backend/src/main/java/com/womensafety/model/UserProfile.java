package com.womensafety.model;

import java.time.Instant;

/**
 * A SafeHer user's profile, stored in Firestore at {@code users/{uid}}. No passwords, OTPs or tokens
 * are ever stored: Firebase Authentication owns credentials.
 *
 * @param role the STORED role; the effective role of an administrator is decided by server configuration
 */
public record UserProfile(
        String uid,
        String fullName,
        String phoneNumber,
        boolean phoneVerified,
        Role role,
        AccountStatus accountStatus,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt,
        String consentVersion,
        Instant consentTimestamp) {

    public UserProfile withFullName(String name, Instant now) {
        return new UserProfile(uid, name, phoneNumber, phoneVerified, role, accountStatus, createdAt, now,
                lastLoginAt, consentVersion, consentTimestamp);
    }

    public UserProfile withLastLoginAt(Instant now) {
        return new UserProfile(uid, fullName, phoneNumber, phoneVerified, role, accountStatus, createdAt, updatedAt,
                now, consentVersion, consentTimestamp);
    }

    public UserProfile withAccountStatus(AccountStatus status, Instant now) {
        return new UserProfile(uid, fullName, phoneNumber, phoneVerified, role, status, createdAt, now,
                lastLoginAt, consentVersion, consentTimestamp);
    }

    public boolean isSuspended() {
        return accountStatus == AccountStatus.SUSPENDED;
    }
}
