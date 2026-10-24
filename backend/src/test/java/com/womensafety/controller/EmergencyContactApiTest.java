package com.womensafety.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import com.womensafety.model.EmergencyContact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class EmergencyContactApiTest extends ApiTestBase {

    private static final Instant NOW = Instant.parse("2025-03-01T10:00:00Z");
    private static final String MUM = "{\"name\":\"  Mum \",\"phoneNumber\":\"+91 98765-00001\",\"relationship\":\" Mother \"}";

    @BeforeEach
    void saveEchoes() {
        when(contacts.save(anyString(), any(EmergencyContact.class))).thenAnswer(call -> call.getArgument(1));
        when(contacts.findAll(anyString())).thenReturn(Collections.emptyList());
    }

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/emergency-contacts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/emergency-contacts").contentType(MediaType.APPLICATION_JSON).content(MUM))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/emergency-contacts/c1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/emergency-contacts/c1")).andExpect(status().isUnauthorized());
        verify(contacts, never()).save(anyString(), any());
    }

    @Test
    void list_returnsOnlyTheCallersContacts() throws Exception {
        when(contacts.findAll("uid-alice")).thenReturn(List.of(
                new EmergencyContact("c1", "Mum", "+919876500001", "Mother", NOW, NOW)));

        mockMvc.perform(get("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].phoneNumber").value("+919876500001"));
        mockMvc.perform(get("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("bob")))
                .andExpect(jsonPath("$.length()").value(0));
        verify(contacts).findAll("uid-bob");
    }

    @Test
    void add_normalisesToE164_andStoresUnderTheCallersUid() throws Exception {
        mockMvc.perform(post("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(MUM))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Mum"))
                .andExpect(jsonPath("$.phoneNumber").value("+919876500001"))
                .andExpect(jsonPath("$.relationship").value("Mother"));
        verify(contacts).save(eq("uid-alice"), any(EmergencyContact.class));
        verify(contacts, never()).save(eq("uid-bob"), any());
    }

    @Test
    void add_readsNumbersWithoutACountryCodeInTheUsersOwnRegion() throws Exception {
        mockMvc.perform(post("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dad\",\"phoneNumber\":\"98765 00002\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phoneNumber").value("+919876500002"));
    }

    @Test
    void add_ignoresAnyUidInTheBody() throws Exception {
        mockMvc.perform(post("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mallory\",\"phoneNumber\":\"+919876500003\",\"uid\":\"uid-bob\"}"))
                .andExpect(status().isCreated());
        verify(contacts).save(eq("uid-alice"), any(EmergencyContact.class));
    }

    @Test
    void add_rejectsInvalidInput() throws Exception {
        String[] bad = {
                "{\"name\":\"\",\"phoneNumber\":\"+919876500001\"}",
                "{\"name\":\"A\",\"phoneNumber\":\"+919876500001\"}",
                "{\"name\":\"<script>x</script>\",\"phoneNumber\":\"+919876500001\"}",
                "{\"name\":\"" + "x".repeat(61) + "\",\"phoneNumber\":\"+919876500001\"}",
                "{\"name\":\"Mum\",\"phoneNumber\":\"\"}",
                "{\"name\":\"Mum\",\"phoneNumber\":\"call me\"}",
                "{\"name\":\"Mum\",\"phoneNumber\":\"12345\"}",
                "{\"name\":\"Mum\",\"phoneNumber\":\"+1234\"}",
                "{\"name\":\"Mum\",\"phoneNumber\":\"+919876500001\",\"relationship\":\"" + "r".repeat(41) + "\"}",
        };
        for (String body : bad) {
            mockMvc.perform(post("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verify(contacts, never()).save(anyString(), any());
    }

    @Test
    void add_rejectsTheUsersOwnNumber() throws Exception {
        mockMvc.perform(post("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Me\",\"phoneNumber\":\"" + ALICE_PHONE + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.phoneNumber").exists());
    }

    @Test
    void add_rejectsADuplicateNumber() throws Exception {
        when(contacts.findAll("uid-alice")).thenReturn(List.of(
                new EmergencyContact("c1", "Mum", "+919876500001", "", NOW, NOW)));
        mockMvc.perform(post("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(MUM))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CONTACT"));
        verify(contacts, never()).save(anyString(), any());
    }

    @Test
    void add_beyondTheLimit_is409() throws Exception {
        List<EmergencyContact> five = IntStream.range(0, 5)
                .mapToObj(i -> new EmergencyContact("c" + i, "Person " + i, "+91987650010" + i, "", NOW, NOW)).toList();
        when(contacts.findAll("uid-alice")).thenReturn(five);
        mockMvc.perform(post("/api/emergency-contacts").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(MUM))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTACT_LIMIT"));
    }

    @Test
    void patch_changesOnlyThePresentFields_andKeepsCreatedAt() throws Exception {
        when(contacts.findById("uid-alice", "c1")).thenReturn(Optional.of(
                new EmergencyContact("c1", "Mum", "+919876500001", "Mother", NOW, NOW)));
        mockMvc.perform(patch("/api/emergency-contacts/c1").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"relationship\":\"Mom\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mum"))
                .andExpect(jsonPath("$.phoneNumber").value("+919876500001"))
                .andExpect(jsonPath("$.relationship").value("Mom"));
        ArgumentCaptor<EmergencyContact> saved = ArgumentCaptor.forClass(EmergencyContact.class);
        verify(contacts).save(eq("uid-alice"), saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().createdAt()).isEqualTo(NOW);
        org.assertj.core.api.Assertions.assertThat(saved.getValue().updatedAt()).isAfter(NOW);
    }

    @Test
    void patch_toAnotherExistingContactsNumber_is409() throws Exception {
        when(contacts.findById("uid-alice", "c1")).thenReturn(Optional.of(
                new EmergencyContact("c1", "Mum", "+919876500001", "", NOW, NOW)));
        when(contacts.findAll("uid-alice")).thenReturn(List.of(
                new EmergencyContact("c1", "Mum", "+919876500001", "", NOW, NOW),
                new EmergencyContact("c2", "Dad", "+919876500002", "", NOW, NOW)));
        mockMvc.perform(patch("/api/emergency-contacts/c1").header(HttpHeaders.AUTHORIZATION, as("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+919876500002\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void patch_someoneElsesContact_is404() throws Exception {
        when(contacts.findById("uid-alice", "c1")).thenReturn(Optional.of(
                new EmergencyContact("c1", "Mum", "+919876500001", "", NOW, NOW)));
        // bob asks for the same id: the lookup is scoped to bob's own uid and finds nothing
        mockMvc.perform(patch("/api/emergency-contacts/c1").header(HttpHeaders.AUTHORIZATION, as("bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isNotFound());
        verify(contacts).findById("uid-bob", "c1");
        verify(contacts, never()).save(anyString(), any());
    }

    @Test
    void patch_rejectsInvalidValues() throws Exception {
        when(contacts.findById("uid-alice", "c1")).thenReturn(Optional.of(
                new EmergencyContact("c1", "Mum", "+919876500001", "", NOW, NOW)));
        for (String body : new String[] {"{\"name\":\"A\"}", "{\"phoneNumber\":\"abc\"}", "{\"phoneNumber\":\"+1234\"}"}) {
            mockMvc.perform(patch("/api/emergency-contacts/c1").header(HttpHeaders.AUTHORIZATION, as("alice"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void delete_removesOnlyFromTheCallersOwnList() throws Exception {
        when(contacts.delete("uid-alice", "c1")).thenReturn(true);
        mockMvc.perform(delete("/api/emergency-contacts/c1").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isNoContent());
        verify(contacts).delete("uid-alice", "c1");
    }

    @Test
    void delete_someoneElsesContact_is404() throws Exception {
        when(contacts.delete("uid-alice", "c1")).thenReturn(true);
        when(contacts.delete("uid-bob", "c1")).thenReturn(false);
        mockMvc.perform(delete("/api/emergency-contacts/c1").header(HttpHeaders.AUTHORIZATION, as("bob")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(contacts, never()).delete(eq("uid-alice"), anyString());
    }

    @Test
    void delete_withMalformedId_is404WithoutTouchingTheStore() throws Exception {
        mockMvc.perform(delete("/api/emergency-contacts/bad..id").header(HttpHeaders.AUTHORIZATION, as("alice")))
                .andExpect(status().isNotFound());
        verify(contacts, never()).delete(anyString(), anyString());
    }
}
