package com.womensafety.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** In TEST mode there is no provider, so the receipt endpoint does not exist at all. */
class SmsWebhookTestModeTest extends ApiTestBase {

    @Test
    void inTestMode_theWebhookIsNotAvailable() throws Exception {
        mockMvc.perform(post("/api/webhooks/sms/twilio?alertId=a1&deliveryId=c-1").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", "anything").param("MessageSid", "SM1").param("MessageStatus", "delivered"))
                .andExpect(status().isNotFound());
    }
}
