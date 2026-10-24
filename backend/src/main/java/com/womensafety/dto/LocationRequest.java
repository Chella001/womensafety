package com.womensafety.dto;

import java.time.Instant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** A location the user chose to attach to an alert, as captured by the browser's Geolocation API. */
public record LocationRequest(
        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
        @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
        Double latitude,

        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
        @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
        Double longitude,

        @DecimalMin(value = "0.0", message = "Accuracy cannot be negative")
        @DecimalMax(value = "100000.0", message = "Accuracy is implausibly large")
        Double accuracyMeters,

        @NotNull(message = "The time the location was captured is required")
        Instant capturedAt) {
}
