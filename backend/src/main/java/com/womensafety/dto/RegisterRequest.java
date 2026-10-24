package com.womensafety.dto;

import com.womensafety.validation.PersonName;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/auth/register}. The phone number and uid are NOT accepted from the client:
 * they come from the verified Firebase ID token. Any extra field a client sends (such as "role") is ignored.
 */
public record RegisterRequest(
        @NotBlank(message = "Full name is required")
        @PersonName(min = 2, max = 60)
        String fullName,

        @AssertTrue(message = "You must accept the Terms and Privacy Policy")
        boolean acceptedTerms,

        @NotBlank(message = "Consent version is required")
        String consentVersion) {
}
