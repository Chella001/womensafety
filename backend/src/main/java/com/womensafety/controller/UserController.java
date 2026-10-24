package com.womensafety.controller;

import com.womensafety.dto.UpdateProfileRequest;
import com.womensafety.dto.UserProfileResponse;
import com.womensafety.security.FirebaseUserPrincipal;
import com.womensafety.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** The signed-in user's own profile. */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(@AuthenticationPrincipal FirebaseUserPrincipal principal) {
        return respond(userService.getProfile(principal.uid()), principal);
    }

    /** Changes the display name. Role, status and phone number cannot be changed through this endpoint. */
    @PatchMapping("/me")
    public ResponseEntity<UserProfileResponse> update(
            @AuthenticationPrincipal FirebaseUserPrincipal principal, @Valid @RequestBody UpdateProfileRequest request) {
        return respond(userService.updateFullName(principal.uid(), request.fullName()), principal);
    }

    /**
     * Marks an SOS practice drill as finished (server time). Creates no alert and sends nothing; it only lets the
     * dashboard's onboarding checklist tick off the drill step.
     */
    @PostMapping("/me/sos-drill")
    public ResponseEntity<UserProfileResponse> sosDrill(@AuthenticationPrincipal FirebaseUserPrincipal principal) {
        return respond(userService.recordSosDrill(principal.uid()), principal);
    }

    private static ResponseEntity<UserProfileResponse> respond(
            com.womensafety.model.UserProfile profile, FirebaseUserPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(UserProfileResponse.from(profile, principal.admin()));
    }
}
