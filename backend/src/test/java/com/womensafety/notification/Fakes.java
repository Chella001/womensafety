package com.womensafety.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.UnaryOperator;

import com.womensafety.exception.ResourceNotFoundException;
import com.womensafety.model.AdminDevice;
import com.womensafety.model.AlertEvent;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.Delivery;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.EmergencyContact;
import com.womensafety.model.NotificationJob;
import com.womensafety.repository.AdminDeviceRepository;
import com.womensafety.repository.AlertEventRepository;
import com.womensafety.repository.DeliveryRepository;
import com.womensafety.repository.EmergencyAlertRepository;
import com.womensafety.repository.EmergencyContactRepository;
import com.womensafety.repository.NotificationJobRepository;

/** In-memory test doubles for the persistence ports and the two providers. Thread-safe where the tests need it. */
public final class Fakes {

    private Fakes() {
    }

    /** A clock the test moves by hand. */
    public static final class TestClock extends Clock {
        private volatile Instant now;

        public TestClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    public static final class Alerts implements EmergencyAlertRepository {
        public final Map<String, EmergencyAlert> byId = new ConcurrentHashMap<>();
        public final Jobs jobs;

        public Alerts(Jobs jobs) {
            this.jobs = jobs;
        }

        @Override
        public EmergencyAlert save(EmergencyAlert alert) {
            byId.put(alert.id(), alert);
            return alert;
        }

        @Override
        public boolean createIfAbsent(EmergencyAlert alert, NotificationJob job) {
            if (byId.putIfAbsent(alert.id(), alert) != null) {
                return false;
            }
            jobs.byId.put(job.alertId(), job);
            return true;
        }

        @Override
        public synchronized EmergencyAlert modify(String alertId, UnaryOperator<EmergencyAlert> mutator) {
            EmergencyAlert current = byId.get(alertId);
            if (current == null) {
                throw new ResourceNotFoundException("That alert does not exist.");
            }
            EmergencyAlert next = mutator.apply(current);
            byId.put(alertId, next);
            return next;
        }

        @Override
        public Optional<EmergencyAlert> findById(String alertId) {
            return Optional.ofNullable(byId.get(alertId));
        }

        @Override
        public List<EmergencyAlert> findByUid(String uid) {
            return byId.values().stream().filter(a -> a.uid().equals(uid)).toList();
        }

        @Override
        public List<EmergencyAlert> findRecent(int limit) {
            return byId.values().stream().sorted(Comparator.comparing(EmergencyAlert::createdAt).reversed()).limit(limit).toList();
        }

        @Override
        public List<EmergencyAlert> findCreatedBetween(Instant from, Instant to, int limit) {
            return findRecent(Integer.MAX_VALUE).stream()
                    .filter(a -> !a.createdAt().isBefore(from) && a.createdAt().isBefore(to)).limit(limit).toList();
        }

        @Override
        public long countAll() {
            return byId.size();
        }

        @Override
        public long countByStatus(AlertStatus status) {
            return byId.values().stream().filter(a -> a.status() == status).count();
        }

        @Override
        public long countCreatedSince(Instant since) {
            return byId.values().stream().filter(a -> !a.createdAt().isBefore(since)).count();
        }

        @Override
        public List<EmergencyAlert> findExpired(Instant now, int limit) {
            return byId.values().stream().filter(a -> a.retainUntil() != null && a.retainUntil().isBefore(now))
                    .sorted(Comparator.comparing(EmergencyAlert::retainUntil)).limit(limit).toList();
        }

        @Override
        public void deleteWithHistory(String alertId) {
            byId.remove(alertId);
            jobs.byId.remove(alertId);
        }
    }

    public static final class Jobs implements NotificationJobRepository {
        public final Map<String, NotificationJob> byId = new ConcurrentHashMap<>();

        @Override
        public synchronized List<NotificationJob> claimDue(Instant now, Instant leaseUntil, int limit) {
            List<NotificationJob> claimed = new ArrayList<>();
            for (NotificationJob job : new ArrayList<>(byId.values())) {
                if (claimed.size() < limit && job.due(now)) {
                    NotificationJob leased = job.claimed(leaseUntil);
                    byId.put(job.alertId(), leased);
                    claimed.add(leased);
                }
            }
            return claimed;
        }

        @Override
        public Optional<NotificationJob> findById(String alertId) {
            return Optional.ofNullable(byId.get(alertId));
        }

        @Override
        public void save(NotificationJob job) {
            byId.put(job.alertId(), job);
        }

        @Override
        public void delete(String alertId) {
            byId.remove(alertId);
        }

        @Override
        public long countOpen() {
            return byId.size();
        }
    }

