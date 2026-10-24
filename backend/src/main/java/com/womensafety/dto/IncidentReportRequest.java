package com.womensafety.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/incident-reports}. */
public record IncidentReportRequest(
        @NotBlank(message = "Category is required")
        @Pattern(regexp = "HARASSMENT|STALKING|ASSAULT|UNSAFE_LOCATION|OTHER", message = "Unknown category")
        String category,

        @NotBlank(message = "Please describe what happened")
        @Size(min = 10, max = 2000, message = "Description must be 10 to 2000 characters")
        String description,

        Instant occurredAt) {
}
