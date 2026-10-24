package com.womensafety.dto;

import com.womensafety.model.AlertStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/admin/sos/{id}/status}. {@code status} must be ACKNOWLEDGED or RESOLVED; RESOLVED needs a
 * note. The acting administrator is always taken from the verified token.
 */
public record ChangeAlertStatusRequest(
        @NotNull(message = "The new status is required")
        AlertStatus status,

        @Size(max = 500, message = "The note must be at most 500 characters")
        String note) {
}
