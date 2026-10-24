package com.womensafety.dto;

import java.time.Instant;

import com.womensafety.model.AdminDevice;

/** A registered administrator device. The token itself is never returned. */
public record DeviceResponse(String id, String label, Instant createdAt, Instant lastSeenAt) {

    public static DeviceResponse from(AdminDevice d) {
        return new DeviceResponse(d.id(), d.label(), d.createdAt(), d.lastSeenAt());
    }
}
