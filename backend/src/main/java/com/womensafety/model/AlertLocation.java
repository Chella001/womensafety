package com.womensafety.model;

import java.time.Instant;

/** Location the user explicitly chose to attach to an alert. */
public record AlertLocation(double latitude, double longitude, Double accuracyMeters, Instant capturedAt) {
}
