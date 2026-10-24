package com.womensafety.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import com.womensafety.model.AlertLocation;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.EmergencyAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class EmergencyAlertApiTest extends ApiTestBase {

    @BeforeEach
    void saveEchoes() {
        when(alerts.save(any(EmergencyAlert.class))).thenAnswer(call -> call.getArgument(0));
        when(alerts.findByUid(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
    }

    private static EmergencyAlert alert(String id, String uid, AlertStatus status, Instant createdAt, AlertLocation loc) {
        return TestAlerts.alert(id, uid, status, createdAt, loc);
    }

    private static String freshLocationJson(double lat, double lng, Instant capturedAt) {
        return "{\"location\":{\"latitude\":" + lat + ",\"longitude\":" + lng + ",\"accuracyMeters\":20,\"capturedAt\":\""
                + capturedAt + "\"}}";
    }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/emergency-alerts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/emergency-alerts/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/emergency-alerts/a1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/emergency-alerts/a1/cancel")).andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutLocation_savesTheAlert_andReportsNotificationsAsStillProcessing() throws Exception {
        mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.uid").value("uid-alice"))
                .andExpect(jsonPath("$.testMode").value(true))
                .andExpect(jsonPath("$.locationStatus").value("NOT_REQUESTED"))
                .andExpect(jsonPath("$.notification.state").value("QUEUED"))
                .andExpect(jsonPath("$.notification.message").value(org.hamcrest.Matchers.containsString("being processed")))
                .andExpect(jsonPath("$.location").doesNotExist());
    }

    @Test
    void create_acceptsAnEmptyBodyToo() throws Exception {
        mockMvc.perform(post("/api/emergency-alerts").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.location").doesNotExist());
    }

    @Test
    void create_withExplicitlySharedLocation_storesItWithRetention() throws Exception {
        mockMvc.perform(post("/api/emergency-alerts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(freshLocationJson(13.0827, 80.2707, Instant.now().minusSeconds(30))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.location.latitude").value(13.0827))
                .andExpect(jsonPath("$.location.longitude").value(80.2707));
        ArgumentCaptor<EmergencyAlert> saved = ArgumentCaptor.forClass(EmergencyAlert.class);
        verify(alerts).createIfAbsent(saved.capture(), any());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().retainUntil())
                .isAfter(Instant.now().plus(Duration.ofDays(89)));
        org.assertj.core.api.Assertions.assertThat(saved.getValue().uid()).isEqualTo("uid-alice");
    }

    @Test
    void create_rejectsInvalidOrStaleLocations() throws Exception {
        String[] bad = {
                freshLocationJson(91, 10, Instant.now()),
                freshLocationJson(-91, 10, Instant.now()),
                freshLocationJson(10, 181, Instant.now()),
                freshLocationJson(10, 10, Instant.now().minus(Duration.ofMinutes(30))),
                freshLocationJson(10, 10, Instant.now().plus(Duration.ofHours(1))),
                "{\"location\":{\"latitude\":10,\"capturedAt\":\"" + Instant.now() + "\"}}",
        };
        for (String body : bad) {
            mockMvc.perform(post("/api/emergency-alerts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(alerts, never()).createIfAbsent(any(), any());
    }

    @Test
    void create_isRateLimitedPerUser() throws Exception {
        Instant now = Instant.now();
        List<EmergencyAlert> recent = IntStream.range(0, 5)
                .mapToObj(i -> alert("a" + i, "uid-alice", AlertStatus.CANCELLED, now.minusSeconds(60L * i + 10), null)).toList();
        when(alerts.findByUid("uid-alice")).thenReturn(recent);

        mockMvc.perform(post("/api/emergency-alerts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verify(alerts, never()).createIfAbsent(any(), any());

        // bob is unaffected by alice's alerts
        mockMvc.perform(post("/api/emergency-alerts").header(HttpHeaders.AUTHORIZATION, as("bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    void history_isNewestFirst_andOnlyTheCallers() throws Exception {
        Instant now = Instant.now();
        when(alerts.findByUid("uid-alice")).thenReturn(List.of(
                alert("old", "uid-alice", AlertStatus.CANCELLED, now.minus(Duration.ofDays(2)), null),
                alert("new", "uid-alice", AlertStatus.ACTIVE, now.minus(Duration.ofMinutes(1)), null)));

        mockMvc.perform(get("/api/emergency-alerts/me").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("new"))
                .andExpect(jsonPath("$[1].id").value("old"));
        mockMvc.perform(get("/api/emergency-alerts/me").header(HttpHeaders.AUTHORIZATION, as("bob")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void get_ownAlert_works_butSomeoneElsesIs404() throws Exception {
        when(alerts.findById("a1")).thenReturn(Optional.of(alert("a1", "uid-alice", AlertStatus.ACTIVE, Instant.now(), null)));

        mockMvc.perform(get("/api/emergency-alerts/a1").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("a1"));
        mockMvc.perform(get("/api/emergency-alerts/a1").header(HttpHeaders.AUTHORIZATION, as("bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancel_ownActiveAlert_works() throws Exception {
        when(alerts.findById("a1")).thenReturn(Optional.of(alert("a1", "uid-alice", AlertStatus.ACTIVE, Instant.now(), null)));
        mockMvc.perform(post("/api/emergency-alerts/a1/cancel").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationRequestedAt").isNotEmpty())
                .andExpect(jsonPath("$.cancellationNote").value(org.hamcrest.Matchers.containsString("cannot be recalled")));
    }

    @Test
    void cancel_someoneElsesAlert_is404_andNothingIsSaved() throws Exception {
        when(alerts.findById("a1")).thenReturn(Optional.of(alert("a1", "uid-alice", AlertStatus.ACTIVE, Instant.now(), null)));
        mockMvc.perform(post("/api/emergency-alerts/a1/cancel").header(HttpHeaders.AUTHORIZATION, as("bob")))
                .andExpect(status().isNotFound());
        verify(alerts, never()).save(any());
    }

    @Test
    void cancel_anAlreadyCancelledAlert_is409() throws Exception {
        when(alerts.findById("a1")).thenReturn(Optional.of(alert("a1", "uid-alice", AlertStatus.CANCELLED, Instant.now(), null)));
        mockMvc.perform(post("/api/emergency-alerts/a1/cancel").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALERT_NOT_ACTIVE"));
    }

    // ------------------------------------------------------------------ /api/sos, idempotency, location status

    @Test
    void sosEndpoints_requireAuthentication_andRejectForgedTokens() throws Exception {
        mockMvc.perform(post("/api/sos").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/sos/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/sos/a1/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/sos/a1/cancel")).andExpect(status().isUnauthorized());
        rejectToken("forged");
        mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, "Bearer token-forged")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void sosEndpoints_workUnderTheNewPath_andOwnerComesOnlyFromTheToken() throws Exception {
        // A userId smuggled into the body is ignored: the alert belongs to the token's user.
        mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"uid-bob\",\"uid\":\"uid-bob\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.uid").value("uid-alice"));

        when(alerts.findById("a1")).thenReturn(Optional.of(alert("a1", "uid-alice", AlertStatus.ACTIVE, Instant.now(), null)));
        mockMvc.perform(get("/api/sos/a1/me").header(HttpHeaders.AUTHORIZATION, as("alice"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/sos/a1/me").header(HttpHeaders.AUTHORIZATION, as("bob"))).andExpect(status().isNotFound());
    }

    @Test
    void create_withAnIdempotencyKey_derivesTheIdFromUserAndKey_andStoresOnlyAHash() throws Exception {
        String key = "11111111-2222-3333-4444-555555555555";
        String first = mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("alice")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("alice")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        // same user + same key = same alert id; another user with the same key gets a different one
        org.assertj.core.api.Assertions.assertThat(idOf(first)).isEqualTo(idOf(second));
        String bobs = mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("bob")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(idOf(bobs)).isNotEqualTo(idOf(first));

        ArgumentCaptor<EmergencyAlert> saved = ArgumentCaptor.forClass(EmergencyAlert.class);
        verify(alerts, org.mockito.Mockito.atLeastOnce()).createIfAbsent(saved.capture(), any());
        EmergencyAlert stored = saved.getAllValues().get(0);
        org.assertj.core.api.Assertions.assertThat(stored.idempotencyKeyHash()).hasSize(64).isNotEqualTo(key);
    }

    @Test
    void create_repeatedWithTheSameKey_returnsTheExistingAlert_withoutCreatingAnother() throws Exception {
        String key = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        String id = java.util.UUID.nameUUIDFromBytes(("uid-alice\n" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        when(alerts.findById(id)).thenReturn(Optional.of(alert(id, "uid-alice", AlertStatus.ACTIVE, Instant.now(), null)));

        mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("alice")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));
        verify(alerts, never()).createIfAbsent(any(), any());
    }

    @Test
    void create_concurrentSubmissionsOfOneKey_produceExactlyOneAlert() throws Exception {
        String key = "cccccccc-dddd-eeee-ffff-000000000000";
        String id = java.util.UUID.nameUUIDFromBytes(("uid-alice\n" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        // Firestore create() succeeds for exactly one caller; the others see "already exists" and read the winner.
        java.util.concurrent.atomic.AtomicBoolean taken = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<EmergencyAlert> winner = new java.util.concurrent.atomic.AtomicReference<>();
        when(alerts.createIfAbsent(any(), any())).thenAnswer(call -> {
            boolean first = taken.compareAndSet(false, true);
            if (first) {
                winner.set(call.getArgument(0));
            }
            return first;
        });
        when(alerts.findById(id)).thenAnswer(call -> Optional.ofNullable(winner.get()));

        int n = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("alice")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int created = 0;
        int replayed = 0;
        for (var f : results) {
            int code = f.get(20, java.util.concurrent.TimeUnit.SECONDS);
            if (code == 201) created++;
            else if (code == 200) replayed++;
        }
        pool.shutdown();
        org.assertj.core.api.Assertions.assertThat(created).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(replayed).isEqualTo(n - 1);
        org.assertj.core.api.Assertions.assertThat(winner.get().id()).isEqualTo(id);
    }

    @Test
    void create_rejectsAMalformedIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("alice")).header("Idempotency-Key", "short")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(alerts, never()).createIfAbsent(any(), any());
    }

    @Test
    void create_whenLocationIsUnavailable_stillCreatesTheAlert_andSaysWhy() throws Exception {
        for (String reason : new String[] {"PERMISSION_DENIED", "UNAVAILABLE", "TIMEOUT"}) {
            mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("alice"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"locationStatus\":\"" + reason + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.locationStatus").value(reason))
                    .andExpect(jsonPath("$.location").doesNotExist());
        }
        // claiming AVAILABLE without coordinates is not accepted as fact
        mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locationStatus\":\"AVAILABLE\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.locationStatus").value("UNAVAILABLE"));
        mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locationStatus\":\"SOMETHING_ELSE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_withALocation_reportsItAsAvailable() throws Exception {
        mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(freshLocationJson(13.0, 80.0, Instant.now())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.locationStatus").value("AVAILABLE"));
    }

    @Test
    void notificationFailure_neverUndoesAnAlert_theAlertIsStoredBeforeAnyWork() throws Exception {
        // The worker is a separate step that runs after the alert was stored; creating returns 201 regardless of it.
        mockMvc.perform(post("/api/sos").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        verify(alerts).createIfAbsent(any(), org.mockito.ArgumentMatchers.argThat(job -> job.alertId() != null && !job.planned()));
    }

    private static String idOf(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"").matcher(json);
        if (!m.find()) throw new IllegalStateException(json);
        return m.group(1);
    }

    @Test
    void incidentReports_canBeFiledAndListedByTheOwnerOnly() throws Exception {
        when(reports.save(any())).thenAnswer(call -> call.getArgument(0));
        mockMvc.perform(post("/api/incident-reports").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"HARASSMENT\",\"description\":\"A man followed me from the bus stop.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.uid").value("uid-alice"));

        mockMvc.perform(post("/api/incident-reports").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"NOPE\",\"description\":\"too short\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/incident-reports/me")).andExpect(status().isUnauthorized());
    }
}
