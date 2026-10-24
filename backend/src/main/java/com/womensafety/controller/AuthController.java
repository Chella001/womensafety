package com.womensafety.controller;

import com.womensafety.dto.RegisterRequest;
import com.womensafety.dto.UserProfileResponse;
import com.womensafety.security.FirebaseUserPrincipal;
import com.womensafety.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints called right after the browser completes Firebase phone sign-in. By the time these methods run the
 * security filters have verified the ID token, so reaching them proves the phone number was verified by Firebase.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /** Confirms the token, records the login and returns the profile of an already registered user. */
    @PostMapping("/verify")
    public ResponseEntity<UserProfileResponse> verify(@AuthenticationPrincipal FirebaseUserPrincipal principal) {
        return profile(HttpStatus.OK, userService.recordLogin(principal.uid()), principal);
    }

    /** Creates the profile for a newly verified phone number. 409 ACCOUNT_EXISTS when it already has one. */
    @PostMapping("/register")
    public ResponseEntity<UserProfileResponse> register(
            @AuthenticationPrincipal FirebaseUserPrincipal principal, @Valid @RequestBody RegisterRequest request) {
        return profile(HttpStatus.CREATED, userService.register(principal, request), principal);
    }

    /**
     * Signs the user out of every device by revoking their Firebase refresh tokens. Ordinary logout is client-side:
     * a Firebase ID token cannot be invalidated individually and stays valid until it expires (about an hour).
     */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutEverywhere(@AuthenticationPrincipal FirebaseUserPrincipal principal) {
        userService.signOutEverywhere(principal.uid());
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<UserProfileResponse> profile(
            HttpStatus status, com.womensafety.model.UserProfile profile, FirebaseUserPrincipal principal) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(UserProfileResponse.from(profile, principal.admin()));
    }
}
