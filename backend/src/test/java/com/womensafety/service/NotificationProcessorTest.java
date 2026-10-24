package com.womensafety.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.womensafety.config.NotificationProperties;
import com.womensafety.model.AccountStatus;
import com.womensafety.model.AdminDevice;
import com.womensafety.model.AlertLocation;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.Delivery;
import com.womensafety.model.DeliveryStatus;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.EmergencyContact;
import com.womensafety.model.NotificationJob;
import com.womensafety.model.NotificationSummary;
import com.womensafety.model.Role;
import com.womensafety.model.UserProfile;
import com.womensafety.notification.Fakes;
import com.womensafety.notification.PushSender.PushResult;
import com.womensafety.notification.SmsNotificationProvider.SmsSendResult;
import com.womensafety.repository.AuditLogRepository;
import com.womensafety.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The durable notification workflow against in-memory repositories and scripted providers. Nothing here sends a real
 * SMS or push, and nothing touches Firebase.
 */
class NotificationProcessorTest {

    static final Instant T0 = Instant.parse("2026-10-22T10:00:00Z");
    static final String UID = "uid-alice";

    Fakes.TestClock clock;
    Fakes.Jobs jobs;
    Fakes.Alerts alerts;
    Fakes.Deliveries deliveries;
    Fakes.Events events;
    Fakes.Devices devices;
    Fakes.Contacts contacts;
    AuditLogRepository auditLog;
    UserRepository users;
    AlertEventLog eventLog;
    NotificationSummaryService summaries;

    @BeforeEach
    void setUp() {
        clock = new Fakes.TestClock(T0);
        jobs = new Fakes.Jobs();
        alerts = new Fakes.Alerts(jobs);
        deliveries = new Fakes.Deliveries();
        events = new Fakes.Events();
        devices = new Fakes.Devices();
        contacts = new Fakes.Contacts();
        auditLog = mock(AuditLogRepository.class);
        users = mock(UserRepository.class);
        when(users.findByUid(UID)).thenReturn(Optional.of(new UserProfile(UID, "Alice Rao", "+919876543210", true, Role.USER,
                AccountStatus.ACTIVE, T0, T0, T0, "v", T0)));
        eventLog = new AlertEventLog(events, clock);
        summaries = new NotificationSummaryService(deliveries, alerts, eventLog, new SosStreamService(clock), clock);
    }

    NotificationProcessor processor(Fakes.ScriptedSms sms, Fakes.ScriptedPush push, int maxAttempts) {
        NotificationProperties props = new NotificationProperties(
                sms.simulated() ? NotificationProperties.Mode.TEST : NotificationProperties.Mode.REAL,
                false, 3000, 60, maxAttempts, 20, "https://api.example.com", true, List.of(), 5, 10, 5, 3, 10,
                new NotificationProperties.Twilio("", "", "", "", "https://api.twilio.com"));
        return new NotificationProcessor(props, sms, push, alerts, deliveries, jobs, contacts, devices, users, summaries,
                eventLog, new AuditService(auditLog), clock);
    }

    static Fakes.ScriptedPush okPush() {
        return new Fakes.ScriptedPush(PushResult.accepted("fcm-1"));
    }

    EmergencyAlert alert(String id, AlertLocation location, Delivery.Mode mode) {
        EmergencyAlert a = EmergencyAlert.builder().id(id).uid(UID).status(AlertStatus.ACTIVE).createdAt(T0).updatedAt(T0)
                .location(location).notificationMode(mode).notificationSummary(NotificationSummary.queued(T0))
                .adminNotificationSummary(NotificationSummary.queued(T0)).retainUntil(T0.plus(Duration.ofDays(90))).build();
        assertThat(alerts.createIfAbsent(a, NotificationJob.pending(id, T0))).isTrue();
        return a;
    }

    void contact(String id, String phone, boolean verified, boolean consent) {
        EmergencyContact c = new EmergencyContact(id, "Contact " + id, phone, "Friend", T0, T0);
        if (consent) {
            c = c.withConsent(true, T0);
        }
        if (verified) {
            c = c.markVerified(T0);
        }
        contacts.save(UID, c);
    }

