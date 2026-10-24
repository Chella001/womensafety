package com.womensafety.dto;

import com.womensafety.validation.PersonName;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code PATCH /api/emergency-contacts/{id}}: every field is optional; only present fields change. */
public record UpdateEmergencyContactRequest(
        @PersonName(min = 2, max = 60)
        String name,

        @Size(max = 24, message = "Phone number is too long")
        @Pattern(regexp = "^\\+?[0-9 ()\\-]+$", message = "Phone number may contain only digits, spaces, ( ), - and a leading +")
        String phoneNumber,

        @Size(max = 40, message = "Relationship must be at most 40 characters")
        String relationship,

        Boolean notifyConsent) {
}
