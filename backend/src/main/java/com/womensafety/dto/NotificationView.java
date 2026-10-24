package com.womensafety.dto;

import java.time.Instant;

import com.womensafety.model.Delivery;
import com.womensafety.model.NotificationSummary;

/**
 * Notification progress as shown to the alert's owner and to administrators. {@code message} is composed on the server
 * so every client words it identically and never claims more than the delivery records prove.
 */
public record NotificationView(
        String state, int total, int queued, int submitted, int delivered, int simulated, int failed, int skipped,
        Instant updatedAt, String message) {

    public static NotificationView from(NotificationSummary s, Delivery.Mode mode) {
        return new NotificationView(s.state().name(), s.total(), s.queued(), s.submitted(), s.delivered(), s.simulated(),
                s.failed(), s.skipped(), s.updatedAt(), describe(s, mode));
    }

    static String describe(NotificationSummary s, Delivery.Mode mode) {
        String skippedNote = s.skipped() > 0
                ? " " + s.skipped() + " contact(s) were not messaged because they are not verified or have not agreed."
                : "";
        return switch (s.state()) {
            case QUEUED, PROCESSING -> "Notifications are being processed. This can take a moment.";
            case NO_RECIPIENTS -> "No verified emergency contact was available, so nobody was messaged." + skippedNote;
            case SIMULATED -> "TEST MODE: " + s.simulated() + " message(s) were simulated. Nothing was actually sent." + skippedNote;
            case SUBMITTED -> s.submitted() + " message(s) were accepted by the SMS provider. Receipt has not been confirmed." + skippedNote;
            case DELIVERED -> s.delivered() + " message(s) were reported as delivered by the SMS provider." + skippedNote;
            case PARTIAL -> "Partly successful: " + s.failed() + " of " + (s.failed() + s.submitted() + s.delivered() + s.simulated())
                    + " message(s) failed; the others were " + (mode == Delivery.Mode.TEST ? "simulated" : "accepted or delivered") + "." + skippedNote;
            case FAILED -> "No message could be sent to your contacts. Contact them or your local emergency number directly." + skippedNote;
            case UNAVAILABLE -> "Notification status is not available for this alert.";
        };
    }
}
