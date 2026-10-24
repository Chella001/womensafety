package com.womensafety.notification;

import java.util.Map;

/**
 * Sends one push notification to one administrator device. "Accepted" means Firebase Cloud Messaging took the message;
 * it does not mean a person saw it. Acknowledging an alert is a separate, audited admin action.
 */
public interface PushSender {

    PushResult send(String token, String title, String body, Map<String, String> data);

    /**
     * @param outcome ACCEPTED; INVALID_TOKEN (the device is gone: remove it); TRANSIENT_FAILURE (retry);
     *                PERMANENT_FAILURE; UNAVAILABLE (FCM is not configured in this deployment)
     */
    record PushResult(Outcome outcome, String messageId, String code) {

        public enum Outcome { ACCEPTED, INVALID_TOKEN, TRANSIENT_FAILURE, PERMANENT_FAILURE, UNAVAILABLE }

        public static PushResult accepted(String id) {
            return new PushResult(Outcome.ACCEPTED, id, null);
        }

        public static PushResult of(Outcome outcome, String code) {
            return new PushResult(outcome, null, code);
        }
    }
}
