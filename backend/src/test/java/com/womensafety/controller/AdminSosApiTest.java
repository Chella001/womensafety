package com.womensafety.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.womensafety.model.AdminDevice;
import com.womensafety.model.AlertEvent;
import com.womensafety.model.AlertLocation;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.AuditLogEntry;
import com.womensafety.model.Delivery;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.NotificationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** The admin SOS module: /api/admin/sos, status changes, deliveries, devices, stream. */
class AdminSosApiTest extends ApiTestBase {

    private static final String ADMIN_ID = "uid-admin";

    @BeforeEach
    void echoSaves() {
        when(alerts.save(any(EmergencyAlert.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static EmergencyAlert alert(String id, AlertStatus status, Instant at) {
        return TestAlerts.alert(id, "uid-bobby-nair-0001", status, at, null);
    }

    private static String admin() {
        return as("admin");
    }

    // ------------------------------------------------------------------ authorization on every new endpoint

    @Test
    void everyNewAdminEndpoint_is403ForARegularUser_and401WithoutAToken() throws Exception {
        String id = "11111111-2222-3333-4444-555555555555";
        String device = "a".repeat(64);
        record Call(String method, String path, String body) { }
        List<Call> calls = List.of(
                new Call("GET", "/api/admin/sos", null),
                new Call("GET", "/api/admin/sos/" + id, null),
                new Call("GET", "/api/admin/sos/" + id + "/deliveries", null),
                new Call("GET", "/api/admin/sos/stream", null),
                new Call("PATCH", "/api/admin/sos/" + id + "/status", "{\"status\":\"ACKNOWLEDGED\"}"),
                new Call("POST", "/api/admin/sos/" + id + "/acknowledge", null),
                new Call("POST", "/api/admin/sos/" + id + "/resolve", "{\"note\":\"done done\"}"),
                new Call("GET", "/api/admin/notifications/overview", null),
                new Call("GET", "/api/admin/devices", null),
                new Call("POST", "/api/admin/devices", "{\"token\":\"" + "t".repeat(40) + "\"}"),
                new Call("DELETE", "/api/admin/devices/" + device, null));
        for (Call c : calls) {
            var asUser = switch (c.method()) {
                case "GET" -> get(c.path());
                case "POST" -> post(c.path());
                case "PATCH" -> patch(c.path());
                default -> delete(c.path());
            };
            var anon = switch (c.method()) {
                case "GET" -> get(c.path());
                case "POST" -> post(c.path());
                case "PATCH" -> patch(c.path());
                default -> delete(c.path());
            };
            if (c.body() != null) {
                asUser.contentType(MediaType.APPLICATION_JSON).content(c.body());
                anon.contentType(MediaType.APPLICATION_JSON).content(c.body());
            }
            mockMvc.perform(asUser.header(HttpHeaders.AUTHORIZATION, as("alice")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(anon).andExpect(status().isUnauthorized());
        }
        // nothing was read or changed on behalf of the regular user
        verify(alerts, never()).modify(any(), any());
        verify(adminDevices, never()).save(any());
        verify(adminDevices, never()).delete(any());
        // and every refusal was logged as a security event
        verify(auditLogs, org.mockito.Mockito.atLeast(calls.size())).append(any());
    }

    // ------------------------------------------------------------------ list, search, masking, flags

    @Test
    void list_masksTheUser_hidesCoordinates_searchesByAlertId_andFlagsOnlyGroundedProblems() throws Exception {
        Instant old = Instant.now().minusSeconds(3600);
        EmergencyAlert stale = alert("aaaa1111-0000-0000-0000-000000000001", AlertStatus.ACTIVE, old);
        EmergencyAlert failed = alert("bbbb2222-0000-0000-0000-000000000002", AlertStatus.ACTIVE, Instant.now())
                .withSummaries(new NotificationSummary(NotificationSummary.State.FAILED, 1, 0, 0, 0, 0, 1, 0, Instant.now()),
                        new NotificationSummary(NotificationSummary.State.NO_RECIPIENTS, 1, 0, 0, 0, 0, 0, 1, Instant.now()), Instant.now());
        EmergencyAlert located = TestAlerts.alert("cccc3333-0000-0000-0000-000000000003", "uid-bobby-nair-0001", AlertStatus.ACTIVE,
                Instant.now(), new AlertLocation(13.0827, 80.2707, 12.0, Instant.now()));
        when(alerts.findRecent(1000)).thenReturn(List.of(located, failed, stale));

        mockMvc.perform(get("/api/admin/sos").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].userRef").value("uid-bobb…"))
                .andExpect(content().string(not(containsString("uid-bobby-nair-0001"))))
                .andExpect(content().string(not(containsString("13.0827"))))
                .andExpect(jsonPath("$.items[?(@.id=='cccc3333-0000-0000-0000-000000000003')].hasLocation").value(true))
                .andExpect(jsonPath("$.items[?(@.id=='aaaa1111-0000-0000-0000-000000000001')].flags[*]").value(hasItem("UNACKNOWLEDGED")))
                .andExpect(jsonPath("$.items[?(@.id=='bbbb2222-0000-0000-0000-000000000002')].flags[*]")
                        .value(hasItem("CONTACT_NOTIFICATION_PROBLEM")))
                .andExpect(jsonPath("$.items[?(@.id=='bbbb2222-0000-0000-0000-000000000002')].flags[*]").value(hasItem("ADMIN_NOT_NOTIFIED")))
                .andExpect(jsonPath("$.items[?(@.id=='cccc3333-0000-0000-0000-000000000003')].flags[*]").value(org.hamcrest.Matchers.empty()));

        mockMvc.perform(get("/api/admin/sos").param("q", "BBBB2222").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("bbbb2222-0000-0000-0000-000000000002"));
        mockMvc.perform(get("/api/admin/sos").param("q", "no-such-id").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.total").value(0));
        verify(auditLogs, never()).append(any()); // listing masked summaries is not a sensitive disclosure
    }

    // ------------------------------------------------------------------ detail + history + deliveries

    @Test
    void detail_showsLocationAndHistory_andIsAuditedBeforeItIsReturned() throws Exception {
        EmergencyAlert a = TestAlerts.alert("a1", "uid-bobby-nair-0001", AlertStatus.ACTIVE, Instant.now(),
                new AlertLocation(13.0827, 80.2707, 12.0, Instant.now()));
        when(alerts.findById("a1")).thenReturn(Optional.of(a));
        when(alertEvents.findAll("a1")).thenReturn(List.of(
                new AlertEvent("e1", AlertEvent.CREATED, "user", Instant.now(), Map.of("locationStatus", "AVAILABLE"))));

        mockMvc.perform(get("/api/admin/sos/a1").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location.latitude").value(13.0827))
                .andExpect(jsonPath("$.uid").value("uid-bobby-nair-0001"))
                .andExpect(jsonPath("$.testMode").value(true))
                .andExpect(jsonPath("$.events[0].type").value("ALERT_CREATED"))
                .andExpect(jsonPath("$.notification.state").value("QUEUED"));

        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogs).append(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("VIEW_ALERT");
        assertThat(entry.getValue().details()).containsEntry("locationDisclosed", "true");
        assertThat(entry.getValue().details().toString()).doesNotContain("13.0827");
    }

    @Test
    void deliveries_areMasked_labelledHonestly_andTheAccessIsAudited() throws Exception {
        when(alerts.findById("a1")).thenReturn(Optional.of(alert("a1", AlertStatus.ACTIVE, Instant.now())));
        Instant now = Instant.now();
        Delivery submitted = Delivery.queued("c-1", Delivery.RecipientType.CONTACT, "1", "+91•••••••001", Delivery.Channel.SMS, Delivery.Mode.REAL, now)
                .withStatus(com.womensafety.model.DeliveryStatus.SUBMITTED, null, now);
        Delivery simulated = Delivery.queued("c-2", Delivery.RecipientType.CONTACT, "2", "+91•••••••002", Delivery.Channel.SMS, Delivery.Mode.TEST, now)
                .withStatus(com.womensafety.model.DeliveryStatus.SIMULATED, null, now);
        when(deliveries.findAll("a1")).thenReturn(List.of(submitted, simulated));

        mockMvc.perform(get("/api/admin/sos/a1/deliveries").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recipient").value("+91•••••••001"))
                .andExpect(jsonPath("$[0].statusLabel").value(containsString("receipt not confirmed")))
                .andExpect(jsonPath("$[1].statusLabel").value(containsString("TEST MODE")))
                .andExpect(content().string(not(containsString("+919876"))));
        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogs).append(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("VIEW_ALERT_DELIVERIES");
    }

    // ------------------------------------------------------------------ status transitions

    @Test
    void patchStatus_acknowledge_thenResolveWithANote_followsTheAllowedOrder() throws Exception {
        when(alerts.findById("a1")).thenReturn(Optional.of(alert("a1", AlertStatus.ACTIVE, Instant.now())));
        mockMvc.perform(patch("/api/admin/sos/a1/status").header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        ArgumentCaptor<EmergencyAlert> saved = ArgumentCaptor.forClass(EmergencyAlert.class);
        verify(alerts).save(saved.capture());
        assertThat(saved.getValue().acknowledgedBy()).isEqualTo(ADMIN_ID);

        when(alerts.findById("a1")).thenReturn(Optional.of(saved.getValue()));
        mockMvc.perform(patch("/api/admin/sos/a1/status").header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\",\"note\":\"Reached the user; she is safe.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
        ArgumentCaptor<AuditLogEntry> entries = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogs, org.mockito.Mockito.times(2)).append(entries.capture());
        assertThat(entries.getAllValues()).extracting(AuditLogEntry::action).containsExactly("ACKNOWLEDGE_ALERT", "RESOLVE_ALERT");
        assertThat(entries.getAllValues()).allSatisfy(e -> {
            assertThat(e.actorUid()).isEqualTo(ADMIN_ID);
            assertThat(e.outcome()).isEqualTo("SUCCESS");
            assertThat(e.details().toString()).doesNotContain("she is safe");
        });
    }

    @Test
    void patchStatus_rejectsInvalidTransitions_andNeverReopens() throws Exception {
        String ack = "{\"status\":\"ACKNOWLEDGED\"}";
        String resolve = "{\"status\":\"RESOLVED\",\"note\":\"closing this alert\"}";

        // resolved / cancelled alerts can never become active, acknowledged or resolved again
        for (AlertStatus closed : new AlertStatus[] {AlertStatus.RESOLVED, AlertStatus.CANCELLED}) {
            when(alerts.findById("a1")).thenReturn(Optional.of(alert("a1", closed, Instant.now())));
            mockMvc.perform(patch("/api/admin/sos/a1/status").header(HttpHeaders.AUTHORIZATION, admin())
                    .contentType(MediaType.APPLICATION_JSON).content(ack)).andExpect(status().isConflict());
            mockMvc.perform(patch("/api/admin/sos/a1/status").header(HttpHeaders.AUTHORIZATION, admin())
                    .contentType(MediaType.APPLICATION_JSON).content(resolve)).andExpect(status().isConflict());
        }
        // acknowledged alerts cannot be acknowledged twice
        when(alerts.findById("a1")).thenReturn(Optional.of(alert("a1", AlertStatus.ACKNOWLEDGED, Instant.now())));
        mockMvc.perform(patch("/api/admin/sos/a1/status").header(HttpHeaders.AUTHORIZATION, admin())
                .contentType(MediaType.APPLICATION_JSON).content(ack)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALERT_NOT_NEW"));

        // targets an administrator may not set, and malformed bodies
        for (String body : new String[] {"{\"status\":\"ACTIVE\"}", "{\"status\":\"CANCELLED\"}", "{\"status\":\"NOPE\"}", "{}",
                "{\"status\":\"RESOLVED\"}", "{\"status\":\"RESOLVED\",\"note\":\"ab\"}"}) {
            mockMvc.perform(patch("/api/admin/sos/a1/status").header(HttpHeaders.AUTHORIZATION, admin())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verify(alerts, never()).save(any());
        verify(auditLogs, never()).append(any());
    }

    @Test
    void patchStatus_unknownAlert_is404_andAnUnsafeIdIsNotLookedUp() throws Exception {
        when(alerts.findById("nope")).thenReturn(Optional.empty());
        mockMvc.perform(patch("/api/admin/sos/nope/status").header(HttpHeaders.AUTHORIZATION, admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACKNOWLEDGED\"}")).andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/admin/sos/bad..id/status").header(HttpHeaders.AUTHORIZATION, admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACKNOWLEDGED\"}")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ push devices

    @Test
    void devices_canBeRegisteredListedAndRemoved_byTheirOwnerOnly_andTheTokenIsNeverReturned() throws Exception {
        String token = "fcm-token-" + "x".repeat(60);
        mockMvc.perform(post("/api/admin/devices").header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\",\"label\":\"Control-room laptop\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Control-room laptop"))
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.matchesPattern("[a-f0-9]{64}")))
                .andExpect(content().string(not(containsString(token))));
        ArgumentCaptor<AdminDevice> saved = ArgumentCaptor.forClass(AdminDevice.class);
        verify(adminDevices).save(saved.capture());
        assertThat(saved.getValue().uid()).isEqualTo(ADMIN_ID);
        assertThat(saved.getValue().token()).isEqualTo(token);

        when(adminDevices.findByUid(ADMIN_ID)).thenReturn(List.of(saved.getValue()));
        mockMvc.perform(get("/api/admin/devices").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(content().string(not(containsString(token))));

        // another administrator's device is invisible: 404, nothing deleted
        AdminDevice foreign = new AdminDevice("f".repeat(64), "uid-someone-else", "t".repeat(30), "x", Instant.now(), Instant.now());
        when(adminDevices.findById(foreign.id())).thenReturn(Optional.of(foreign));
        mockMvc.perform(delete("/api/admin/devices/" + foreign.id()).header(HttpHeaders.AUTHORIZATION, admin())).andExpect(status().isNotFound());
        verify(adminDevices, never()).delete(any());

        when(adminDevices.findById(saved.getValue().id())).thenReturn(Optional.of(saved.getValue()));
        mockMvc.perform(delete("/api/admin/devices/" + saved.getValue().id()).header(HttpHeaders.AUTHORIZATION, admin())).andExpect(status().isNoContent());
        verify(adminDevices).delete(saved.getValue().id());
    }

    @Test
    void devices_rejectInvalidTokens_aTokenOfAnotherAdmin_andTooManyDevices() throws Exception {
        for (String bad : new String[] {"short", "has spaces in the token value 1234567890", "<script>alert(1)</script>" + "x".repeat(20), ""}) {
            mockMvc.perform(post("/api/admin/devices").header(HttpHeaders.AUTHORIZATION, admin())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + bad + "\"}")).andExpect(status().isBadRequest());
        }
        // the same token is already registered by somebody else
        String token = "fcm-token-" + "y".repeat(60);
        String id = java.util.HexFormat.of().formatHex(sha256(token));
        when(adminDevices.findById(id)).thenReturn(Optional.of(new AdminDevice(id, "uid-someone-else", token, "x", Instant.now(), Instant.now())));
        mockMvc.perform(post("/api/admin/devices").header(HttpHeaders.AUTHORIZATION, admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}")).andExpect(status().isConflict());

        // the per-administrator device limit
        List<AdminDevice> ten = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> new AdminDevice(String.format("%064x", i), ADMIN_ID, "t" + i + "x".repeat(30), "d", Instant.now(), Instant.now())).toList();
        when(adminDevices.findByUid(ADMIN_ID)).thenReturn(ten);
        mockMvc.perform(post("/api/admin/devices").header(HttpHeaders.AUTHORIZATION, admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"fcm-token-" + "z".repeat(60) + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_LIMIT"));
        verify(adminDevices, never()).save(any());
    }

    private static byte[] sha256(String v) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ overview + stream

    @Test
    void overview_reportsTheTestModeHonestly_andListsRecentProblems() throws Exception {
        when(adminDevices.findAll()).thenReturn(List.of());
        when(jobs.countOpen()).thenReturn(2L);
        EmergencyAlert failed = alert("bbbb", AlertStatus.ACTIVE, Instant.now()).withSummaries(
                new NotificationSummary(NotificationSummary.State.FAILED, 1, 0, 0, 0, 0, 1, 0, Instant.now()),
                NotificationSummary.queued(Instant.now()), Instant.now());
        when(alerts.findRecent(50)).thenReturn(List.of(failed, alert("cccc", AlertStatus.ACTIVE, Instant.now())));

        mockMvc.perform(get("/api/admin/notifications/overview").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TEST"))
                .andExpect(jsonPath("$.smsProvider").value("mock"))
                .andExpect(jsonPath("$.fcmConfigured").value(false))
                .andExpect(jsonPath("$.openJobs").value(2))
                .andExpect(jsonPath("$.recentProblems.length()").value(1))
                .andExpect(jsonPath("$.recentProblems[0].alertId").value("bbbb"))
                .andExpect(jsonPath("$.note").value(containsString("nothing is sent")))
                .andExpect(jsonPath("$.note").value(containsString("does not contact the police")));
    }

    @Test
    void stream_opensAnEventStreamForAnAdministrator() throws Exception {
        mockMvc.perform(get("/api/admin/sos/stream").header(HttpHeaders.AUTHORIZATION, admin()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
    }
}
