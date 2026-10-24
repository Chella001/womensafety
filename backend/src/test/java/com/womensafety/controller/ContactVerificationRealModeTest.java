package com.womensafety.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.womensafety.model.EmergencyContact;
import com.womensafety.notification.SmsNotificationProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** With a real (non-simulated) provider the code is texted and NEVER returned by the API. The provider is a mock here. */
class ContactVerificationRealModeTest extends ApiTestBase {

    @MockitoBean
    SmsNotificationProvider sms;

    final AtomicReference<EmergencyContact> stored = new AtomicReference<>();

    @BeforeEach
    void setUpStore() {
        when(sms.simulated()).thenReturn(false);
        when(sms.name()).thenReturn("twilio");
        when(contacts.findAll("uid-alice")).thenAnswer(call -> stored.get() == null ? List.of() : List.of(stored.get()));
        when(contacts.findById(eq("uid-alice"), any())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(contacts.save(eq("uid-alice"), any(EmergencyContact.class))).thenAnswer(call -> {
            stored.set(call.getArgument(1));
            return call.getArgument(1);
        });
    }

    @Test
    void realMode_sendsTheCodeBySms_andTheResponseNeverContainsIt() throws Exception {
        when(sms.send(any())).thenReturn(SmsNotificationProvider.SmsSendResult.accepted("SM1"));
        mockMvc.perform(post("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Meera Nair\",\"phoneNumber\":\"98765 00001\",\"notifyConsent\":true}"));
        String id = stored.get().id();

        mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testMode").value(false))
                .andExpect(jsonPath("$.testModeCode").doesNotExist())
                .andExpect(jsonPath("$.message").value(containsString("+91")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("codeHash"))));

        var message = org.mockito.ArgumentCaptor.forClass(SmsNotificationProvider.SmsMessage.class);
        org.mockito.Mockito.verify(sms).send(message.capture());
        assertThat(message.getValue().to()).isEqualTo("+919876500001");
        assertThat(message.getValue().body()).containsPattern("code is [0-9]{6}").contains("Alice");
        assertThat(message.getValue().callbackUrl()).isNull();
    }

    @Test
    void whenTheProviderRefuses_theUserGetsAnHonestError_notASuccess() throws Exception {
        when(sms.send(any())).thenReturn(SmsNotificationProvider.SmsSendResult.permanent("TWILIO_21211"));
        mockMvc.perform(post("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Meera Nair\",\"phoneNumber\":\"98765 00001\",\"notifyConsent\":true}"));
        String id = stored.get().id();

        mockMvc.perform(post("/api/emergency-contacts/" + id + "/verification").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("21211"))));
    }
}
