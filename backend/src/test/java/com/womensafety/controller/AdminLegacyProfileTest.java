package com.womensafety.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.womensafety.model.AccountStatus;
import com.womensafety.model.Role;
import com.womensafety.model.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Profiles written by older versions of the app may lack a name or number; the admin list must not crash on them. */
class AdminLegacyProfileTest extends ApiTestBase {

    @Test
    void searchAndListSurviveProfilesWithoutNameOrPhone() throws Exception {
        UserProfile legacy = new UserProfile("legacyuid", "", "", false, Role.USER, AccountStatus.ACTIVE, CREATED, null, CREATED, null, null);
        when(users.findRecent(anyInt())).thenReturn(List.of(legacy));

        mockMvc.perform(get("/api/admin/users?search=anything").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/admin/users?search=9876").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].uid").value("legacyuid"))
                .andExpect(jsonPath("$.items[0].phoneMasked").value("••••"));
    }
}
