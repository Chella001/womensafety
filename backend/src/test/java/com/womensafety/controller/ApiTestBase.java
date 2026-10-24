package com.womensafety.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.womensafety.model.AccountStatus;
import com.womensafety.model.Role;
import com.womensafety.model.UserProfile;
import com.womensafety.repository.AdminDeviceRepository;
import com.womensafety.repository.AlertEventRepository;
import com.womensafety.repository.AuditLogRepository;
import com.womensafety.repository.DeliveryRepository;
import com.womensafety.repository.EmergencyAlertRepository;
import com.womensafety.repository.EmergencyContactRepository;
import com.womensafety.repository.IncidentReportRepository;
import com.womensafety.repository.NotificationJobRepository;
import com.womensafety.repository.UserRepository;
import com.womensafety.service.IdentityAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Runs the real security filter chain, controllers, validation and error handling. Only the external boundaries are
 * mocked: Firebase Authentication (ID-token verification and administration) and the Firestore repositories.
 * No network access and no real Firebase project are involved.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class ApiTestBase {

    static final String ALICE_PHONE = "+919876543210";
    static final String BOB_PHONE = "+919123456789";
    static final String ADMIN_PHONE = "+447911123456";
    static final Instant CREATED = Instant.parse("2025-01-01T10:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    FirebaseAuth firebaseAuth;

    @MockitoBean
    IdentityAdminService identity;

    @MockitoBean
    UserRepository users;

    @MockitoBean
    EmergencyContactRepository contacts;

    @MockitoBean
    EmergencyAlertRepository alerts;

    @MockitoBean
    IncidentReportRepository reports;

    @MockitoBean
    AuditLogRepository auditLogs;

    @MockitoBean
    DeliveryRepository deliveries;

    @MockitoBean
    AlertEventRepository alertEvents;

    @MockitoBean
    NotificationJobRepository jobs;

    @MockitoBean
    AdminDeviceRepository adminDevices;

    /** alice and bob are ordinary users; admin's uid ("uid-admin") is the allow-listed administrator. */
    @BeforeEach
    void signedInUsers() throws Exception {
        signIn("alice", "uid-alice", ALICE_PHONE, "phone");
        signIn("bob", "uid-bob", BOB_PHONE, "phone");
        signIn("admin", "uid-admin", ADMIN_PHONE, "phone");
        register("uid-alice", "Alice Rao", ALICE_PHONE, AccountStatus.ACTIVE);
        register("uid-bob", "Bob Nair", BOB_PHONE, AccountStatus.ACTIVE);
        register("uid-admin", "Site Owner", ADMIN_PHONE, AccountStatus.ACTIVE);
        // A mock has no transaction: emulate modify() with the findById/save stubs each test already sets up.
        when(alerts.modify(org.mockito.ArgumentMatchers.anyString(), any())).thenAnswer(call -> {
            java.util.function.UnaryOperator<com.womensafety.model.EmergencyAlert> mutator = call.getArgument(1);
            var current = alerts.findById(call.getArgument(0))
                    .orElseThrow(() -> new com.womensafety.exception.ResourceNotFoundException("That alert does not exist."));
            return alerts.save(mutator.apply(current));
        });
        when(alerts.createIfAbsent(any(), any())).thenReturn(true);
    }

    /** Makes {@code Bearer token-<name>} verify as a Firebase user signed in with the given provider. */
    void signIn(String name, String uid, String phone, String provider) throws Exception {
        FirebaseToken token = mock(FirebaseToken.class);
        Map<String, Object> claims = new HashMap<>();
        if (phone != null) {
            claims.put("phone_number", phone);
        }
        if (provider != null) {
            claims.put("firebase", Map.of("sign_in_provider", provider));
        }
        when(token.getUid()).thenReturn(uid);
        when(token.getClaims()).thenReturn(claims);
        when(firebaseAuth.verifyIdToken("token-" + name, true)).thenReturn(token);
    }

    void rejectToken(String name) throws Exception {
        when(firebaseAuth.verifyIdToken("token-" + name, true)).thenThrow(mock(FirebaseAuthException.class));
    }

    UserProfile register(String uid, String name, String phone, AccountStatus status) {
        UserProfile profile = new UserProfile(uid, name, phone, true, Role.USER, status, CREATED, CREATED, CREATED,
                "2026-10-v1", CREATED);
        when(users.findByUid(uid)).thenReturn(java.util.Optional.of(profile));
        when(users.save(any(UserProfile.class))).thenAnswer(call -> call.getArgument(0));
        return profile;
    }

    static String as(String name) {
        return "Bearer token-" + name;
    }
}
