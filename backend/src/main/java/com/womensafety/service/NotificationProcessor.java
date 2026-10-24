package com.womensafety.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.womensafety.config.NotificationProperties;
import com.womensafety.model.AdminDevice;
import com.womensafety.model.AlertEvent;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.Delivery;
import com.womensafety.model.DeliveryStatus;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.EmergencyContact;
import com.womensafety.model.NotificationJob;
import com.womensafety.model.NotificationSummary;
import com.womensafety.notification.PushSender;
import com.womensafety.notification.SmsNotificationProvider;
import com.womensafety.repository.AdminDeviceRepository;
import com.womensafety.repository.DeliveryRepository;
import com.womensafety.repository.EmergencyAlertRepository;
import com.womensafety.repository.EmergencyContactRepository;
import com.womensafety.repository.NotificationJobRepository;
import com.womensafety.repository.UserRepository;
import com.womensafety.validation.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Durable notification workflow (transactional outbox + leased worker).
 *
 * <ol>
 *   <li>The alert and its {@link NotificationJob} are stored in one batch before anything is sent, so a notification
 *       problem can never erase an alert and a restart resumes unfinished jobs.</li>
 *   <li>{@link #runOnce()} leases due jobs, PLANS the recipients (verified + consenting contacts, registered admin
 *       devices) as deterministic delivery records, then sends each one with bounded retries and backoff.</li>
 *   <li>Every attempt is stored on the delivery; the alert summaries are recomputed from those records.</li>
 * </ol>
 *
 * <p><strong>Guarantee:</strong> at-least-once with de-duplication by recipient. A delivery that is already SUBMITTED,
 * DELIVERED, SIMULATED or FAILED is never sent again, and planning twice cannot create a second delivery. If the process
 * dies between the provider accepting a message and us recording that, the message is retried and the recipient may get
 * it twice. Exactly-once delivery of an external SMS cannot be guaranteed by any design that talks to a third party.</p>
 */