    void device(String id, String token) {
        devices.save(new AdminDevice(id, "uid-admin", token, "Laptop", T0, T0));
    }

    EmergencyAlert stored(String id) {
        return alerts.findById(id).orElseThrow();
    }

    // ---------------------------------------------------------------------------------------------- TEST mode

    @Test
    void testMode_simulatesEligibleContacts_andNeverCallsItDelivered() {
        alert("a1", null, Delivery.Mode.TEST);
        contact("c1", "+919876500001", true, true);
        contact("c2", "+919876500002", true, true);
        contact("c3", "+919876500003", false, true); // not verified
        var sms = new Fakes.ScriptedSms(true, SmsSendResult.accepted("TEST-ref"));

        processor(sms, okPush(), 3).runOnce();

        assertThat(sms.calls()).isEqualTo(2);
        assertThat(deliveries.get("a1", "c-c1").status()).isEqualTo(DeliveryStatus.SIMULATED);
        assertThat(deliveries.get("a1", "c-c2").status()).isEqualTo(DeliveryStatus.SIMULATED);
        assertThat(deliveries.get("a1", "c-c3").status()).isEqualTo(DeliveryStatus.SKIPPED);
        assertThat(deliveries.get("a1", "c-c3").failureCode()).isEqualTo("NOT_VERIFIED");
        assertThat(stored("a1").contactSummary().state()).isEqualTo(NotificationSummary.State.SIMULATED);
        assertThat(stored("a1").contactSummary().delivered()).isZero();
        assertThat(deliveries.findAll("a1")).noneMatch(d -> d.status() == DeliveryStatus.DELIVERED);
    }

    @Test
    void contactWithoutConsent_isSkipped_evenWhenVerified() {
        alert("a1", null, Delivery.Mode.TEST);
        contact("c1", "+919876500001", true, false);
        var sms = new Fakes.ScriptedSms(true, SmsSendResult.accepted("TEST-ref"));

        processor(sms, okPush(), 3).runOnce();

        assertThat(sms.calls()).isZero();
        assertThat(deliveries.get("a1", "c-c1").failureCode()).isEqualTo("NO_CONSENT");
        assertThat(stored("a1").contactSummary().state()).isEqualTo(NotificationSummary.State.NO_RECIPIENTS);
    }

    // ---------------------------------------------------------------------------------------------- REAL mode

    @Test
    void providerAcceptance_isSubmitted_notDelivered_untilAReceiptArrives() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        var sms = new Fakes.ScriptedSms(false, SmsSendResult.accepted("SM123"));

        processor(sms, okPush(), 3).runOnce();

        Delivery d = deliveries.get("a1", "c-c1");
        assertThat(d.status()).isEqualTo(DeliveryStatus.SUBMITTED);
        assertThat(d.providerReference()).isEqualTo("SM123");
        assertThat(d.deliveredAt()).isNull();
        assertThat(stored("a1").contactSummary().state()).isEqualTo(NotificationSummary.State.SUBMITTED);

        // only provider evidence makes it DELIVERED
        var receipts = new DeliveryReceiptService(deliveries, summaries, eventLog, clock);
        assertThat(receipts.applyTwilioReceipt("a1", "c-c1", "SM123", "delivered", null)).isTrue();
        assertThat(deliveries.get("a1", "c-c1").status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(deliveries.get("a1", "c-c1").deliveredAt()).isNotNull();
        assertThat(stored("a1").contactSummary().state()).isEqualTo(NotificationSummary.State.DELIVERED);
    }

    @Test
    void receipts_forAnotherMessage_orUnknownDelivery_areIgnored_andNeverGoBackwards() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        processor(new Fakes.ScriptedSms(false, SmsSendResult.accepted("SM123")), okPush(), 3).runOnce();
        var receipts = new DeliveryReceiptService(deliveries, summaries, eventLog, clock);

        assertThat(receipts.applyTwilioReceipt("a1", "c-c1", "SM-OTHER", "delivered", null)).isFalse();
        assertThat(receipts.applyTwilioReceipt("a1", "c-nope", "SM123", "delivered", null)).isFalse();
        assertThat(receipts.applyTwilioReceipt("a1", "c-c1", "SM123", "sent", null)).isFalse();
        assertThat(deliveries.get("a1", "c-c1").status()).isEqualTo(DeliveryStatus.SUBMITTED);

