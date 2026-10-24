package com.womensafety.controller;

import java.util.List;

import com.womensafety.config.NotificationProperties;
import com.womensafety.config.PolicyProperties;
import com.womensafety.dto.AlertResponse;
import com.womensafety.dto.CreateAlertRequest;
import com.womensafety.exception.ResourceNotFoundException;
import com.womensafety.security.FirebaseUserPrincipal;
import com.womensafety.service.EmergencyAlertService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SOS alerts of the signed-in user. The owner is always taken from the verified Firebase token, never from the request.
 * {@code /api/sos} is the primary path; {@code /api/emergency-alerts} is kept as an alias for existing clients.
 */
@RestController
@RequestMapping({"/api/sos", "/api/emergency-alerts"})
public class EmergencyAlertController {

    private static final String ID_PATTERN = "^[A-Za-z0-9-]{1,64}$";

    private final EmergencyAlertService service;
    private final NotificationProperties notifications;
    private final PolicyProperties policy;

    public EmergencyAlertController(EmergencyAlertService service, NotificationProperties notifications, PolicyProperties policy) {
        this.service = service;
        this.notifications = notifications;
        this.policy = policy;
    }

    /**
     * What the SOS screen needs to describe itself truthfully. In TEST mode messages are simulated and nothing is sent.
     *
     * @param note a plain-language statement of what SafeHer does and does not do
     */
    public record SosInfo(String mode, boolean testMode, int maxContacts, String note) {
    }

    @GetMapping("/info")
    public ResponseEntity<SosInfo> info() {
        boolean test = !notifications.realMode();
        String note = test
                ? "TEST MODE: SOS messages are only simulated, nothing is sent to anyone. SafeHer does not contact the police or any emergency service."
                : "SafeHer messages your verified emergency contacts and notifies the SafeHer safety team. It does not contact the police or any emergency service and cannot guarantee that anyone sees a message in time.";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SosInfo(notifications.mode().name(), test, policy.maxEmergencyContacts(), note));
    }

    /** 201 when a new alert was created, 200 (with {@code Idempotent-Replayed: true}) when the same key was used before. */
    @PostMapping
    public ResponseEntity<AlertResponse> create(
            @AuthenticationPrincipal FirebaseUserPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) CreateAlertRequest request) {
        EmergencyAlertService.Created result = service.create(principal.uid(), idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore());
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(AlertResponse.from(result.alert()));
    }

    @GetMapping("/me")
    public ResponseEntity<List<AlertResponse>> mine(@AuthenticationPrincipal FirebaseUserPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.listOwn(principal.uid()).stream().map(AlertResponse::from).toList());
    }

    @GetMapping({"/{alertId}", "/{alertId}/me"})
    public ResponseEntity<AlertResponse> get(
            @AuthenticationPrincipal FirebaseUserPrincipal principal, @PathVariable String alertId) {
        requireId(alertId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(AlertResponse.from(service.getOwned(principal.uid(), alertId)));
    }

    /** Records the cancellation request. It does not recall messages that were already sent. */
    @PostMapping("/{alertId}/cancel")
    public ResponseEntity<AlertResponse> cancel(
            @AuthenticationPrincipal FirebaseUserPrincipal principal, @PathVariable String alertId) {
        requireId(alertId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(AlertResponse.from(service.cancel(principal.uid(), alertId)));
    }

    private static void requireId(String id) {
        if (!id.matches(ID_PATTERN)) {
            throw new ResourceNotFoundException("That alert does not exist.");
        }
    }
}
