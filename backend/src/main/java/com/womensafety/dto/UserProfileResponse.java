package com.womensafety.dto;

import java.time.Instant;

import com.womensafety.model.UserProfile;

/**
 * The signed-in user's own profile.
 *
 * @param role effective role: ADMIN only when the uid is in the server-side administrator allow-list. The role
 *             string stored in Firestore is never trusted for this.
 */
public record UserProfileResponse(
        String uid,
        String fullName,
        String phoneNumber,
        boolean phoneVerified,
        String role,
        String accountStatus,
        Instant createdAt,
        Instant lastLoginAt,
        String consentVersion) {

    public static UserProfileResponse from(UserProfile profile, boolean administrator) {
        return new UserProfileResponse(
                profile.uid(), profile.fullName(), profile.phoneNumber(), profile.phoneVerified(),
                administrator ? "ADMIN" : "USER", profile.accountStatus().name(),
                profile.createdAt(), profile.lastLoginAt(), profile.consentVersion());
    }
}
