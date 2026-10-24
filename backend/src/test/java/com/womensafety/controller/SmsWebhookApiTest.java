package com.womensafety.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;

import com.womensafety.model.Delivery;
import com.womensafety.model.DeliveryStatus;
import com.womensafety.notification.TwilioSignature;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Delivery receipts: only a correctly signed request can change a delivery. REAL mode with test credentials (nothing is sent). */
@TestPropertySource(properties = {
        "app.notifications.mode=REAL",
        "app.notifications.public-base-url=https://api.example.com",
        "app.notifications.twilio.account-sid=ACtest",
        "app.notifications.twilio.auth-token=webhook-secret",
        "app.notifications.twilio.from=+15005550006",
})
class SmsWebhookApiTest extends ApiTestBase {

    static final String URL = "https://api.example.com/api/webhooks/sms/twilio?alertId=a1&deliveryId=c-1";

    private Delivery submitted() {
        var now = java.time.Instant.now();
        return new Delivery("c-1", Delivery.RecipientType.CONTACT, "1", "+91•••001", Delivery.Channel.SMS, DeliveryStatus.SUBMITTED,
                Delivery.Mode.REAL, 1, "SM123", now, null, null, null, java.util.List.of(), now, now);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder callback(String signature, Map<String, String> form) {
        var request = post("/api/webhooks/sms/twilio?alertId=a1&deliveryId=c-1").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        form.forEach(request::param);
        if (signature != null) {
            request.header("X-Twilio-Signature", signature);
        }
        return request;
    }

    @Test
    void aCorrectlySignedReceipt_marksTheDeliveryDelivered_withoutAnyFirebaseToken() throws Exception {
        when(deliveries.findById("a1", "c-1")).thenReturn(Optional.of(submitted()));
        when(deliveries.save(eq("a1"), any())).thenAnswer(call -> call.getArgument(1));
        when(alerts.findById("a1")).thenReturn(Optional.of(TestAlerts.alert("a1", "uid-alice", com.womensafety.model.AlertStatus.ACTIVE, java.time.Instant.now(), null)));
        when(alerts.modify(any(), any())).thenAnswer(call -> {
            java.util.function.UnaryOperator<com.womensafety.model.EmergencyAlert> m = call.getArgument(1);
            return m.apply(alerts.findById("a1").orElseThrow());
        });
        Map<String, String> form = Map.of("MessageSid", "SM123", "MessageStatus", "delivered");

        mockMvc.perform(callback(TwilioSignature.sign("webhook-secret", URL, form), form)).andExpect(status().isNoContent());

        var saved = org.mockito.ArgumentCaptor.forClass(Delivery.class);
        verify(deliveries).save(eq("a1"), saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    void forgedReceipts_areRefused_beforeAnythingIsRead() throws Exception {
        Map<String, String> form = Map.of("MessageSid", "SM123", "MessageStatus", "delivered");
        String good = TwilioSignature.sign("webhook-secret", URL, form);

        mockMvc.perform(callback(null, form)).andExpect(status().isForbidden());
        mockMvc.perform(callback("", form)).andExpect(status().isForbidden());
        mockMvc.perform(callback("not-a-signature", form)).andExpect(status().isForbidden());
        mockMvc.perform(callback(TwilioSignature.sign("wrong-token", URL, form), form)).andExpect(status().isForbidden());
        // a valid signature for different parameters does not authorise these ones
        mockMvc.perform(callback(good, Map.of("MessageSid", "SM123", "MessageStatus", "undelivered"))).andExpect(status().isForbidden());
        // path tricks
        mockMvc.perform(post("/api/webhooks/sms/twilio?alertId=../x&deliveryId=c-1").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Twilio-Signature", good).param("MessageSid", "SM123")).andExpect(status().isForbidden());

        verify(deliveries, never()).findById(any(), any());
        verify(deliveries, never()).save(any(), any());
    }

    @Test
    void onlyPostIsPublic_andOtherApiPathsStillNeedAFirebaseToken() throws Exception {
        mockMvc.perform(get("/api/webhooks/sms/twilio")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/sos/me")).andExpect(status().isUnauthorized());
    }
}
