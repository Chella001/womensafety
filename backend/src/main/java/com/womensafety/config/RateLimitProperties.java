package com.womensafety.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-client request limits (in-memory, per application instance; use a shared store when running several).
 *
 * @param authPerMinute    requests per minute per client IP for {@code /api/auth/**}
 * @param generalPerMinute requests per minute per client IP for every other {@code /api/**} path
 * @param adminWritePerMinute  state-changing {@code /api/admin/**} requests per minute per client IP
 * @param sensitivePerMinute   requests per minute per client IP for actions that send SMS (contact verification)
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") int authPerMinute,
        @DefaultValue("240") int generalPerMinute,
        @DefaultValue("30") int adminWritePerMinute,
        @DefaultValue("10") int sensitivePerMinute) {
}
