package com.womensafety.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/admin/devices}: the browser's FCM registration token and an optional friendly label. */
public record RegisterDeviceRequest(
        @NotBlank(message = "The device token is required")
        @Size(max = 4096, message = "The device token is too long")
        String token,

        @Size(max = 40, message = "The label must be at most 40 characters")
        String label) {
}
