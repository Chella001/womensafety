package com.womensafety.notification;

/**
 * Sends one text message. Implementations must not log the message body or the full recipient number, and must report
 * honestly what happened: {@link SmsSendResult#accepted} means only that the provider took the message.
 */
public interface SmsNotificationProvider {

    /** Short name for logs and the admin console, e.g. "mock" or "twilio". */
    String name();

    /** True when nothing is really sent (TEST mode). */
    boolean simulated();

    /** Never throws for provider problems; reports them in the result. */
    SmsSendResult send(SmsMessage message);

    /**
     * @param to          recipient in E.164
     * @param body        message text
     * @param callbackUrl where the provider should post delivery receipts (null = none)
     */
    record SmsMessage(String to, String body, String callbackUrl) {
    }

    /**
     * @param outcome            ACCEPTED, PERMANENT_FAILURE (do not retry) or TRANSIENT_FAILURE (retry later)
     * @param providerReference  the provider's message id when it accepted the message
     * @param code               short machine-readable reason on failure
     */
    record SmsSendResult(Outcome outcome, String providerReference, String code) {

        public enum Outcome { ACCEPTED, PERMANENT_FAILURE, TRANSIENT_FAILURE }

        public static SmsSendResult accepted(String reference) {
            return new SmsSendResult(Outcome.ACCEPTED, reference, null);
        }

        public static SmsSendResult permanent(String code) {
            return new SmsSendResult(Outcome.PERMANENT_FAILURE, null, code);
        }

        public static SmsSendResult transientFailure(String code) {
            return new SmsSendResult(Outcome.TRANSIENT_FAILURE, null, code);
        }
    }
}