        assertThat(receipts.applyTwilioReceipt("a1", "c-c1", "SM123", "delivered", null)).isTrue();
        assertThat(receipts.applyTwilioReceipt("a1", "c-c1", "SM123", "failed", "30008")).isFalse();
        assertThat(deliveries.get("a1", "c-c1").status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    void undeliveredReceipt_marksTheDeliveryFailed_withTheProviderCode() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        processor(new Fakes.ScriptedSms(false, SmsSendResult.accepted("SM9")), okPush(), 3).runOnce();
        new DeliveryReceiptService(deliveries, summaries, eventLog, clock).applyTwilioReceipt("a1", "c-c1", "SM9", "undelivered", "30003");

        assertThat(deliveries.get("a1", "c-c1").status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(deliveries.get("a1", "c-c1").failureCode()).isEqualTo("PROVIDER_30003");
        assertThat(stored("a1").contactSummary().state()).isEqualTo(NotificationSummary.State.FAILED);
    }

    @Test
    void providerFailure_marksFailed_butTheAlertSurvivesUntouched() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);

        processor(new Fakes.ScriptedSms(false, SmsSendResult.permanent("TWILIO_21211")), okPush(), 3).runOnce();

        assertThat(deliveries.get("a1", "c-c1").status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(deliveries.get("a1", "c-c1").failureCode()).isEqualTo("TWILIO_21211");
        assertThat(stored("a1").contactSummary().state()).isEqualTo(NotificationSummary.State.FAILED);
        assertThat(stored("a1").status()).isEqualTo(AlertStatus.ACTIVE); // the emergency alert is still there
        assertThat(jobs.byId).isEmpty(); // permanent failure: nothing left to retry
    }

    @Test
    void partialSuccess_oneRecipientSucceedsAndAnotherFails() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        contact("c2", "+919876500002", true, true);
        var sms = new Fakes.ScriptedSms(false, SmsSendResult.accepted("SM-1"), SmsSendResult.permanent("TWILIO_21614"));

        processor(sms, okPush(), 3).runOnce();

