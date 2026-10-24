package com.womensafety.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body of {@code POST /api/emergency-contacts/{id}/verification/confirm}. */
public record ConfirmVerificationRequest(
        @NotBlank(message = "Enter the code")
        @Pattern(regexp = "^[0-9]{6}$", message = "The code has 6 digits")
        String code) {
}
