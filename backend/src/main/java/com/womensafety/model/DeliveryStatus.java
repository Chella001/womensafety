package com.womensafety.model;

/**
 * Where one recipient's notification stands. The values are deliberately fine-grained so the UI can never turn
 * "the provider accepted it" into "the person received it".
 *
 * <ul>
 *   <li>QUEUED / SENDING / RETRY_SCHEDULED — still being processed</li>
 *   <li>SUBMITTED — the provider ACCEPTED the message. This is NOT proof of receipt.</li>
 *   <li>DELIVERED — the provider reported delivery (receipt callback). Only set from provider evidence.</li>
 *   <li>SIMULATED — TEST mode: nothing was sent at all</li>
 *   <li>FAILED — gave up after a permanent error or exhausted retries</li>
 *   <li>SKIPPED — not attempted (contact not verified / no consent / removed, or no admin device)</li>
 *   <li>CANCELLED — suppressed before sending because the user cancelled the alert</li>
 * </ul>
 */
public enum DeliveryStatus {
    QUEUED, SENDING, RETRY_SCHEDULED, SUBMITTED, DELIVERED, SIMULATED, FAILED, SKIPPED, CANCELLED;

    /** Still needs work from the worker. */
    public boolean pending() {
        return this == QUEUED || this == SENDING || this == RETRY_SCHEDULED;
    }
}