@Service
public class NotificationProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);
    private static final int JOBS_PER_RUN = 10;

    private final NotificationProperties properties;
    private final SmsNotificationProvider sms;
    private final PushSender push;
    private final EmergencyAlertRepository alerts;
    private final DeliveryRepository deliveries;
    private final NotificationJobRepository jobs;
    private final EmergencyContactRepository contacts;
    private final AdminDeviceRepository devices;
    private final UserRepository users;
    private final NotificationSummaryService summaries;
    private final AlertEventLog events;
    private final AuditService audit;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    public NotificationProcessor(NotificationProperties properties, SmsNotificationProvider sms, PushSender push,
                                 EmergencyAlertRepository alerts, DeliveryRepository deliveries,
                                 NotificationJobRepository jobs, EmergencyContactRepository contacts,
                                 AdminDeviceRepository devices, UserRepository users,
                                 NotificationSummaryService summaries, AlertEventLog events, AuditService audit,
                                 Clock clock) {
        this.properties = properties;
        this.sms = sms;
        this.push = push;
        this.alerts = alerts;
        this.deliveries = deliveries;
        this.jobs = jobs;
        this.contacts = contacts;
        this.devices = devices;
        this.users = users;
        this.summaries = summaries;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /** Processes the jobs that are due. Safe to call from several threads or instances; overlapping calls return 0. */
    public int runOnce() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            Instant now = now();
            List<NotificationJob> due = jobs.claimDue(now, now.plusSeconds(properties.leaseSeconds()), JOBS_PER_RUN);
            for (NotificationJob job : due) {
                process(job);
            }
            return due.size();
        } finally {
            running.set(false);
        }
    }

    private void process(NotificationJob job) {
        String alertId = job.alertId();
        try {
            EmergencyAlert alert = alerts.findById(alertId).orElse(null);
            if (alert == null) {
                jobs.delete(alertId);
                return;
            }
            if (!job.planned()) {
                plan(alert);
                job = job.markPlanned();
                jobs.save(job);
            }
            List<Delivery> current = new ArrayList<>(deliveries.findAll(alertId));
            Instant next = null;
            for (int i = 0; i < current.size(); i++) {
                Delivery updated = handle(alert, current.get(i));
                current.set(i, updated);
                if (updated.status().pending() && updated.nextAttemptAt() != null
                        && (next == null || updated.nextAttemptAt().isBefore(next))) {
                    next = updated.nextAttemptAt();
                }
            }
            EmergencyAlert refreshed = summaries.refresh(alertId, "system");
            if (next == null) {
                jobs.delete(alertId);
                audit.recordSystemEvent("NOTIFICATIONS_PROCESSED", alert.uid(), Map.of(
                        "alertId", alertId,
                        "contacts", refreshed.contactSummary().state().name(),
                        "admins", refreshed.adminSummary().state().name(),
                        "mode", alert.notificationMode().name()), true);
            } else {
                jobs.save(job.rescheduled(next));
            }
        } catch (RuntimeException e) {
            // Never lose the job: it stays leased until the lease expires, then another run picks it up again.
            log.error("Notification job {} failed and will be retried: {}", alertId, e.toString());
        }
    }

    /* ---------- planning ---------- */

    private void plan(EmergencyAlert alert) {
        Instant now = now();
        Delivery.Mode mode = alert.notificationMode();
        int eligible = 0;
        int skipped = 0;
        for (EmergencyContact contact : contacts.findAll(alert.uid())) {
            String id = "c-" + contact.id();
            String label = PhoneNumbers.mask(contact.phoneNumber());
            if (contact.eligibleForNotifications()) {
                deliveries.createIfAbsent(alert.id(), Delivery.queued(id, Delivery.RecipientType.CONTACT, contact.id(),
                        label, Delivery.Channel.SMS, mode, now));
                eligible++;
            } else {
                String code = !contact.verified() ? "NOT_VERIFIED" : "NO_CONSENT";
                deliveries.createIfAbsent(alert.id(), Delivery.skipped(id, Delivery.RecipientType.CONTACT, contact.id(),
                        label, Delivery.Channel.SMS, mode, code, now));
                skipped++;
            }
        }
        int pushTargets = 0;
        if (!properties.adminPushEnabled()) {
            deliveries.createIfAbsent(alert.id(), Delivery.skipped("p-none", Delivery.RecipientType.ADMIN_DEVICE, "none",
                    "Admin push is turned off", Delivery.Channel.PUSH, Delivery.Mode.REAL, "ADMIN_PUSH_DISABLED", now));
        } else {
            List<AdminDevice> targets = devices.findAll().stream().filter(this::isRecipient).toList();
            for (AdminDevice device : targets) {
                deliveries.createIfAbsent(alert.id(), Delivery.queued("p-" + device.id().substring(0, Math.min(16, device.id().length())),
                        Delivery.RecipientType.ADMIN_DEVICE, device.id(), device.label() == null ? "Admin device" : device.label(),
                        Delivery.Channel.PUSH, Delivery.Mode.REAL, now));
                pushTargets++;
            }
            if (targets.isEmpty()) {
                deliveries.createIfAbsent(alert.id(), Delivery.skipped("p-none", Delivery.RecipientType.ADMIN_DEVICE, "none",
                        "No administrator device registered", Delivery.Channel.PUSH, Delivery.Mode.REAL, "NO_ADMIN_DEVICES", now));
            }
        }
        events.record(alert.id(), AlertEvent.NOTIFICATIONS_PLANNED, "system", Map.of(
                "contactsToNotify", String.valueOf(eligible), "contactsSkipped", String.valueOf(skipped),
                "adminDevices", String.valueOf(pushTargets), "mode", alert.notificationMode().name()));
        summaries.refresh(alert.id(), "system");
    }

    private boolean isRecipient(AdminDevice device) {
        List<String> only = properties.adminPushRecipients().stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        return only.isEmpty() || only.contains(device.uid());
    }

    /* ---------- sending ---------- */

    private Delivery handle(EmergencyAlert alert, Delivery delivery) {
        if (!delivery.status().pending()) {
            return delivery;
        }
        Instant now = now();
        // Unsent SMS are dropped if the user cancelled. Messages that already went out cannot be recalled.
        if (alert.status() == AlertStatus.CANCELLED && delivery.channel() == Delivery.Channel.SMS) {
            return deliveries.save(alert.id(), delivery.withStatus(DeliveryStatus.CANCELLED, "USER_CANCELLED", now));
        }
        if (delivery.status() == DeliveryStatus.RETRY_SCHEDULED && delivery.nextAttemptAt() != null
                && delivery.nextAttemptAt().isAfter(now)) {
            return delivery;
        }
        if (delivery.attemptCount() >= properties.maxAttempts()) {
            // Only reachable for a SENDING record left behind by a crashed worker that used its last attempt.
            return deliveries.save(alert.id(), delivery.withStatus(DeliveryStatus.FAILED, "RETRIES_EXHAUSTED", now));
        }
        return delivery.channel() == Delivery.Channel.SMS ? sendSms(alert, delivery) : sendPush(alert, delivery);
    }

    private Delivery sendSms(EmergencyAlert alert, Delivery delivery) {
        Instant now = now();
        EmergencyContact contact = contacts.findById(alert.uid(), delivery.recipientReference()).orElse(null);
        if (contact == null) {
            return deliveries.save(alert.id(), delivery.withStatus(DeliveryStatus.SKIPPED, "CONTACT_REMOVED", now));
        }
        if (!contact.eligibleForNotifications()) {
            return deliveries.save(alert.id(), delivery.withStatus(DeliveryStatus.SKIPPED, "NO_LONGER_ELIGIBLE", now));
        }
        // Stored BEFORE the call: a crash now leaves a visible SENDING record rather than an unrecorded message.
        Delivery attempting = deliveries.save(alert.id(), delivery.beginAttempt(now));
        String name = users.findByUid(alert.uid()).map(u -> u.fullName()).orElse(null);
        SmsNotificationProvider.SmsMessage message = new SmsNotificationProvider.SmsMessage(contact.phoneNumber(),
                SosMessages.emergency(name, alert.createdAt(), alert.location()), callbackUrl(alert.id(), delivery.id()));
        SmsNotificationProvider.SmsSendResult result;
        try {
            result = sms.send(message);
        } catch (RuntimeException e) {
            result = SmsNotificationProvider.SmsSendResult.transientFailure("PROVIDER_EXCEPTION");
        }
        Instant after = now();
        return switch (result.outcome()) {
            case ACCEPTED -> deliveries.save(alert.id(), attempting.afterAttempt(
                    sms.simulated() ? DeliveryStatus.SIMULATED : DeliveryStatus.SUBMITTED, result.providerReference(), null, null, after));
            case PERMANENT_FAILURE -> deliveries.save(alert.id(),
                    attempting.afterAttempt(DeliveryStatus.FAILED, null, result.code(), null, after));
            case TRANSIENT_FAILURE -> deliveries.save(alert.id(), retryOrFail(attempting, result.code(), after));
        };
    }

    private Delivery sendPush(EmergencyAlert alert, Delivery delivery) {
        Instant now = now();
        AdminDevice device = devices.findById(delivery.recipientReference()).orElse(null);
        if (device == null) {
            return deliveries.save(alert.id(), delivery.withStatus(DeliveryStatus.SKIPPED, "DEVICE_REMOVED", now));
        }
        Delivery attempting = deliveries.save(alert.id(), delivery.beginAttempt(now));
        PushSender.PushResult result;
        try {
            // Deliberately generic: no location, no phone number, no name. The alert id lets the console open the record.
            result = push.send(device.token(), "SafeHer: new SOS alert", "Open the admin console to review it.",
                    Map.of("type", "SOS_NEW", "alertId", alert.id()));
        } catch (RuntimeException e) {
            result = PushSender.PushResult.of(PushSender.PushResult.Outcome.TRANSIENT_FAILURE, "PUSH_EXCEPTION");
        }
        Instant after = now();
        return switch (result.outcome()) {
            // "Accepted by FCM" is not "seen by a person": acknowledging the alert is a separate admin action.
            case ACCEPTED -> deliveries.save(alert.id(),
                    attempting.afterAttempt(DeliveryStatus.SUBMITTED, result.messageId(), null, null, after));
            case INVALID_TOKEN -> {
                devices.delete(device.id());
                yield deliveries.save(alert.id(), attempting.afterAttempt(DeliveryStatus.FAILED, null, "TOKEN_INVALID", null, after));
            }
            case UNAVAILABLE, PERMANENT_FAILURE -> deliveries.save(alert.id(),
                    attempting.afterAttempt(DeliveryStatus.FAILED, null, result.code(), null, after));
            case TRANSIENT_FAILURE -> deliveries.save(alert.id(), retryOrFail(attempting, result.code(), after));
        };
    }

    private Delivery retryOrFail(Delivery attempted, String code, Instant now) {
        if (attempted.attemptCount() >= properties.maxAttempts()) {
            return attempted.afterAttempt(DeliveryStatus.FAILED, null, "RETRIES_EXHAUSTED:" + code, null, now);
        }
        long seconds = (long) properties.retryBaseSeconds() * (long) Math.pow(3, attempted.attemptCount() - 1);
        return attempted.afterAttempt(DeliveryStatus.RETRY_SCHEDULED, null, code, now.plus(Duration.ofSeconds(seconds)), now);
    }

    /** Where the provider reports delivery receipts; only for a real provider and only over https. */
    private String callbackUrl(String alertId, String deliveryId) {
        return sms.simulated() ? null : properties.callbackUrl(alertId, deliveryId);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Visible for tests and monitoring: how the summary of these deliveries reads. */
    static NotificationSummary summarise(List<Delivery> deliveries, Instant now) {
        return NotificationSummary.of(deliveries, now);
    }
}
