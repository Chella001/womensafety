package com.womensafety.dto;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uniform error body returned by every failing API call.
 *
 * @param code    stable machine-readable code, e.g. {@code VALIDATION_FAILED}
 * @param message human-readable summary that is safe to show to end users
 * @param details optional per-field messages for validation errors
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String code, String message, Map<String, String> details) {
}
