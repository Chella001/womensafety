package com.womensafety.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import com.womensafety.exception.DataStoreException;
import com.womensafety.model.AccountStatus;
import com.womensafety.model.Role;
import com.womensafety.model.UserProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class AuthApiTest extends ApiTestBase {

    private static final String VALID_BODY =
            "{\"fullName\":\"  Priya   Sharma \",\"acceptedTerms\":true,\"consentVersion\":\"2026-10-v1\"}";

    // ---------------------------------------------------------------- authentication of the token itself

    @Test
    void verify_withoutToken_is401() throws Exception {
        mockMvc.perform(post("/api/auth/verify"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void verify_withInvalidOrExpiredToken_is401() throws Exception {
        rejectToken("forged");
        mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, as("forged")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void verify_withNonBearerScheme_is401() throws Exception {
        mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, "Basic YWJjOmRlZg=="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verify_withEmailSignInToken_isRefused_phoneOnly() throws Exception {
        signIn("emailer", "uid-email", null, "password");
        mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, as("emailer")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PHONE_AUTH_REQUIRED"));
    }

    @Test
    void verify_withCustomOrAnonymousToken_isRefused() throws Exception {
        signIn("custom", "uid-custom", "+919876500000", "custom");
        signIn("anon", "uid-anon", null, "anonymous");
        mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, as("custom")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PHONE_AUTH_REQUIRED"));
        mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, as("anon")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PHONE_AUTH_REQUIRED"));
    }

    @Test
    void verify_withPhoneTokenLackingAValidNumber_isRefused() throws Exception {
        signIn("nonumber", "uid-x", "not-a-number", "phone");
        mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, as("nonumber")))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- verify (login)

    @Test
    void verify_registeredUser_returnsProfileAndRecordsLogin() throws Exception {
        mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.uid").value("uid-alice"))
                .andExpect(jsonPath("$.fullName").value("Alice Rao"))
                .andExpect(jsonPath("$.phoneNumber").value(ALICE_PHONE))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"));

        ArgumentCaptor<UserProfile> saved = ArgumentCaptor.forClass(UserProfile.class);
        verify(users).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().lastLoginAt()).isAfter(CREATED);
        org.assertj.core.api.Assertions.assertThat(saved.getValue().createdAt()).isEqualTo(CREATED);
    }

    @Test
    void verify_unregisteredNumber_is404NotRegistered() throws Exception {
        signIn("newbie", "uid-newbie", "+919000000001", "phone");
        when(users.findByUid("uid-newbie")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, as("newbie")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_REGISTERED"));
        verify(users, never()).save(any());
    }

    @Test
    void verify_suspendedUser_is403() throws Exception {
        register("uid-alice", "Alice Rao", ALICE_PHONE, AccountStatus.SUSPENDED);
        mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test
    void verify_whenFirestoreIsDown_is503WithoutDetails() throws Exception {
        when(users.findByUid("uid-alice")).thenThrow(new DataStoreException("boom", new RuntimeException()));
        mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATA_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(not(containsString("boom"))));
    }

    // ---------------------------------------------------------------- registration

    private void asNewUser() throws Exception {
        signIn("newbie", "uid-newbie", "+919000000001", "phone");
        when(users.findByUid("uid-newbie")).thenReturn(Optional.empty());
        when(users.createIfAbsent(any(UserProfile.class))).thenReturn(true);
    }

    @Test
    void register_createsAProfileFromTheVerifiedToken() throws Exception {
        asNewUser();
        mockMvc.perform(post("/api/auth/register").header(HttpHeaders.AUTHORIZATION, as("newbie"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").value("uid-newbie"))
                .andExpect(jsonPath("$.fullName").value("Priya Sharma"))
                .andExpect(jsonPath("$.phoneNumber").value("+919000000001"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"));

        ArgumentCaptor<UserProfile> created = ArgumentCaptor.forClass(UserProfile.class);
        verify(users).createIfAbsent(created.capture());
        UserProfile p = created.getValue();
        org.assertj.core.api.Assertions.assertThat(p.uid()).isEqualTo("uid-newbie");
        org.assertj.core.api.Assertions.assertThat(p.phoneVerified()).isTrue();
        org.assertj.core.api.Assertions.assertThat(p.role()).isEqualTo(Role.USER);
        org.assertj.core.api.Assertions.assertThat(p.consentVersion()).isEqualTo("2026-10-v1");
        org.assertj.core.api.Assertions.assertThat(p.consentTimestamp()).isNotNull();
    }

    @Test
    void register_ignoresClientSuppliedRoleUidPhoneAndStatus() throws Exception {
        asNewUser();
        String hostile = "{\"fullName\":\"Mallory\",\"acceptedTerms\":true,\"consentVersion\":\"2026-10-v1\","
                + "\"role\":\"ADMIN\",\"uid\":\"uid-admin\",\"phoneNumber\":\"+14155550100\","
                + "\"accountStatus\":\"ACTIVE\",\"phoneVerified\":false}";

        mockMvc.perform(post("/api/auth/register").header(HttpHeaders.AUTHORIZATION, as("newbie"))
                        .contentType(MediaType.APPLICATION_JSON).content(hostile))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.uid").value("uid-newbie"))
                .andExpect(jsonPath("$.phoneNumber").value("+919000000001"));

        ArgumentCaptor<UserProfile> created = ArgumentCaptor.forClass(UserProfile.class);
        verify(users).createIfAbsent(created.capture());
        org.assertj.core.api.Assertions.assertThat(created.getValue().role()).isEqualTo(Role.USER);
        org.assertj.core.api.Assertions.assertThat(created.getValue().uid()).isEqualTo("uid-newbie");
        org.assertj.core.api.Assertions.assertThat(created.getValue().phoneNumber()).isEqualTo("+919000000001");
    }

    @Test
    void register_storesRoleUser_evenForTheConfiguredAdministrator() throws Exception {
        signIn("owner", "uid-admin", ADMIN_PHONE, "phone");
        when(users.findByUid("uid-admin")).thenReturn(Optional.empty());
        when(users.createIfAbsent(any(UserProfile.class))).thenReturn(true);

        mockMvc.perform(post("/api/auth/register").header(HttpHeaders.AUTHORIZATION, as("owner"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN")); // effective role comes from the allow-list

        ArgumentCaptor<UserProfile> created = ArgumentCaptor.forClass(UserProfile.class);
        verify(users).createIfAbsent(created.capture());
        org.assertj.core.api.Assertions.assertThat(created.getValue().role()).isEqualTo(Role.USER);
    }

    @Test
    void register_rejectsInvalidInput() throws Exception {
        asNewUser();
        String[] bad = {
                "{\"fullName\":\"\",\"acceptedTerms\":true,\"consentVersion\":\"2026-10-v1\"}",
                "{\"fullName\":\"     \",\"acceptedTerms\":true,\"consentVersion\":\"2026-10-v1\"}",
                "{\"fullName\":\"A\",\"acceptedTerms\":true,\"consentVersion\":\"2026-10-v1\"}",
                "{\"fullName\":\" A \",\"acceptedTerms\":true,\"consentVersion\":\"2026-10-v1\"}",
                "{\"fullName\":\"" + "x".repeat(61) + "\",\"acceptedTerms\":true,\"consentVersion\":\"2026-10-v1\"}",
                "{\"fullName\":\"12345\",\"acceptedTerms\":true,\"consentVersion\":\"2026-10-v1\"}",
                "{\"fullName\":\"<script>alert(1)</script>\",\"acceptedTerms\":true,\"consentVersion\":\"2026-10-v1\"}",
                "{\"acceptedTerms\":true,\"consentVersion\":\"2026-10-v1\"}",
                "{\"fullName\":\"Priya\",\"acceptedTerms\":false,\"consentVersion\":\"2026-10-v1\"}",
                "{\"fullName\":\"Priya\",\"consentVersion\":\"2026-10-v1\"}",
                "{\"fullName\":\"Priya\",\"acceptedTerms\":true}",
        };
        for (String body : bad) {
            mockMvc.perform(post("/api/auth/register").header(HttpHeaders.AUTHORIZATION, as("newbie"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verify(users, never()).createIfAbsent(any());
    }

    @Test
    void register_withOutdatedConsentVersion_isRejected() throws Exception {
        asNewUser();
        mockMvc.perform(post("/api/auth/register").header(HttpHeaders.AUTHORIZATION, as("newbie"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Priya\",\"acceptedTerms\":true,\"consentVersion\":\"1999-old\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.consentVersion").exists());
        verify(users, never()).createIfAbsent(any());
    }

    @Test
    void register_twice_returns409AndDoesNotOverwrite() throws Exception {
        asNewUser();
        when(users.createIfAbsent(any(UserProfile.class))).thenReturn(false);

        mockMvc.perform(post("/api/auth/register").header(HttpHeaders.AUTHORIZATION, as("newbie"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_EXISTS"));
        verify(users, never()).save(any());
    }

    @Test
    void register_withMalformedJson_is400() throws Exception {
        asNewUser();
        mockMvc.perform(post("/api/auth/register").header(HttpHeaders.AUTHORIZATION, as("newbie"))
                        .contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_withoutToken_is401() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- everything else needs a registered profile

    @Test
    void protectedEndpoint_forUnregisteredUser_is403RegistrationRequired() throws Exception {
        signIn("newbie", "uid-newbie", "+919000000001", "phone");
        when(users.findByUid("uid-newbie")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("newbie")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGISTRATION_REQUIRED"));
    }

    // ---------------------------------------------------------------- logout

    @Test
    void logoutAll_revokesTheCallersRefreshTokens() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isNoContent());
        verify(identity).revokeSessions("uid-alice");
    }

    @Test
    void logoutAll_withoutToken_is401() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all")).andExpect(status().isUnauthorized());
        verify(identity, never()).revokeSessions(any());
    }

    // ---------------------------------------------------------------- platform rules

    @Test
    void unknownApiPath_isJson404() throws Exception {
        mockMvc.perform(get("/api/nope").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void nonApiPaths_areDenied() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void corsPreflight_fromFrontendOrigin_allowsPatchAndDelete() throws Exception {
        for (String method : new String[] {"PATCH", "DELETE", "POST"}) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/users/me")
                            .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5500"));
        }
    }

    @Test
    void corsPreflight_fromUnknownOrigin_isRejected() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/users/me")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }
}
