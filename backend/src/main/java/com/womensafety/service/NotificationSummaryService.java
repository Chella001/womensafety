package com.womensafety.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.womensafety.model.AlertEvent;
import com.womensafety.model.Delivery;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.NotificationSummary;
import com.womensafety.repository.DeliveryRepository;
import com.womensafety.repository.EmergencyAlertRepository;
import org.springframework.stereotype.Service;

/**
 * Recomputes the two notification summaries of an alert from its delivery records. The summaries are never edited by
 * hand, so what a user or administrator sees can only be as good as the recorded evidence.
 */
@Service
public class NotificationSummaryService {

    private final DeliveryRepository deliveries;
    private final EmergencyAlertRepository alerts;
    private final AlertEventLog events;
    private final SosStreamService stream;
    private final Clock clock;

    public NotificationSummaryService(DeliveryRepository deliveries, EmergencyAlertRepository alerts,
                                      AlertEventLog events, SosStreamService stream, Clock clock) {
        this.deliveries = deliveries;
        this.alerts = alerts;
        this.events = events;
        this.stream = stream;
        this.clock = clock;
    }

    /** @return the alert with fresh summaries (only written when something actually changed) */
    public EmergencyAlert refresh(String alertId, String actor) {
        List<Delivery> all = deliveries.findAll(alertId);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        NotificationSummary contacts = NotificationSummary.of(
                all.stream().filter(d -> d.recipientType() == Delivery.RecipientType.CONTACT).toList(), now);
        NotificationSummary admins = NotificationSummary.of(
                all.stream().filter(d -> d.recipientType() == Delivery.RecipientType.ADMIN_DEVICE).toList(), now);

        boolean[] changed = {false};
        EmergencyAlert updated = alerts.modify(alertId, current -> {
            if (sameFacts(current.notificationSummary(), contacts) && sameFacts(current.adminNotificationSummary(), admins)) {
                return current;
            }
            changed[0] = true;
            return current.withSummaries(contacts, admins, now);
        });
        if (changed[0]) {
            events.record(alertId, AlertEvent.NOTIFICATIONS_UPDATED, actor,
                    Map.of("contacts", contacts.state().name(), "admins", admins.state().name()));
            stream.publish("notifications", alertId, updated.status().name());
        }
        return updated;
    }

    private static boolean sameFacts(NotificationSummary a, NotificationSummary b) {
        return a != null && Objects.equals(a.state(), b.state()) && a.total() == b.total() && a.queued() == b.queued()
                && a.submitted() == b.submitted() && a.delivered() == b.delivered() && a.simulated() == b.simulated()
                && a.failed() == b.failed() && a.skipped() == b.skipped();
    }
}