    public static final class Deliveries implements DeliveryRepository {
        public final Map<String, Map<String, Delivery>> byAlert = new ConcurrentHashMap<>();

        private Map<String, Delivery> of(String alertId) {
            return byAlert.computeIfAbsent(alertId, k -> new ConcurrentHashMap<>());
        }

        @Override
        public boolean createIfAbsent(String alertId, Delivery delivery) {
            return of(alertId).putIfAbsent(delivery.id(), delivery) == null;
        }

        @Override
        public Delivery save(String alertId, Delivery delivery) {
            of(alertId).put(delivery.id(), delivery);
            return delivery;
        }

        @Override
        public Optional<Delivery> findById(String alertId, String deliveryId) {
            return Optional.ofNullable(of(alertId).get(deliveryId));
        }

        @Override
        public List<Delivery> findAll(String alertId) {
            return of(alertId).values().stream().sorted(Comparator.comparing(Delivery::id)).toList();
        }

        public Delivery get(String alertId, String deliveryId) {
            return of(alertId).get(deliveryId);
        }
    }

    public static final class Events implements AlertEventRepository {
        public final Map<String, List<AlertEvent>> byAlert = new ConcurrentHashMap<>();

        @Override
        public void append(String alertId, AlertEvent event) {
            byAlert.computeIfAbsent(alertId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(event);
        }

        @Override
        public List<AlertEvent> findAll(String alertId) {
            return byAlert.getOrDefault(alertId, List.of());
        }
    }

    public static final class Devices implements AdminDeviceRepository {
        public final Map<String, AdminDevice> byId = new LinkedHashMap<>();

        @Override
        public synchronized void save(AdminDevice device) {
            byId.put(device.id(), device);
        }

        @Override
        public synchronized Optional<AdminDevice> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public synchronized List<AdminDevice> findAll() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public synchronized List<AdminDevice> findByUid(String uid) {
            return byId.values().stream().filter(d -> d.uid().equals(uid)).toList();
        }

        @Override
        public synchronized boolean delete(String id) {
            return byId.remove(id) != null;
        }
    }

    public static final class Contacts implements EmergencyContactRepository {
        public final Map<String, Map<String, EmergencyContact>> byUser = new ConcurrentHashMap<>();

        @Override
        public List<EmergencyContact> findAll(String uid) {
            return byUser.getOrDefault(uid, Map.of()).values().stream().sorted(Comparator.comparing(EmergencyContact::id)).toList();
        }

        @Override
        public Optional<EmergencyContact> findById(String uid, String contactId) {
            return Optional.ofNullable(byUser.getOrDefault(uid, Map.of()).get(contactId));
        }

        @Override
        public EmergencyContact save(String uid, EmergencyContact contact) {
            byUser.computeIfAbsent(uid, k -> new ConcurrentHashMap<>()).put(contact.id(), contact);
            return contact;
        }

        @Override
        public boolean delete(String uid, String contactId) {
            return byUser.getOrDefault(uid, new ConcurrentHashMap<>()).remove(contactId) != null;
        }
    }

    /** Scripted SMS provider: each call takes the next result (the last one repeats). Records what it was asked to send. */
    public static final class ScriptedSms implements SmsNotificationProvider {
        public final ConcurrentLinkedQueue<SmsMessage> sent = new ConcurrentLinkedQueue<>();
        private final List<SmsSendResult> script;
        private final boolean simulated;
        private int calls;

        public ScriptedSms(boolean simulated, SmsSendResult... script) {
            this.simulated = simulated;
            this.script = java.util.Arrays.asList(script);
        }

        @Override
        public String name() {
            return simulated ? "mock" : "scripted";
        }

        @Override
        public boolean simulated() {
            return simulated;
        }

        @Override
        public synchronized SmsSendResult send(SmsMessage message) {
            sent.add(message);
            SmsSendResult result = script.get(Math.min(calls, script.size() - 1));
            calls++;
            if (result == null) {
                throw new IllegalStateException("provider blew up");
            }
            return result;
        }

        public int calls() {
            return sent.size();
        }
    }

    /** Scripted push sender, same idea. */
    public static final class ScriptedPush implements PushSender {
        public final ConcurrentLinkedQueue<Map<String, Object>> sent = new ConcurrentLinkedQueue<>();
        private final List<PushResult> script;
        private int calls;

        public ScriptedPush(PushResult... script) {
            this.script = java.util.Arrays.asList(script);
        }

        @Override
        public synchronized PushResult send(String token, String title, String body, Map<String, String> data) {
            sent.add(Map.of("token", token, "title", title, "body", body, "data", data));
            PushResult result = script.get(Math.min(calls, script.size() - 1));
            calls++;
            return result;
        }
    }
}
