package com.womensafety.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import com.womensafety.model.AlertLocation;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.AuditLogEntry;
import com.womensafety.model.EmergencyAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class AdminAlertsApiTest extends ApiTestBase {

    private static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");

    @BeforeEach
    void echoSaves() {
        when(alerts.save(any(EmergencyAlert.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static EmergencyAlert newAlert(String id, String uid, Instant at) {
        return TestAlerts.alert(id, uid, AlertStatus.ACTIVE, at, null);
    }

    private static EmergencyAlert withLocation(String id, Instant at) {
        return TestAlerts.alert(id, "uid-bob", AlertStatus.ACTIVE, at, new AlertLocation(13.0827, 80.2707, 20.0, at));
    }

    private static String admin() {
        return as("admin");
    }

    // ---------------------------------------------------------------- listing, filters, paging

    @Test
    void list_filtersByStatus_andSortsNewestFirst() throws Exception {
        EmergencyAlert a = newAlert("a1", "uid-bob", T0);
        EmergencyAlert b = newAlert("a2", "uid-alice", T0.plusSeconds(60)).acknowledged("uid-admin", T0.plusSeconds(90));
        EmergencyAlert c = newAlert("a3", "uid-bob", T0.plusSeconds(120)).resolved("uid-admin", "done", T0.plusSeconds(150));
        EmergencyAlert d = newAlert("a4", "uid-bob", T0.plusSeconds(180)).cancelled(T0.plusSeconds(200));
        when(alerts.findRecent(1000)).thenReturn(List.of(a, b, c, d));

        mockMvc.perform(get("/api/admin/alerts").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[0].id").value("a4"))
                .andExpect(jsonPath("$.items[3].id").value("a1"));
        mockMvc.perform(get("/api/admin/alerts?status=ACKNOWLEDGED").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value("a2"))
                .andExpect(jsonPath("$.items[0].acknowledgedAt").exists());
        mockMvc.perform(get("/api/admin/alerts?status=RESOLVED").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value("a3"));
        mockMvc.perform(get("/api/admin/alerts?status=ACTIVE").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value("a1"));
    }

    @Test
    void list_filtersByDateRange_inTheCallersUtcOffset() throws Exception {
        when(alerts.findCreatedBetween(any(), any(), anyInt())).thenReturn(List.of(newAlert("a1", "uid-bob", T0)));

        mockMvc.perform(get("/api/admin/alerts?from=2026-10-10&to=2026-10-12").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        verify(alerts).findCreatedBetween(Instant.parse("2026-10-10T00:00:00Z"), Instant.parse("2026-10-13T00:00:00Z"), 1000);

        // 2026-10-10 00:00 in UTC+05:30 is 2026-10-09 18:30 UTC
        mockMvc.perform(get("/api/admin/alerts?from=2026-10-10&to=2026-10-12&utcOffsetMinutes=330").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk());
        verify(alerts).findCreatedBetween(Instant.parse("2026-10-09T18:30:00Z"), Instant.parse("2026-10-12T18:30:00Z"), 1000);
    }

    @Test
    void list_rejectsBadFilters() throws Exception {
        for (String query : new String[] {"from=2026-10-12&to=2026-10-01", "from=not-a-date", "status=BANANA", "utcOffsetMinutes=9999&from=2026-10-01"}) {
            mockMvc.perform(get("/api/admin/alerts?" + query).header(HttpHeaders.AUTHORIZATION, admin()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void list_isPaged_withBoundedPageSize() throws Exception {
        List<EmergencyAlert> many = IntStream.range(0, 45).mapToObj(i -> newAlert("a" + i, "uid-bob", T0.plusSeconds(i))).toList();
        when(alerts.findRecent(1000)).thenReturn(many);

        mockMvc.perform(get("/api/admin/alerts?page=2&size=20").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.total").value(45))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.page").value(2));
        mockMvc.perform(get("/api/admin/alerts?page=9&size=20").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/admin/alerts?size=100000").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.size").value(100)).andExpect(jsonPath("$.items.length()").value(45));
        mockMvc.perform(get("/api/admin/alerts?page=-4&size=0").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1));
    }

    @Test
    void list_withNothingStored_isAnEmptyPageNotInventedData() throws Exception {
        mockMvc.perform(get("/api/admin/alerts").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.totalPages").value(0));
    }

    // ---------------------------------------------------------------- acknowledge

    @Test
    void acknowledge_marksANewAlertSeen_recordsWho_andAudits_withoutNotifyingAnyone() throws Exception {
        when(alerts.findById("a1")).thenReturn(Optional.of(newAlert("a1", "uid-bob", T0)));

        mockMvc.perform(post("/api/admin/alerts/a1/acknowledge").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.notification.state").value("QUEUED"))
                .andExpect(jsonPath("$.location").doesNotExist());

        ArgumentCaptor<EmergencyAlert> saved = ArgumentCaptor.forClass(EmergencyAlert.class);
        verify(alerts).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().acknowledgedBy()).isEqualTo("uid-admin");
        org.assertj.core.api.Assertions.assertThat(saved.getValue().acknowledgedAt()).isNotNull();

        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogs).append(entry.capture());
        org.assertj.core.api.Assertions.assertThat(entry.getValue().action()).isEqualTo("ACKNOWLEDGE_ALERT");
        org.assertj.core.api.Assertions.assertThat(entry.getValue().actorUid()).isEqualTo("uid-admin");
        org.assertj.core.api.Assertions.assertThat(entry.getValue().targetUid()).isEqualTo("uid-bob");
        org.assertj.core.api.Assertions.assertThat(entry.getValue().outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void acknowledge_isOnlyAllowedFromNew() throws Exception {
        when(alerts.findById("ack")).thenReturn(Optional.of(newAlert("ack", "uid-bob", T0).acknowledged("uid-admin", T0)));
        when(alerts.findById("res")).thenReturn(Optional.of(newAlert("res", "uid-bob", T0).resolved("uid-admin", "ok", T0)));
        when(alerts.findById("can")).thenReturn(Optional.of(newAlert("can", "uid-bob", T0).cancelled(T0)));
        for (String id : new String[] {"ack", "res", "can"}) {
            mockMvc.perform(post("/api/admin/alerts/" + id + "/acknowledge").header(HttpHeaders.AUTHORIZATION, admin()))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALERT_NOT_NEW"));
        }
        verify(alerts, never()).save(any());
        verify(auditLogs, never()).append(any());
    }

    @Test
    void acknowledgeAndResolve_unknownOrMalformedId_is404() throws Exception {
        mockMvc.perform(post("/api/admin/alerts/ghost/acknowledge").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/alerts/bad..id/acknowledge").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/alerts/ghost/resolve").header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"fine\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- resolve

    @Test
    void resolve_closesAnAcknowledgedOrNewAlert_withNote_andAudits() throws Exception {
        when(alerts.findById("ack")).thenReturn(Optional.of(newAlert("ack", "uid-bob", T0).acknowledged("uid-admin", T0)));
        when(alerts.findById("new")).thenReturn(Optional.of(newAlert("new", "uid-bob", T0)));

        for (String id : new String[] {"ack", "new"}) {
            mockMvc.perform(post("/api/admin/alerts/" + id + "/resolve").header(HttpHeaders.AUTHORIZATION, admin())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"  Called the user, she is safe  \"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
        }
        ArgumentCaptor<EmergencyAlert> saved = ArgumentCaptor.forClass(EmergencyAlert.class);
        verify(alerts, org.mockito.Mockito.times(2)).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getAllValues()).allSatisfy(a -> {
            org.assertj.core.api.Assertions.assertThat(a.resolutionNote()).isEqualTo("Called the user, she is safe");
            org.assertj.core.api.Assertions.assertThat(a.resolvedBy()).isEqualTo("uid-admin");
            org.assertj.core.api.Assertions.assertThat(a.resolvedAt()).isNotNull();
        });
        ArgumentCaptor<AuditLogEntry> entries = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogs, org.mockito.Mockito.times(2)).append(entries.capture());
        org.assertj.core.api.Assertions.assertThat(entries.getAllValues()).allSatisfy(e -> {
            org.assertj.core.api.Assertions.assertThat(e.action()).isEqualTo("RESOLVE_ALERT");
            org.assertj.core.api.Assertions.assertThat(e.details().toString()).doesNotContain("safe"); // note stays on the record
        });
    }

    @Test
    void resolve_requiresAReasonableNote() throws Exception {
        when(alerts.findById("a1")).thenReturn(Optional.of(newAlert("a1", "uid-bob", T0)));
        for (String body : new String[] {"{}", "{\"note\":\"\"}", "{\"note\":\"  \"}", "{\"note\":\"ok\"}", "{\"note\":\"" + "n".repeat(501) + "\"}", "not json"}) {
            mockMvc.perform(post("/api/admin/alerts/a1/resolve").header(HttpHeaders.AUTHORIZATION, admin())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(alerts, never()).save(any());
    }

    @Test
    void resolve_isRefusedForFinishedAlerts() throws Exception {
        when(alerts.findById("res")).thenReturn(Optional.of(newAlert("res", "uid-bob", T0).resolved("uid-admin", "ok", T0)));
        when(alerts.findById("can")).thenReturn(Optional.of(newAlert("can", "uid-bob", T0).cancelled(T0)));
        for (String id : new String[] {"res", "can"}) {
            mockMvc.perform(post("/api/admin/alerts/" + id + "/resolve").header(HttpHeaders.AUTHORIZATION, admin())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"again\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALERT_NOT_OPEN"));
        }
        verify(alerts, never()).save(any());
    }

    @Test
    void detail_showsTheResolutionAndLocation_only_toTheAdmin() throws Exception {
        EmergencyAlert resolved = withLocation("a1", T0).resolved("uid-admin", "Handled", T0.plusSeconds(60));
        when(alerts.findById("a1")).thenReturn(Optional.of(resolved));

        mockMvc.perform(get("/api/admin/alerts/a1").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionNote").value("Handled"))
                .andExpect(jsonPath("$.location.latitude").value(13.0827));
        // the alert's owner sees status but never the administrator's note
        mockMvc.perform(get("/api/emergency-alerts/a1").header(HttpHeaders.AUTHORIZATION, as("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").doesNotExist());
        // another user cannot see it at all
        mockMvc.perform(get("/api/emergency-alerts/a1").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAlertTheAdminAlreadyAcknowledged_canNoLongerBeCancelledByTheUser() throws Exception {
        when(alerts.findById("a1")).thenReturn(Optional.of(newAlert("a1", "uid-bob", T0).acknowledged("uid-admin", T0)));
        mockMvc.perform(post("/api/emergency-alerts/a1/cancel").header(HttpHeaders.AUTHORIZATION, as("bob")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALERT_NOT_ACTIVE"));
    }
}
