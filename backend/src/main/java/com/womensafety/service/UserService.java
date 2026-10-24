package com.womensafety.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.womensafety.config.AdminProperties;
import com.womensafety.config.PolicyProperties;
import com.womensafety.dto.RegisterRequest;
import com.womensafety.exception.ConflictException;
import com.womensafety.exception.InvalidRequestException;
import com.womensafety.exception.ResourceNotFoundException;
import com.womensafety.model.AccountStatus;
import com.womensafety.model.Role;
import com.womensafety.model.UserProfile;
import com.womensafety.repository.UserRepository;
import com.womensafety.security.FirebaseUserPrincipal;
import com.womensafety.validation.PersonNames;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final IdentityAdminService identity;
    private final AdminProperties adminProperties;
    private final PolicyProperties policy;
    private final Clock clock;

    public UserService(UserRepository userRepository, IdentityAdminService identity, AdminProperties adminProperties,
                       PolicyProperties policy, Clock clock) {
        this.userRepository = userRepository;
        this.identity = identity;
        this.adminProperties = adminProperties;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Creates the profile for the caller. The uid and the phone number come from the VERIFIED token, never from
     * the request, and the stored role is always USER whatever the client sent.
     */
    public UserProfile register(FirebaseUserPrincipal principal, RegisterRequest request) {
        if (!policy.consentVersion().equals(request.consentVersion())) {
            throw new InvalidRequestException("consentVersion", "The Terms and Privacy Policy have changed. Reload the page and accept them again.");
        }
        Instant now = currentTime();
        UserProfile profile = new UserProfile(
                principal.uid(),
                PersonNames.normalise(request.fullName()),
                principal.phoneNumber(),
                true,
                Role.USER,
                AccountStatus.ACTIVE,
                now, now, now,
                policy.consentVersion(),
                now);
        if (!userRepository.createIfAbsent(profile)) {
            throw new ConflictException("ACCOUNT_EXISTS", "An account already exists for this phone number. Please log in instead.");
        }
        return profile;
    }

    /** Called after a successful Firebase sign-in: records the login for an already registered user. */
    public UserProfile recordLogin(String uid) {
        UserProfile profile = requireProfile(uid);
        return userRepository.save(profile.withLastLoginAt(currentTime()));
    }

    public UserProfile getProfile(String uid) {
        return requireProfile(uid);
    }

    public UserProfile updateFullName(String uid, String fullName) {
        UserProfile profile = requireProfile(uid);
        return userRepository.save(profile.withFullName(PersonNames.normalise(fullName), currentTime()));
    }

    /**
     * Signs the user out everywhere by revoking all of their Firebase refresh tokens. Their current ID tokens are
     * rejected immediately because the backend verifies tokens with the revocation check enabled.
     */
    public void signOutEverywhere(String uid) {
        identity.revokeSessions(uid);
    }

    public boolean isAdministrator(String uid) {
        return adminProperties.isAdmin(uid);
    }

    private UserProfile requireProfile(String uid) {
        return userRepository.findByUid(uid).orElseThrow(() -> new ResourceNotFoundException(
                "NOT_REGISTERED", "No SafeHer account exists for this phone number yet. Please register first."));
    }

    private Instant currentTime() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