        assertThat(deliveries.get("a1", "c-c1").status()).isEqualTo(DeliveryStatus.SUBMITTED);
        assertThat(deliveries.get("a1", "c-c2").status()).isEqualTo(DeliveryStatus.FAILED);
        NotificationSummary s = stored("a1").contactSummary();
        assertThat(s.state()).isEqualTo(NotificationSummary.State.PARTIAL);
        assertThat(s.submitted()).isEqualTo(1);
        assertThat(s.failed()).isEqualTo(1);
    }

    @Test
    void aProviderThatThrows_isTreatedAsATransientFailure_andRetried() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        var sms = new Fakes.ScriptedSms(false, (SmsSendResult) null, SmsSendResult.accepted("SM-2"));
        var p = processor(sms, okPush(), 3);

        p.runOnce();
        assertThat(deliveries.get("a1", "c-c1").status()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        clock.advance(Duration.ofSeconds(21));
        p.runOnce();
        assertThat(deliveries.get("a1", "c-c1").status()).isEqualTo(DeliveryStatus.SUBMITTED);
    }

    // ---------------------------------------------------------------------------------------------- retries / dedup

    @Test
    void transientFailure_isRetriedWithBackoff_thenSucceeds_andIsNotSentTwice() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        var sms = new Fakes.ScriptedSms(false, SmsSendResult.transientFailure("HTTP_503"), SmsSendResult.accepted("SM-OK"));
        var p = processor(sms, okPush(), 3);

        p.runOnce();
        Delivery first = deliveries.get("a1", "c-c1");
        assertThat(first.status()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        assertThat(first.attemptCount()).isEqualTo(1);
        assertThat(first.nextAttemptAt()).isEqualTo(T0.plusSeconds(20));
        assertThat(stored("a1").contactSummary().state()).isEqualTo(NotificationSummary.State.PROCESSING);

        clock.advance(Duration.ofSeconds(10));
        jobs.byId.computeIfPresent("a1", (k, j) -> j.rescheduled(T0)); // the worker would wake the job at the retry time
        p.runOnce();
        assertThat(sms.calls()).isEqualTo(1); // not due yet: no second send

        clock.advance(Duration.ofSeconds(11));
        p.runOnce();
        assertThat(sms.calls()).isEqualTo(2);
        Delivery done = deliveries.get("a1", "c-c1");
        assertThat(done.status()).isEqualTo(DeliveryStatus.SUBMITTED);
        assertThat(done.attemptCount()).isEqualTo(2);
        assertThat(done.attempts()).hasSize(2);

        p.runOnce();
        p.runOnce();
        assertThat(sms.calls()).isEqualTo(2); // finished deliveries are never sent again
    }

    @Test
    void retriesAreBounded_afterTheLastAttemptTheDeliveryFails() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        var sms = new Fakes.ScriptedSms(false, SmsSendResult.transientFailure("HTTP_503"));
        var p = processor(sms, okPush(), 3);

        for (int i = 0; i < 6; i++) {
            p.runOnce();
            clock.advance(Duration.ofMinutes(10));
        }

        Delivery d = deliveries.get("a1", "c-c1");
        assertThat(sms.calls()).isEqualTo(3);
        assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(d.failureCode()).isEqualTo("RETRIES_EXHAUSTED:HTTP_503");
        assertThat(d.attempts()).hasSize(3);
        assertThat(jobs.byId).isEmpty();
    }

    @Test
    void planningTwice_doesNotDuplicateDeliveries_orMessages() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        var sms = new Fakes.ScriptedSms(false, SmsSendResult.accepted("SM-1"));
        var p = processor(sms, okPush(), 3);
        p.runOnce();
        assertThat(sms.calls()).isEqualTo(1);

        // simulate a crash that lost the "planned" flag and the job deletion: the job comes back unplanned
        jobs.byId.put("a1", NotificationJob.pending("a1", clock.instant()));
        p.runOnce();

        assertThat(deliveries.findAll("a1").stream().filter(d -> d.recipientType() == Delivery.RecipientType.CONTACT)).hasSize(1);
        assertThat(sms.calls()).isEqualTo(1); // already SUBMITTED: not sent again
    }

    @Test
    void aCrashedWorker_leavesASendingDelivery_whichIsRecoveredByAnotherRun() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        var sms = new Fakes.ScriptedSms(false, SmsSendResult.accepted("SM-1"));
        var p = processor(sms, okPush(), 3);
        // worker 1 claimed the job, planned, started an attempt (SENDING) and died
        jobs.byId.put("a1", new NotificationJob("a1", NotificationJob.State.PROCESSING, T0, T0.plusSeconds(60), true, 1, T0));
        deliveries.createIfAbsent("a1", Delivery.queued("c-c1", Delivery.RecipientType.CONTACT, "c1", "+91•••", Delivery.Channel.SMS, Delivery.Mode.REAL, T0));
        deliveries.save("a1", deliveries.get("a1", "c-c1").beginAttempt(T0));

        assertThat(p.runOnce()).isZero(); // the lease has not expired: nobody else may touch the job
        clock.advance(Duration.ofSeconds(61));
        assertThat(p.runOnce()).isEqualTo(1);

        Delivery d = deliveries.get("a1", "c-c1");
        assertThat(d.status()).isEqualTo(DeliveryStatus.SUBMITTED);
        assertThat(d.attemptCount()).isEqualTo(2); // at-least-once: the interrupted attempt counted
    }

    @Test
    void twoWorkers_cannotClaimTheSameJob() throws Exception {
        alert("a1", null, Delivery.Mode.TEST);
        contact("c1", "+919876500001", true, true);
        var sms = new Fakes.ScriptedSms(true, SmsSendResult.accepted("TEST-1"));
        var p1 = processor(sms, okPush(), 3);
        var p2 = processor(sms, okPush(), 3);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var f1 = pool.submit(p1::runOnce);
        var f2 = pool.submit(p2::runOnce);
        int claimed = f1.get() + f2.get();
        pool.shutdown();

        assertThat(claimed).isEqualTo(1);
        assertThat(sms.calls()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------- cancellation

    @Test
    void cancelledAlert_stopsUnsentSms_butStillTellsTheAdmins() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        device("d1" + "0".repeat(62), "token-aaaaaaaaaaaaaaaaaaaa");
        alerts.modify("a1", a -> a.cancelled(T0.plusSeconds(1)));
        var sms = new Fakes.ScriptedSms(false, SmsSendResult.accepted("SM-1"));
        var push = okPush();

        processor(sms, push, 3).runOnce();

        assertThat(sms.calls()).isZero();
        assertThat(deliveries.get("a1", "c-c1").status()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(push.sent).hasSize(1);
        assertThat(stored("a1").status()).isEqualTo(AlertStatus.CANCELLED);
    }

    @Test
    void aContactRemovedAfterPlanning_isSkipped() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        var sms = new Fakes.ScriptedSms(false, SmsSendResult.accepted("SM-1"));
        var p = processor(sms, okPush(), 3);
        // plan, then the user deletes the contact before the send happens
        deliveries.createIfAbsent("a1", Delivery.queued("c-c1", Delivery.RecipientType.CONTACT, "c1", "+91•••", Delivery.Channel.SMS, Delivery.Mode.REAL, T0));
        jobs.byId.put("a1", new NotificationJob("a1", NotificationJob.State.PENDING, T0, null, true, 0, T0));
        contacts.delete(UID, "c1");

        p.runOnce();

        assertThat(sms.calls()).isZero();
        assertThat(deliveries.get("a1", "c-c1").failureCode()).isEqualTo("CONTACT_REMOVED");
    }

    // ---------------------------------------------------------------------------------------------- message content

    @Test
    void smsText_includesTheLocationLinkOnlyWhenTheUserSharedIt_andNoSurnameOrFullNumber() {
        alert("a1", new AlertLocation(13.08270, 80.27070, 18.4, T0), Delivery.Mode.REAL);
        alert("a2", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        var sms = new Fakes.ScriptedSms(false, SmsSendResult.accepted("SM-1"));
        processor(sms, okPush(), 3).runOnce();

        List<String> bodies = sms.sent.stream().map(m -> m.body()).toList();
        String withLocation = bodies.stream().filter(b -> b.contains("maps.google.com")).findFirst().orElseThrow();
        String without = bodies.stream().filter(b -> !b.contains("maps.google.com")).findFirst().orElseThrow();
        assertThat(withLocation).contains("13.08270,80.27070").contains("about 18 m");
        assertThat(bodies).hasSize(2);
        for (String body : bodies) {
            assertThat(body).contains("Alice").doesNotContain("Rao").doesNotContain("+9198765").contains("emergency number");
            assertThat(body).contains("22 Oct 10:00 UTC");
        }
        assertThat(without).doesNotContain("Location:");
    }

    @Test
    void callbackUrl_isOnlyAttachedForARealProviderOverHttps() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        var sms = new Fakes.ScriptedSms(false, SmsSendResult.accepted("SM-1"));
        processor(sms, okPush(), 3).runOnce();
        assertThat(sms.sent.peek().callbackUrl()).isEqualTo("https://api.example.com/api/webhooks/sms/twilio?alertId=a1&deliveryId=c-c1");

        var mock = new Fakes.ScriptedSms(true, SmsSendResult.accepted("TEST-1"));
        alert("a2", null, Delivery.Mode.TEST);
        processor(mock, okPush(), 3).runOnce();
        assertThat(mock.sent.peek().callbackUrl()).isNull();
    }

    // ---------------------------------------------------------------------------------------------- admin push

    @Test
    void adminPush_isAcceptedNotSeen_andCarriesNoPersonalData() {
        alert("a1", new AlertLocation(13.0827, 80.2707, 10.0, T0), Delivery.Mode.TEST);
        device("d1" + "0".repeat(62), "token-aaaaaaaaaaaaaaaaaaaa");
        device("d2" + "0".repeat(62), "token-bbbbbbbbbbbbbbbbbbbb");
        var push = okPush();

        processor(new Fakes.ScriptedSms(true, SmsSendResult.accepted("TEST-1")), push, 3).runOnce();

        assertThat(push.sent).hasSize(2); // several safety-team devices
        for (Map<String, Object> message : push.sent) {
            String all = message.toString();
            assertThat(all).doesNotContain("13.0827").doesNotContain("80.2707").doesNotContain("+91").doesNotContain("Alice");
            @SuppressWarnings("unchecked") Map<String, String> data = (Map<String, String>) message.get("data");
            assertThat(data).containsEntry("alertId", "a1").hasSize(2);
        }
        assertThat(stored("a1").adminSummary().state()).isEqualTo(NotificationSummary.State.SUBMITTED);
        assertThat(stored("a1").status()).isEqualTo(AlertStatus.ACTIVE); // acknowledgement is a separate admin action
    }

    @Test
    void adminPush_withNoRegisteredDevice_isReportedAsNoRecipients() {
        alert("a1", null, Delivery.Mode.TEST);

        processor(new Fakes.ScriptedSms(true, SmsSendResult.accepted("TEST-1")), okPush(), 3).runOnce();

        assertThat(deliveries.get("a1", "p-none").failureCode()).isEqualTo("NO_ADMIN_DEVICES");
        assertThat(stored("a1").adminSummary().state()).isEqualTo(NotificationSummary.State.NO_RECIPIENTS);
    }

    @Test
    void adminPush_invalidToken_removesTheDevice_andFails() {
        alert("a1", null, Delivery.Mode.TEST);
        String id = "d1" + "0".repeat(62);
        device(id, "token-dead-aaaaaaaaaaaaaaa");
        var push = new Fakes.ScriptedPush(PushResult.of(PushResult.Outcome.INVALID_TOKEN, "FCM_UNREGISTERED"));

        processor(new Fakes.ScriptedSms(true, SmsSendResult.accepted("TEST-1")), push, 3).runOnce();

        assertThat(devices.findById(id)).isEmpty();
        assertThat(deliveries.get("a1", "p-" + id.substring(0, 16)).failureCode()).isEqualTo("TOKEN_INVALID");
        assertThat(stored("a1").adminSummary().state()).isEqualTo(NotificationSummary.State.FAILED);
    }

    @Test
    void adminPush_transientFailure_isRetried_thenSucceeds() {
        alert("a1", null, Delivery.Mode.TEST);
        String id = "d1" + "0".repeat(62);
        device(id, "token-aaaaaaaaaaaaaaaaaaaa");
        var push = new Fakes.ScriptedPush(PushResult.of(PushResult.Outcome.TRANSIENT_FAILURE, "FCM_UNAVAILABLE"), PushResult.accepted("fcm-2"));
        var p = processor(new Fakes.ScriptedSms(true, SmsSendResult.accepted("TEST-1")), push, 3);

        p.runOnce();
        assertThat(deliveries.get("a1", "p-" + id.substring(0, 16)).status()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        assertThat(stored("a1").adminSummary().state()).isEqualTo(NotificationSummary.State.PROCESSING);
        clock.advance(Duration.ofSeconds(21));
        p.runOnce();
        assertThat(deliveries.get("a1", "p-" + id.substring(0, 16)).status()).isEqualTo(DeliveryStatus.SUBMITTED);
    }

    @Test
    void fcmNotConfigured_failsHonestly_andContactsAreUnaffected() {
        alert("a1", null, Delivery.Mode.REAL);
        contact("c1", "+919876500001", true, true);
        String id = "d1" + "0".repeat(62);
        device(id, "token-aaaaaaaaaaaaaaaaaaaa");
        var push = new Fakes.ScriptedPush(PushResult.of(PushResult.Outcome.UNAVAILABLE, "FCM_NOT_CONFIGURED"));

        processor(new Fakes.ScriptedSms(false, SmsSendResult.accepted("SM-1")), push, 3).runOnce();

        assertThat(stored("a1").adminSummary().state()).isEqualTo(NotificationSummary.State.FAILED);
        assertThat(stored("a1").contactSummary().state()).isEqualTo(NotificationSummary.State.SUBMITTED);
    }

    @Test
    void adminPushRecipientPolicy_limitsPushesToTheConfiguredAdministrators() {
        alert("a1", null, Delivery.Mode.TEST);
        devices.save(new AdminDevice("e1" + "0".repeat(62), "uid-other-admin", "token-cccccccccccccccccccc", "Phone", T0, T0));
        device("d1" + "0".repeat(62), "token-aaaaaaaaaaaaaaaaaaaa");
        var push = okPush();
        NotificationProperties props = new NotificationProperties(NotificationProperties.Mode.TEST, false, 3000, 60, 3, 20, "",
                true, List.of("uid-admin"), 5, 10, 5, 3, 10, new NotificationProperties.Twilio("", "", "", "", ""));
        new NotificationProcessor(props, new Fakes.ScriptedSms(true, SmsSendResult.accepted("TEST-1")), push, alerts, deliveries, jobs,
                contacts, devices, users, summaries, eventLog, new AuditService(auditLog), clock).runOnce();

        assertThat(push.sent).hasSize(1);
    }

    // ---------------------------------------------------------------------------------------------- audit & history

    @Test
    void processing_isRecordedInTheAlertHistoryAndTheAuditLog_withoutPersonalData() {
        alert("a1", new AlertLocation(13.0827, 80.2707, 10.0, T0), Delivery.Mode.TEST);
        contact("c1", "+919876500001", true, true);

        processor(new Fakes.ScriptedSms(true, SmsSendResult.accepted("TEST-1")), okPush(), 3).runOnce();

        assertThat(events.findAll("a1")).extracting(e -> e.type()).contains("NOTIFICATIONS_PLANNED", "NOTIFICATIONS_UPDATED");
        String history = events.findAll("a1").toString();
        assertThat(history).doesNotContain("+9198765").doesNotContain("13.0827");
        var entry = org.mockito.ArgumentCaptor.forClass(com.womensafety.model.AuditLogEntry.class);
        org.mockito.Mockito.verify(auditLog).append(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("NOTIFICATIONS_PROCESSED");
        assertThat(entry.getValue().actorUid()).isEqualTo("system");
        assertThat(entry.getValue().details()).containsEntry("mode", "TEST").doesNotContainValue("13.0827");
    }

    // ---------------------------------------------------------------------------------------------- summary rules

    @Test
    void summaryStates_followTheDeliveryEvidence() {
        Instant t = T0;
        Delivery.Mode m = Delivery.Mode.REAL;
        java.util.function.Function<DeliveryStatus, Delivery> d = s ->
                Delivery.queued("x", Delivery.RecipientType.CONTACT, "c", "l", Delivery.Channel.SMS, m, t).withStatus(s, null, t);

        assertThat(NotificationSummary.of(List.of(), t).state()).isEqualTo(NotificationSummary.State.NO_RECIPIENTS);
        assertThat(NotificationSummary.of(List.of(d.apply(DeliveryStatus.QUEUED), d.apply(DeliveryStatus.DELIVERED)), t).state())
                .isEqualTo(NotificationSummary.State.PROCESSING);
        assertThat(NotificationSummary.of(List.of(d.apply(DeliveryStatus.DELIVERED), d.apply(DeliveryStatus.DELIVERED)), t).state())
                .isEqualTo(NotificationSummary.State.DELIVERED);
        assertThat(NotificationSummary.of(List.of(d.apply(DeliveryStatus.DELIVERED), d.apply(DeliveryStatus.SUBMITTED)), t).state())
                .isEqualTo(NotificationSummary.State.SUBMITTED); // one unconfirmed message: never "delivered"
        assertThat(NotificationSummary.of(List.of(d.apply(DeliveryStatus.SUBMITTED), d.apply(DeliveryStatus.FAILED)), t).state())
                .isEqualTo(NotificationSummary.State.PARTIAL);
        assertThat(NotificationSummary.of(List.of(d.apply(DeliveryStatus.FAILED)), t).state()).isEqualTo(NotificationSummary.State.FAILED);
        assertThat(NotificationSummary.of(List.of(d.apply(DeliveryStatus.SKIPPED)), t).state()).isEqualTo(NotificationSummary.State.NO_RECIPIENTS);
        assertThat(NotificationSummary.of(List.of(d.apply(DeliveryStatus.SIMULATED)), t).state()).isEqualTo(NotificationSummary.State.SIMULATED);
    }
}
