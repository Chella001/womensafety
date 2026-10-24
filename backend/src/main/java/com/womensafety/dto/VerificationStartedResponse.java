package com.womensafety.dto;

import java.time.Instant;

/**
 * Result of asking for a verification code.
 *
 * @param testMode     true when the SMS is only simulated; then {@code testModeCode} carries the code so the flow can be
 *                     tried without a real phone. It is null in REAL mode, always.
 */
public record VerificationStartedResponse(Instant expiresAt, boolean testMode, String testModeCode, String message) {
}
