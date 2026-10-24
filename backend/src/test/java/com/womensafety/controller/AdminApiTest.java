package com.womensafety.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.womensafety.exception.DataStoreException;
import com.womensafety.model.AccountStatus;
import com.womensafety.model.AlertLocation;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.AuditLogEntry;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.IncidentReport;
import com.womensafety.model.ReportStatus;
import com.womensafety.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class AdminApiTest extends ApiTestBase {

    private static final String[] ADMIN_GETS = {
            "/api/admin/dashboard", "/api/admin/users", "/api/admin/users/uid-bob", "/api/admin/alerts",
            "/api/admin/alerts/a1", "/api/admin/incident-reports", "/api/admin/audit-logs", "/api/admin/reports/alerts",
            "/api/admin/reports/alerts.csv", "/api/admin/settings"};

    @BeforeEach
    void stubs() {
        when(users.findRecent(anyInt())).thenReturn(List.of(
                new UserProfile("uid-alice", "Alice Rao", ALICE_PHONE, true, com.womensafety.model.Role.USER, AccountStatus.ACTIVE, CREATED, CREATED, CREATED, "v", CREATED),
                new UserProfile("uid-bob", "Bob Nair", BOB_PHONE, true, com.womensafety.model.Role.USER, AccountStatus.SUSPENDED, CREATED, CREATED, CREATED, "v", CREATED)));
    }

    private List<AuditLogEntry> auditEntries(int expected) {
        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogs, org.mockito.Mockito.times(expected)).append(captor.capture());
        return captor.getAllValues();
    }

    // ---------------------------------------------------------------- who may call admin endpoints

    @Test
    void adminEndpoints_requireAToken() throws Exception {
        for (String path : ADMIN_GETS) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(patch("/api/admin/users/uid-bob/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SUSPENDED\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void ordinaryUsers_are403_onEveryAdminEndpoint_andTheAttemptIsLogged() throws Exception {
        for (String path : ADMIN_GETS) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, as("alice"))).andExpect(status().isForbidden());
        }
        mockMvc.perform(patch("/api/admin/users/uid-bob/status").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/admin/incident-reports/r1/resolve").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/alerts/a1/acknowledge")
                        .header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/alerts/a1/resolve")
                        .header(HttpHeaders.AUTHORIZATION, as("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"closing\"}"))
                .andExpect(status().isForbidden());

        List<AuditLogEntry> entries = auditEntries(ADMIN_GETS.length + 4);
        org.assertj.core.api.Assertions.assertThat(entries).allSatisfy(e -> {
            org.assertj.core.api.Assertions.assertThat(e.type()).isEqualTo("SECURITY_EVENT");
            org.assertj.core.api.Assertions.assertThat(e.action()).isEqualTo("ADMIN_ACCESS_DENIED");
            org.assertj.core.api.Assertions.assertThat(e.actorUid()).isEqualTo("uid-alice");
            org.assertj.core.api.Assertions.assertThat(e.outcome()).isEqualTo("DENIED");
        });
        verify(users, never()).save(any());
        verify(identity, never()).setDisabled(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void theAdminsUid_withANonPhoneToken_isStillRefused() throws Exception {
        signIn("adminemail", "uid-admin", null, "password");
        mockMvc.perform(get("/api/admin/dashboard").header(HttpHeaders.AUTHORIZATION, as("adminemail")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PHONE_AUTH_REQUIRED"));
    }

    @Test
    void aSuspendedAdminAccountRecord_isRefusedToo() throws Exception {
        register("uid-admin", "Site Owner", ADMIN_PHONE, AccountStatus.SUSPENDED);
        mockMvc.perform(get("/api/admin/dashboard").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    // ---------------------------------------------------------------- overview and user management

    @Test
    void dashboard_reportsRealCounts() throws Exception {
        when(users.countAll()).thenReturn(12L);
        when(users.countPhoneVerified()).thenReturn(11L);
        when(users.countByStatus(AccountStatus.ACTIVE)).thenReturn(10L);
        when(users.countByStatus(AccountStatus.SUSPENDED)).thenReturn(2L);
        when(alerts.countAll()).thenReturn(7L);
        when(alerts.countCreatedSince(any())).thenReturn(3L);
        when(reports.countByStatus(ReportStatus.OPEN)).thenReturn(4L);
        when(alerts.findRecent(5)).thenReturn(List.of(alert("a1", "uid-bob", Instant.now(), null)));
        when(alerts.countByStatus(com.womensafety.model.AlertStatus.ACTIVE)).thenReturn(2L);
        when(alerts.countByStatus(com.womensafety.model.AlertStatus.ACKNOWLEDGED)).thenReturn(1L);
        when(alerts.countByStatus(com.womensafety.model.AlertStatus.RESOLVED)).thenReturn(3L);
        when(alerts.countByStatus(com.womensafety.model.AlertStatus.CANCELLED)).thenReturn(1L);

        mockMvc.perform(get("/api/admin/dashboard").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(12))
                .andExpect(jsonPath("$.verifiedUsers").value(11))
                .andExpect(jsonPath("$.activeUsers").value(10))
                .andExpect(jsonPath("$.suspendedUsers").value(2))
                .andExpect(jsonPath("$.totalAlerts").value(7))
                .andExpect(jsonPath("$.alertsLast24Hours").value(3))
                .andExpect(jsonPath("$.alertsNew").value(2))
                .andExpect(jsonPath("$.alertsAcknowledged").value(1))
                .andExpect(jsonPath("$.alertsResolved").value(3))
                .andExpect(jsonPath("$.alertsCancelled").value(1))
                .andExpect(jsonPath("$.registrationsByDay.length()").value(14))
                .andExpect(jsonPath("$.alertsByDay.length()").value(14))
                .andExpect(jsonPath("$.openIncidentReports").value(4))
                .andExpect(jsonPath("$.recentAlerts[0].id").value("a1"))
                .andExpect(jsonPath("$.recentAlerts[0].location").doesNotExist());
    }

    @Test
    void userList_masksPhoneNumbers_andSupportsSearchAndStatusFilter() throws Exception {
        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].phoneMasked").value("+91•••••••210"));
        mockMvc.perform(get("/api/admin/users?search=bob").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].uid").value("uid-bob"));
        mockMvc.perform(get("/api/admin/users?search=9123456789").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].uid").value("uid-bob"));
        mockMvc.perform(get("/api/admin/users?status=SUSPENDED").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].accountStatus").value("SUSPENDED"));
        mockMvc.perform(get("/api/admin/users?status=BANANA").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userDetail_showsTheFullNumber_andIsAudited() throws Exception {
        mockMvc.perform(get("/api/admin/users/uid-alice").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value(ALICE_PHONE))
                .andExpect(jsonPath("$.role").value("USER"));
        AuditLogEntry entry = auditEntries(1).get(0);
        org.assertj.core.api.Assertions.assertThat(entry.action()).isEqualTo("VIEW_USER");
        org.assertj.core.api.Assertions.assertThat(entry.actorUid()).isEqualTo("uid-admin");
        org.assertj.core.api.Assertions.assertThat(entry.targetUid()).isEqualTo("uid-alice");
    }

    @Test
    void suspend_disablesTheFirebaseAccount_revokesSessions_andIsAudited() throws Exception {
        mockMvc.perform(patch("/api/admin/users/uid-alice/status").header(HttpHeaders.AUTHORIZATION, as("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\",\"reason\":\"abuse report\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("SUSPENDED"));

        ArgumentCaptor<UserProfile> saved = ArgumentCaptor.forClass(UserProfile.class);
        verify(users).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().accountStatus()).isEqualTo(AccountStatus.SUSPENDED);
        verify(identity).setDisabled("uid-alice", true);
        verify(identity).revokeSessions("uid-alice");
        AuditLogEntry entry = auditEntries(1).get(0);
        org.assertj.core.api.Assertions.assertThat(entry.action()).isEqualTo("SUSPEND_USER");
        org.assertj.core.api.Assertions.assertThat(entry.details()).containsEntry("reason", "abuse report");
    }

    @Test
    void reactivate_enablesTheFirebaseAccount_andIsAudited() throws Exception {
        register("uid-bob", "Bob Nair", BOB_PHONE, AccountStatus.SUSPENDED);
        mockMvc.perform(patch("/api/admin/users/uid-bob/status").header(HttpHeaders.AUTHORIZATION, as("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"));
        verify(identity).setDisabled("uid-bob", false);
        verify(identity, never()).revokeSessions("uid-bob");
        org.assertj.core.api.Assertions.assertThat(auditEntries(1).get(0).action()).isEqualTo("REACTIVATE_USER");
    }

    @Test
    void theAdministratorCannotBeSuspended_notEvenBySelf() throws Exception {
        mockMvc.perform(patch("/api/admin/users/uid-admin/status").header(HttpHeaders.AUTHORIZATION, as("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_PROTECTED"));
        verify(users, never()).save(any());
        verify(identity, never()).setDisabled(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void suspend_unknownUser_is404_andInvalidBodies_are400() throws Exception {
        when(users.findByUid("uid-ghost")).thenReturn(Optional.empty());
        mockMvc.perform(patch("/api/admin/users/uid-ghost/status").header(HttpHeaders.AUTHORIZATION, as("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isNotFound());
        for (String body : new String[] {"{\"status\":\"BANNED\"}", "{}", "{\"status\":\"ACTIVE\",\"reason\":\"" + "r".repeat(201) + "\"}"}) {
            mockMvc.perform(patch("/api/admin/users/uid-alice/status").header(HttpHeaders.AUTHORIZATION, as("admin"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(identity, never()).setDisabled(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ---------------------------------------------------------------- emergencies

    private static EmergencyAlert alert(String id, String uid, Instant at, AlertLocation location) {
        return TestAlerts.alert(id, uid, AlertStatus.ACTIVE, at, location);
    }

    @Test
    void alertList_hidesCoordinates() throws Exception {
        when(alerts.findRecent(1000)).thenReturn(List.of(
                alert("a1", "uid-bob", Instant.now(), new AlertLocation(13.0827, 80.2707, 20.0, Instant.now()))));
        mockMvc.perform(get("/api/admin/alerts").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].hasLocation").value(true))
                .andExpect(jsonPath("$.items[0].location").doesNotExist())
                .andExpect(jsonPath("$.items[0].latitude").doesNotExist())
                .andExpect(jsonPath("$.items[0].notification.state").value("QUEUED"));
        verify(auditLogs, never()).append(any());
    }

    @Test
    void alertDetail_revealsCoordinates_afterWritingTheAuditEntry() throws Exception {
        when(alerts.findById("a1")).thenReturn(Optional.of(
                alert("a1", "uid-bob", Instant.now(), new AlertLocation(13.0827, 80.2707, 20.0, Instant.now()))));
        mockMvc.perform(get("/api/admin/alerts/a1").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location.latitude").value(13.0827));
        inOrder(alerts, auditLogs).verify(alerts).findById("a1");
        AuditLogEntry entry = auditEntries(1).get(0);
        org.assertj.core.api.Assertions.assertThat(entry.action()).isEqualTo("VIEW_ALERT");
        org.assertj.core.api.Assertions.assertThat(entry.details()).containsEntry("locationDisclosed", "true");
        org.assertj.core.api.Assertions.assertThat(entry.details().toString()).doesNotContain("13.0827");
    }

    @Test
    void alertDetail_failsClosed_whenTheAuditEntryCannotBeWritten() throws Exception {
        when(alerts.findById("a1")).thenReturn(Optional.of(
                alert("a1", "uid-bob", Instant.now(), new AlertLocation(13.0827, 80.2707, 20.0, Instant.now()))));
        org.mockito.Mockito.doThrow(new DataStoreException("audit down", new RuntimeException()))
                .when(auditLogs).append(any());
        mockMvc.perform(get("/api/admin/alerts/a1").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.location").doesNotExist());
    }

    @Test
    void incidentReports_canBeListedAndResolvedOnce_withAuditing() throws Exception {
        IncidentReport open = new IncidentReport("r1", "uid-bob", "HARASSMENT", "Followed me home", null,
                ReportStatus.OPEN, CREATED, null, null, null);
        when(reports.findRecent(50)).thenReturn(List.of(open));
        when(reports.findById("r1")).thenReturn(Optional.of(open));
        when(reports.save(any())).thenAnswer(call -> call.getArgument(0));

        mockMvc.perform(get("/api/admin/incident-reports").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
        mockMvc.perform(patch("/api/admin/incident-reports/r1/resolve").header(HttpHeaders.AUTHORIZATION, as("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Contacted the user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").value("Contacted the user"));
        org.assertj.core.api.Assertions.assertThat(auditEntries(1).get(0).action()).isEqualTo("RESOLVE_REPORT");

        when(reports.findById("r1")).thenReturn(Optional.of(open.resolved("uid-admin", "x", Instant.now())));
        mockMvc.perform(patch("/api/admin/incident-reports/r1/resolve").header(HttpHeaders.AUTHORIZATION, as("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void auditLogs_areListedForTheAdmin_withOutcome() throws Exception {
        when(auditLogs.findRecent(500)).thenReturn(List.of(
                new AuditLogEntry("l1", "ADMIN_ACTION", "SUSPEND_USER", "uid-admin", "uid-bob", Map.of("reason", "x"), Instant.now())));
        mockMvc.perform(get("/api/admin/audit-logs").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("SUSPEND_USER"))
                .andExpect(jsonPath("$.items[0].outcome").value("SUCCESS"));
        verify(auditLogs).findRecent(eq(500));
    }
}
