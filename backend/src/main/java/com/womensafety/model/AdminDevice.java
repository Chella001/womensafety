package com.womensafety.model;

import java.time.Instant;

/**
 * A browser/device an administrator registered for push notifications ({@code adminDevices/{id}}). The id is a hash of
 * the FCM token, so registering the same token twice is a no-op. Only the backend reads this collection; the token is
 * never returned to any client.
 */
public record AdminDevice(String id, String uid, String token, String label, Instant createdAt, Instant lastSeenAt) {
}
