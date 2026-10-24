package com.womensafety.dto;

import com.womensafety.model.AccountStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code PATCH /api/admin/users/{uid}/status}. */
public record UpdateAccountStatusRequest(
        @NotNull(message = "Status is required") AccountStatus status,
        @Size(max = 200, message = "Reason must be at most 200 characters") String reason) {
}
