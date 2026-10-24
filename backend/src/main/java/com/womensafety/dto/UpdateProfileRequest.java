package com.womensafety.dto;

import com.womensafety.validation.PersonName;
import jakarta.validation.constraints.NotBlank;

/** Body of {@code PATCH /api/users/me}. Only the display name is editable; role and status never are. */
public record UpdateProfileRequest(
        @NotBlank(message = "Full name is required")
        @PersonName(min = 2, max = 60)
        String fullName) {
}
