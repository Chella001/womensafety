package com.womensafety.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.womensafety.exception.IdentityServiceException;
import com.womensafety.model.AlertLocation;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.AuditLogEntry;
import com.womensafety.model.EmergencyAlert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class AdminReportsApiTest extends ApiTestBase {

    private static EmergencyAlert alert(String id, Instant at, AlertStatus status, boolean located) {
        EmergencyAlert base = TestAlerts.alert(id, "uid-bob", AlertStatus.ACTIVE, at, located ? new AlertLocation(13.0, 80.0, 10.0, at) : null);
        return switch (status) {
            case ACTIVE -> base;
            case ACKNOWLEDGED -> base.acknowledged("uid-admin", at);
            case RESOLVED -> base.resolved("uid-admin", "done", at);
            case CANCELLED -> base.cancelled(at);
        };
    }

    private String admin() {
        return as("admin");
    }

    // ---------------------------------------------------------------- summaries

    @Test
    void dailySummary_countsByCurrentStatus_andFillsEmptyDaysWithRealZeros() throws Exception {
        when(alerts.findCreatedBetween(any(), any(), anyInt())).thenReturn(List.of(
                alert("1", Instant.parse("2026-10-05T08:00:00Z"), AlertStatus.ACTIVE, true),
                alert("2", Instant.parse("2026-10-05T09:00:00Z"), AlertStatus.RESOLVED, false),
                alert("3", Instant.parse("2026-10-07T23:59:00Z"), AlertStatus.CANCELLED, false),
                alert("4", Instant.parse("2026-10-07T01:00:00Z"), AlertStatus.ACKNOWLEDGED, true)));

        mockMvc.perform(get("/api/admin/reports/alerts?period=daily&from=2026-10-05&to=2026-10-07").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("daily"))
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[0].label").value("2026-10-05"))
                .andExpect(jsonPath("$.rows[0].total").value(2))
                .andExpect(jsonPath("$.rows[0].newAlerts").value(1))
                .andExpect(jsonPath("$.rows[0].resolved").value(1))
                .andExpect(jsonPath("$.rows[0].withLocation").value(1))
                .andExpect(jsonPath("$.rows[1].total").value(0))
                .andExpect(jsonPath("$.rows[2].total").value(2))
                .andExpect(jsonPath("$.rows[2].cancelled").value(1))
                .andExpect(jsonPath("$.rows[2].acknowledged").value(1))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void summary_cutsDaysInTheCallersUtcOffset() throws Exception {
        // 20:00 UTC on the 5th is 01:30 on the 6th in India
        when(alerts.findCreatedBetween(any(), any(), anyInt())).thenReturn(List.of(
                alert("1", Instant.parse("2026-10-05T20:00:00Z"), AlertStatus.ACTIVE, false)));
        mockMvc.perform(get("/api/admin/reports/alerts?period=daily&from=2026-10-05&to=2026-10-06&utcOffsetMinutes=330").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.rows[0].total").value(0))
                .andExpect(jsonPath("$.rows[1].total").value(1))
                .andExpect(jsonPath("$.rows[1].label").value("2026-10-06"));
    }

    @Test
    void weeklySummary_startsOnMonday_andWidensToWholeWeeks() throws Exception {
        when(alerts.findCreatedBetween(any(), any(), anyInt())).thenReturn(List.of(
                alert("1", Instant.parse("2026-10-14T10:00:00Z"), AlertStatus.ACTIVE, false),
                alert("2", Instant.parse("2026-10-18T10:00:00Z"), AlertStatus.ACTIVE, false)));
        // Thu 2026-10-15 .. Fri 2026-10-16 lies inside the week of Mon 2026-10-12
        mockMvc.perform(get("/api/admin/reports/alerts?period=weekly&from=2026-10-15&to=2026-10-16").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.from").value("2026-10-12"))
                .andExpect(jsonPath("$.to").value("2026-10-18"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].label").value("Week of 2026-10-12"))
                .andExpect(jsonPath("$.rows[0].total").value(2));
    }

    @Test
    void monthlySummary_groupsByCalendarMonth() throws Exception {
        when(alerts.findCreatedBetween(any(), any(), anyInt())).thenReturn(List.of(
                alert("1", Instant.parse("2026-08-31T23:00:00Z"), AlertStatus.ACTIVE, false),
                alert("2", Instant.parse("2026-09-01T00:00:00Z"), AlertStatus.ACTIVE, false),
                alert("3", Instant.parse("2026-10-20T00:00:00Z"), AlertStatus.RESOLVED, false)));
        mockMvc.perform(get("/api/admin/reports/alerts?period=monthly&from=2026-08-10&to=2026-10-02").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[0].label").value("2026-08"))
                .andExpect(jsonPath("$.rows[0].total").value(1))
                .andExpect(jsonPath("$.rows[1].total").value(1))
                .andExpect(jsonPath("$.rows[2].resolved").value(1));
    }

    @Test
    void summary_defaultsToTheLast30Days_andRejectsBadInput() throws Exception {
        mockMvc.perform(get("/api/admin/reports/alerts").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(30));
        for (String query : new String[] {"period=yearly", "from=2026-10-10&to=2026-10-01", "from=2020-01-01&to=2026-10-01", "from=x", "utcOffsetMinutes=99999"}) {
            mockMvc.perform(get("/api/admin/reports/alerts?" + query).header(HttpHeaders.AUTHORIZATION, admin()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void summary_saysWhenItHitTheReadLimit() throws Exception {
        Instant at = Instant.parse("2026-10-05T08:00:00Z");
        when(alerts.findCreatedBetween(any(), any(), anyInt())).thenReturn(
                IntStream.range(0, 5000).mapToObj(i -> alert("a" + i, at, AlertStatus.ACTIVE, false)).toList());
        mockMvc.perform(get("/api/admin/reports/alerts?period=daily&from=2026-10-05&to=2026-10-05").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.truncated").value(true)).andExpect(jsonPath("$.rows[0].total").value(5000));
    }

    // ---------------------------------------------------------------- CSV export

    @Test
    void csv_isAnAttachment_withOnlyDatesAndCounts_andTheExportIsAudited() throws Exception {
        when(alerts.findCreatedBetween(any(), any(), anyInt())).thenReturn(List.of(
                alert("secret-alert-id", Instant.parse("2026-10-05T08:00:00Z"), AlertStatus.ACTIVE, true)));

        mockMvc.perform(get("/api/admin/reports/alerts.csv?period=daily&from=2026-10-05&to=2026-10-06").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/csv")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("safeher-alert-summary-daily.csv")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(content().string("period,period_start,total_alerts,new,acknowledged,resolved,cancelled,location_shared\r\n"
                        + "daily,2026-10-05,1,1,0,0,0,1\r\n"
                        + "daily,2026-10-06,0,0,0,0,0,0\r\n"))
                .andExpect(content().string(not(containsString("uid-"))))
                .andExpect(content().string(not(containsString("secret-alert-id"))));

        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogs).append(entry.capture());
        org.assertj.core.api.Assertions.assertThat(entry.getValue().action()).isEqualTo("EXPORT_ALERT_REPORT");
        org.assertj.core.api.Assertions.assertThat(entry.getValue().actorUid()).isEqualTo("uid-admin");
    }

    @Test
    void csv_isNotAvailableToUsersOrAnonymous_andInvalidRequestsAreNotAudited() throws Exception {
        mockMvc.perform(get("/api/admin/reports/alerts.csv")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/reports/alerts.csv").header(HttpHeaders.AUTHORIZATION, as("alice"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reports/alerts.csv?period=nope").header(HttpHeaders.AUTHORIZATION, admin())).andExpect(status().isBadRequest());
        ArgumentCaptor<AuditLogEntry> entries = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogs, org.mockito.Mockito.times(1)).append(entries.capture()); // only alice's denied attempt
        org.assertj.core.api.Assertions.assertThat(entries.getValue().action()).isEqualTo("ADMIN_ACCESS_DENIED");
    }

    // ---------------------------------------------------------------- settings

    @Test
    void settings_areReadOnly_andContainNoSecretsOrAdminUids() throws Exception {
        mockMvc.perform(get("/api/admin/settings").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.administratorCount").value(1))
                .andExpect(jsonPath("$.alertRetentionDays").value(90))
                .andExpect(jsonPath("$.notificationProvider").value("mock"))
                .andExpect(jsonPath("$.notificationMode").value("TEST"))
                .andExpect(jsonPath("$.notificationNote").value(containsString("TEST MODE: SMS messages are simulated and nothing is sent")))
                .andExpect(jsonPath("$.notificationNote").value(containsString("does not contact the police")))
                .andExpect(jsonPath("$.tokenRevocationCheck").value(true))
                .andExpect(content().string(not(containsString("uid-admin"))));
        mockMvc.perform(put("/api/admin/settings").header(HttpHeaders.AUTHORIZATION, admin())).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch("/api/admin/settings").header(HttpHeaders.AUTHORIZATION, admin())).andExpect(status().isMethodNotAllowed());
    }

    // ---------------------------------------------------------------- audit log

    @Test
    void auditLog_canBeFilteredByType_andIsPaged() throws Exception {
        List<AuditLogEntry> entries = IntStream.range(0, 25).mapToObj(i -> new AuditLogEntry("l" + i,
                i % 5 == 0 ? "SECURITY_EVENT" : "ADMIN_ACTION", "ACTION_" + i, "uid-admin", null, Map.of(), Instant.now(),
                i % 5 == 0 ? "DENIED" : "SUCCESS")).toList();
        when(auditLogs.findRecent(500)).thenReturn(entries);

        mockMvc.perform(get("/api/admin/audit-logs?type=SECURITY_EVENT").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.total").value(5)).andExpect(jsonPath("$.items[0].outcome").value("DENIED"));
        mockMvc.perform(get("/api/admin/audit-logs?type=admin_action&page=1&size=10").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(jsonPath("$.total").value(20)).andExpect(jsonPath("$.items.length()").value(10)).andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/admin/audit-logs?type=BOGUS").header(HttpHeaders.AUTHORIZATION, admin())).andExpect(status().isBadRequest());
    }

    @Test
    void auditLog_offersNoWayToModifyOrDeleteEntries() throws Exception {
        for (var method : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                put("/api/admin/audit-logs"), patch("/api/admin/audit-logs"), delete("/api/admin/audit-logs"),
                delete("/api/admin/audit-logs/l1"), patch("/api/admin/audit-logs/l1")}) {
            mockMvc.perform(method.header(HttpHeaders.AUTHORIZATION, admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().is4xxClientError());
        }
        // and a normal user is turned away before reaching any of it
        mockMvc.perform(delete("/api/admin/audit-logs").header(HttpHeaders.AUTHORIZATION, as("alice"))).andExpect(status().isForbidden());
    }

    @Test
    void aFailedFirebaseUpdate_isRecordedAsAFailure_andReportedAsUnavailable() throws Exception {
        doThrow(new IdentityServiceException("down", new RuntimeException())).when(identity).setDisabled("uid-bob", true);
        mockMvc.perform(patch("/api/admin/users/uid-bob/status").header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\",\"reason\":\"test\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_SERVICE_UNAVAILABLE"));
        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogs).append(entry.capture());
        org.assertj.core.api.Assertions.assertThat(entry.getValue().action()).isEqualTo("SUSPEND_USER");
        org.assertj.core.api.Assertions.assertThat(entry.getValue().outcome()).isEqualTo("FAILURE");
        verify(identity, never()).revokeSessions("uid-bob");
    }
}
