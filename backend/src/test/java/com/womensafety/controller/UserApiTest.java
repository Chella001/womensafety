package com.womensafety.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import com.womensafety.model.AccountStatus;
import com.womensafety.model.Role;
import com.womensafety.model.UserProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class UserApiTest extends ApiTestBase {

    @Test
    void me_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void me_returnsOnlyTheCallersOwnProfile() throws Exception {
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("uid-alice"))
                .andExpect(jsonPath("$.fullName").value("Alice Rao"));
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, as("bob")))
                .andExpect(jsonPath("$.uid").value("uid-bob"))
                .andExpect(jsonPath("$.phoneNumber").value(BOB_PHONE));
        verify(users, org.mockito.Mockito.atLeastOnce()).findByUid("uid-alice");
        verify(users, org.mockito.Mockito.atLeastOnce()).findByUid("uid-bob");
    }

    @Test
    void me_neverTrustsARoleWrittenIntoTheFirestoreDocument() throws Exception {
        // Simulates someone editing their own document: stored role says ADMIN, but the allow-list does not.
        UserProfile tampered = new UserProfile("uid-alice", "Alice Rao", ALICE_PHONE, true, Role.ADMIN,
                AccountStatus.ACTIVE, CREATED, CREATED, CREATED, "2026-10-v1", CREATED);
        when(users.findByUid("uid-alice")).thenReturn(Optional.of(tampered));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(jsonPath("$.role").value("USER"));
        mockMvc.perform(get("/api/admin/dashboard").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isForbidden());
    }

    @Test
    void patch_updatesOnlyTheName() throws Exception {
        mockMvc.perform(patch("/api/users/me").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"  Alice   B Rao \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alice B Rao"));
        ArgumentCaptor<UserProfile> saved = ArgumentCaptor.forClass(UserProfile.class);
        verify(users).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().uid()).isEqualTo("uid-alice");
        org.assertj.core.api.Assertions.assertThat(saved.getValue().phoneNumber()).isEqualTo(ALICE_PHONE);
    }

    @Test
    void patch_cannotPromoteOrReactivateOrChangeThePhone() throws Exception {
        mockMvc.perform(patch("/api/users/me").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Alice Rao\",\"role\":\"ADMIN\",\"accountStatus\":\"SUSPENDED\","
                                + "\"phoneNumber\":\"+14155550100\",\"uid\":\"uid-bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.uid").value("uid-alice"))
                .andExpect(jsonPath("$.phoneNumber").value(ALICE_PHONE))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"));
        ArgumentCaptor<UserProfile> saved = ArgumentCaptor.forClass(UserProfile.class);
        verify(users).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().role()).isEqualTo(Role.USER);
        org.assertj.core.api.Assertions.assertThat(saved.getValue().accountStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void patch_rejectsInvalidNames() throws Exception {
        for (String name : new String[] {"", " ", "A", "x".repeat(61), "12345", "<b>hi</b>"}) {
            mockMvc.perform(patch("/api/users/me").header(HttpHeaders.AUTHORIZATION, as("alice"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"" + name + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verify(users, never()).save(any());
    }

    @Test
    void sosDrill_recordsOnlyAProfileFlag_andNeverCreatesAnAlertOrNotification() throws Exception {
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(jsonPath("$.sosDrillCompletedAt").doesNotExist());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/users/me/sos-drill")
                        .header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("uid-alice"))
                .andExpect(jsonPath("$.sosDrillCompletedAt").isNotEmpty());

        ArgumentCaptor<UserProfile> saved = ArgumentCaptor.forClass(UserProfile.class);
        verify(users).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().uid()).isEqualTo("uid-alice");
        org.assertj.core.api.Assertions.assertThat(saved.getValue().sosDrillCompletedAt()).isNotNull();
        // the drill touches nothing but the caller's own profile
        org.mockito.Mockito.verifyNoInteractions(alerts, deliveries, jobs, contacts, adminDevices);
    }

    @Test
    void sosDrill_requiresAuthentication_andAPhoneToken() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/users/me/sos-drill"))
                .andExpect(status().isUnauthorized());
        signIn("mallory", "uid-mallory", null, "password");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/users/me/sos-drill")
                        .header(HttpHeaders.AUTHORIZATION, as("mallory")))
                .andExpect(status().isForbidden());
        verify(users, never()).save(any());
    }

    @Test
    void suspendedUser_isRefusedOnEveryEndpoint() throws Exception {
        register("uid-alice", "Alice Rao", ALICE_PHONE, AccountStatus.SUSPENDED);
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
        mockMvc.perform(get("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
        mockMvc.perform(get("/api/emergency-alerts/me").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/emergency-alerts")
                        .header(HttpHeaders.AUTHORIZATION, as("alice")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}
