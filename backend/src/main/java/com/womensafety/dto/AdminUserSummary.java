package com.womensafety.dto;

import java.time.Instant;

import com.womensafety.model.UserProfile;
import com.womensafety.validation.PhoneNumbers;

/** A row in the admin user list. The phone number is masked here; the detail view shows it in full (and is audited). */
public record AdminUserSummary(
        String uid, String fullName, String phoneMasked, boolean phoneVerified, String accountStatus,
        Instant createdAt, Instant lastLoginAt) {

    public static AdminUserSummary from(UserProfile p) {
        return new AdminUserSummary(p.uid(), p.fullName(), PhoneNumbers.mask(p.phoneNumber()), p.phoneVerified(),
                p.accountStatus().name(), p.createdAt(), p.lastLoginAt());
    }
}
