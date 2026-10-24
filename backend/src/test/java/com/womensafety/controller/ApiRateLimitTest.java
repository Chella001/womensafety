package com.womensafety.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"app.rate-limit.enabled=true", "app.rate-limit.auth-per-minute=3"})
class ApiRateLimitTest extends ApiTestBase {

    @Test
    void authEndpoints_areLimitedPerClient_with429AndRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, as("alice")))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/auth/verify").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void otherApiPaths_haveTheirOwnBudget() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, as("alice")))
                    .andExpect(status().isOk());
        }
    }
}
