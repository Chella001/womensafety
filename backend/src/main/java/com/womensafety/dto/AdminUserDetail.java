package com.womensafety.dto;

import java.time.Instant;

import com.womensafety.model.UserProfile;

public record AdminUserDetail(
        String uid, String fullName, String phoneNumber, boolean phoneVerified, String role, String accountStatus,
        Instant createdAt, Instant updatedAt, Instant lastLoginAt, String consentVersion, Instant consentTimestamp) {

    public static AdminUserDetail from(UserProfile p, boolean administrator) {
        return new AdminUserDetail(p.uid(), p.fullName(), p.phoneNumber(), p.phoneVerified(),
                administrator ? "ADMIN" : "USER", p.accountStatus().name(), p.createdAt(), p.updatedAt(),
                p.lastLoginAt(), p.consentVersion(), p.consentTimestamp());
    }
}
