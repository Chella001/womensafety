package com.womensafety.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/** With no administrator configured (the default), nobody can reach admin APIs, whatever their uid is. */
@TestPropertySource(properties = "app.admin.uids=")
class AdminDisabledApiTest extends ApiTestBase {

    @Test
    void whenNoAdminIsConfigured_everyoneIsForbidden() throws Exception {
        for (String user : new String[] {"alice", "bob", "admin"}) {
            mockMvc.perform(get("/api/admin/dashboard").header(HttpHeaders.AUTHORIZATION, as(user)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(jsonPath("$.role").value("USER"));
    }
}
