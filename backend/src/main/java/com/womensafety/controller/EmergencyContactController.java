package com.womensafety.controller;

import java.util.List;

import com.womensafety.dto.ConfirmVerificationRequest;
import com.womensafety.dto.EmergencyContactRequest;
import com.womensafety.dto.EmergencyContactResponse;
import com.womensafety.dto.UpdateEmergencyContactRequest;
import com.womensafety.dto.VerificationStartedResponse;
import com.womensafety.security.FirebaseUserPrincipal;
import com.womensafety.service.EmergencyContactService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's emergency contacts. The uid always comes from the verified token, never from the request. */
@RestController
@RequestMapping("/api/emergency-contacts")
public class EmergencyContactController {

    private final EmergencyContactService service;

    public EmergencyContactController(EmergencyContactService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<EmergencyContactResponse>> list(@AuthenticationPrincipal FirebaseUserPrincipal principal) {
        List<EmergencyContactResponse> body = service.list(principal.uid()).stream()
                .map(EmergencyContactResponse::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping
    public ResponseEntity<EmergencyContactResponse> add(
            @AuthenticationPrincipal FirebaseUserPrincipal principal,
            @Valid @RequestBody EmergencyContactRequest request) {
        EmergencyContactResponse body = EmergencyContactResponse.from(service.add(principal, request));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    @PatchMapping("/{contactId}")
    public ResponseEntity<EmergencyContactResponse> update(
            @AuthenticationPrincipal FirebaseUserPrincipal principal, @PathVariable String contactId,
            @Valid @RequestBody UpdateEmergencyContactRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(EmergencyContactResponse.from(service.update(principal, contactId, request)));
    }

    /** Texts a code to the contact (TEST mode: returns it instead; see {@link VerificationStartedResponse}). */
    @PostMapping("/{contactId}/verification")
    public ResponseEntity<VerificationStartedResponse> startVerification(
            @AuthenticationPrincipal FirebaseUserPrincipal principal, @PathVariable String contactId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.startVerification(principal.uid(), contactId));
    }

    @PostMapping("/{contactId}/verification/confirm")
    public ResponseEntity<EmergencyContactResponse> confirmVerification(
            @AuthenticationPrincipal FirebaseUserPrincipal principal, @PathVariable String contactId,
            @Valid @RequestBody ConfirmVerificationRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(EmergencyContactResponse.from(service.confirmVerification(principal.uid(), contactId, request.code())));
    }

    @DeleteMapping("/{contactId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal FirebaseUserPrincipal principal, @PathVariable String contactId) {
        service.delete(principal.uid(), contactId);
        return ResponseEntity.noContent().build();
    }
}
