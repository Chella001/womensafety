package com.womensafety.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/admin/alerts/{id}/resolve}. A note is required so the audit trail says why. */
public record ResolveAlertRequest(
        @NotBlank(message = "A resolution note is required")
        @Size(min = 3, max = 500, message = "The note must be 3 to 500 characters")
        String note) {
}
