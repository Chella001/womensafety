package com.womensafety.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.womensafety.model.EmergencyContact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Verification and consent: a contact only becomes eligible for SOS messages after BOTH steps. */
class ContactVerificationApiTest extends ApiTestBase {

    static final Instant NOW = Instant.now();
    final AtomicReference<EmergencyContact> stored = new AtomicReference<>();

    @BeforeEach
    void contactStore() {
        stored.set(null);
        when(contacts.findAll("uid-alice")).thenAnswer(call -> stored.get() == null ? List.of() : List.of(stored.get()));
        when(contacts.findById(eq("uid-alice"), any())).thenAnswer(call ->
                Optional.ofNullable(stored.get()).filter(c -> c.id().equals(call.getArgument(1))));
        when(contacts.save(eq("uid-alice"), any(EmergencyContact.class))).thenAnswer(call -> {
            stored.set(call.getArgument(1));
            return call.getArgument(1);
        });
    }

    private static String contactJson(String name, String phone, Boolean consent) {
        return "{\"name\":\"" + name + "\",\"phoneNumber\":\"" + phone + "\",\"relationship\":\"Friend\""
                + (consent == null ? "" : ",\"notifyConsent\":" + consent) + "}";
    }

    private String addContact(Boolean consent) throws Exception {
        String body = mockMvc.perform(post("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(contactJson("Meera Nair", "98765 00001", consent)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return stored.get().id() + "|" + body;
    }

    @Test
    void aNewContact_isNeitherVerifiedNorEligible_andTheResponseNeverExposesTheChallenge() throws Exception {
        mockMvc.perform(post("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(contactJson("Meera Nair", "98765 00001", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.notifyConsent").value(true))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("codeHash"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("challenge\":{"))));
    }

    @Test
    void consentDefaultsToFalse_whenNotConfirmed() throws Exception {
        mockMvc.perform(post("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(contactJson("Meera Nair", "98765 00001", null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.notifyConsent").value(false));
    }

    @Test
    void aCodeCannotBeRequested_untilTheUserConfirmsTheContactAgreed() throws Exception {
        String id = addContact(false).split("\\|")[0];
        mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.notifyConsent").value(containsString("agreed")));
    }

    @Test
    void testMode_returnsTheCodeOnlyBecauseNothingIsSent_andStoresOnlyItsHash() throws Exception {
        String id = addContact(true).split("\\|")[0];

        String body = mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testMode").value(true))
                .andExpect(jsonPath("$.testModeCode").value(matchesPattern("[0-9]{6}")))
                .andExpect(jsonPath("$.message").value(containsString("TEST MODE: no SMS was sent")))
                .andReturn().getResponse().getContentAsString();
        String code = body.replaceAll(".*\"testModeCode\":\"(\\d{6})\".*", "$1");

        EmergencyContact.Challenge challenge = stored.get().challenge();
        assertThat(challenge.codeHash()).hasSize(64).doesNotContain(code);
        assertThat(challenge.expiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(9)));
        assertThat(stored.get().verified()).isFalse();
    }

    @Test
    void theCorrectCode_verifiesTheContact_andOnlyThenIsItEligible() throws Exception {
        String id = addContact(true).split("\\|")[0];
        String body = mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andReturn().getResponse().getContentAsString();
        String code = body.replaceAll(".*\"testModeCode\":\"(\\d{6})\".*", "$1");

        mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification/confirm").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.verificationPending").value(false));
        assertThat(stored.get().challenge()).isNull(); // the code is single-use
        assertThat(stored.get().eligibleForNotifications()).isTrue();
    }

    @Test
    void aWrongCode_isRejected_andCounted_andTooManyLockTheCodeOut() throws Exception {
        String id = addContact(true).split("\\|")[0];
        String body = mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andReturn().getResponse().getContentAsString();
        String code = body.replaceAll(".*\"testModeCode\":\"(\\d{6})\".*", "$1");
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification/confirm").header(HttpHeaders.AUTHORIZATION, as("alice"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + wrong + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details.code").value(containsString("isn't correct")));
            assertThat(stored.get().challenge().failedAttempts()).isEqualTo(i);
        }
        // locked out: even the right code is refused until a new code is requested
        mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification/confirm").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.code").value(containsString("Too many wrong codes")));
        assertThat(stored.get().verified()).isFalse();
    }

    @Test
    void anExpiredCode_isRejected() throws Exception {
        String id = addContact(true).split("\\|")[0];
        stored.set(stored.get().withChallenge(new EmergencyContact.Challenge("0".repeat(64), Instant.now().minusSeconds(5), 0, 1, Instant.now()), Instant.now()));
        mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification/confirm").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.code").value(containsString("expired")));
    }

    @Test
    void aMalformedCode_isRejected_beforeAnythingIsChecked() throws Exception {
        String id = addContact(true).split("\\|")[0];
        for (String bad : new String[] {"", "12345", "1234567", "abcdef", "12 456"}) {
            mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification/confirm").header(HttpHeaders.AUTHORIZATION, as("alice"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void codesPerContactPerHour_areLimited_toProtectAgainstSmsAbuse() throws Exception {
        String id = addContact(true).split("\\|")[0];
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification").header(HttpHeaders.AUTHORIZATION, as("alice")))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void anAlreadyVerifiedContact_cannotBeVerifiedAgain() throws Exception {
        String id = addContact(true).split("\\|")[0];
        stored.set(stored.get().markVerified(Instant.now()));
        mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));
    }

    @Test
    void changingTheNumber_dropsVerificationConsentAndAnyPendingCode() throws Exception {
        String id = addContact(true).split("\\|")[0];
        stored.set(stored.get().markVerified(Instant.now()));
        assertThat(stored.get().eligibleForNotifications()).isTrue();

        mockMvc.perform(patch("/api/emergency-contacts/" + id).header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"98765 00099\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.notifyConsent").value(false))
                .andExpect(jsonPath("$.eligible").value(false));
    }

    @Test
    void changingOnlyTheName_keepsVerification_andConsentCanBeWithdrawn() throws Exception {
        String id = addContact(true).split("\\|")[0];
        stored.set(stored.get().markVerified(Instant.now()));

        mockMvc.perform(patch("/api/emergency-contacts/" + id).header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Meera N\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true)).andExpect(jsonPath("$.eligible").value(true));
        mockMvc.perform(patch("/api/emergency-contacts/" + id).header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notifyConsent\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true)).andExpect(jsonPath("$.eligible").value(false));
    }

    @Test
    void someoneElsesContact_cannotBeVerified_andRequiresAuthentication() throws Exception {
        String id = addContact(true).split("\\|")[0];
        when(contacts.findById(eq("uid-bob"), any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification").header(HttpHeaders.AUTHORIZATION, as("bob")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification/confirm").header(HttpHeaders.AUTHORIZATION, as("bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        verify(contacts, never()).save(eq("uid-bob"), any());
    }
}
