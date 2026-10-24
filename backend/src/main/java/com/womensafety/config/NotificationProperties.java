package com.womensafety.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Notification settings ({@code app.notifications.*}, environment variables in application.yml).
 *
 * <p>{@code mode} is TEST unless the owner deliberately switches to REAL. In TEST mode the mock SMS provider is used:
 * nothing is sent and every simulated attempt is stored with the status SIMULATED. REAL mode refuses to start without
 * provider credentials, so the application can never silently fall back to pretending.</p>
 *
 * @param mode                    TEST (default) or REAL
 * @param workerEnabled           run the background worker that sends queued notifications
 * @param workerIntervalMs        how often the worker looks for due jobs
 * @param leaseSeconds            how long a worker owns a job before another may take it over (crash recovery)
 * @param maxAttempts             attempts per recipient before a delivery is marked FAILED
 * @param retryBaseSeconds        first retry delay; it triples with every further attempt
 * @param publicBaseUrl           public https base URL of this API, used for provider delivery callbacks (empty = none)
 * @param adminPushEnabled        send an FCM push to registered administrator devices for new alerts
 * @param adminPushRecipients     administrator uids that receive pushes; empty means every registered admin device
 * @param attentionMinutes        a NEW alert still unacknowledged after this many minutes is flagged
 * @param verificationCodeMinutes validity of a contact verification code
 * @param verificationMaxAttempts wrong codes allowed before a new code is required
 * @param verificationMaxSendsPerHour verification codes per contact per hour (SMS abuse protection)
 * @param maxAdminDevices         push devices one administrator may register
 * @param twilio                  settings of the Twilio adapter (REAL mode)
 */
@ConfigurationProperties(prefix = "app.notifications")
public record NotificationProperties(
        @DefaultValue("TEST") Mode mode,
        @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("3000") long workerIntervalMs,
        @DefaultValue("60") int leaseSeconds,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("20") int retryBaseSeconds,
        @DefaultValue("") String publicBaseUrl,
        @DefaultValue("true") boolean adminPushEnabled,
        @DefaultValue List<String> adminPushRecipients,
        @DefaultValue("5") int attentionMinutes,
        @DefaultValue("10") int verificationCodeMinutes,
        @DefaultValue("5") int verificationMaxAttempts,
        @DefaultValue("3") int verificationMaxSendsPerHour,
        @DefaultValue("10") int maxAdminDevices,
        @DefaultValue Twilio twilio) {

    public enum Mode { TEST, REAL }

    /**
     * @param accountSid          Twilio Account SID (environment variable TWILIO_ACCOUNT_SID)
     * @param authToken           Twilio auth token (TWILIO_AUTH_TOKEN); also signs delivery callbacks
     * @param from                sender number in E.164 (TWILIO_FROM) — or use a messaging service
     * @param messagingServiceSid Messaging Service SID (TWILIO_MESSAGING_SERVICE_SID); takes precedence over {@code from}
     * @param baseUrl             API base URL; only changed by tests
     */
    public record Twilio(
            @DefaultValue("") String accountSid,
            @DefaultValue("") String authToken,
            @DefaultValue("") String from,
            @DefaultValue("") String messagingServiceSid,
            @DefaultValue("https://api.twilio.com") String baseUrl) {

        public boolean configured() {
            return !accountSid.isBlank() && !authToken.isBlank() && (!from.isBlank() || !messagingServiceSid.isBlank());
        }
    }

    /** The https URL the provider posts delivery receipts to for one delivery; null when none is configured. */
    public String callbackUrl(String alertId, String deliveryId) {
        String base = publicBaseUrl.strip();
        if (!base.startsWith("https://")) {
            return null;
        }
        return base.replaceAll("/+$", "") + "/api/webhooks/sms/twilio?alertId=" + alertId + "&deliveryId=" + deliveryId;
    }

    public boolean realMode() {
        return mode == Mode.REAL;
    }
}
