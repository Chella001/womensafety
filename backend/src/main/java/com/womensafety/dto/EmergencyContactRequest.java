package com.womensafety.dto;

import com.womensafety.validation.PersonName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/emergency-contacts}. */
public record EmergencyContactRequest(
        @NotBlank(message = "Name is required")
        @PersonName(min = 2, max = 60)
        String name,

        @NotBlank(message = "Phone number is required")
        @Size(max = 24, message = "Phone number is too long")
        @Pattern(regexp = "^\\+?[0-9 ()\\-]+$", message = "Phone number may contain only digits, spaces, ( ), - and a leading +")
        String phoneNumber,

        @Size(max = 40, message = "Relationship must be at most 40 characters")
        String relationship,

        /** The user confirms this person agreed to receive SafeHer emergency messages. Needed (with verification) before any SOS message. */
        Boolean notifyConsent) {
}
