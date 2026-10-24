package com.womensafety.security;

import java.io.IOException;
import java.util.Optional;

import com.womensafety.exception.DataStoreException;
import com.womensafety.model.UserProfile;
import com.womensafety.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after authentication and enforces the account state stored in Firestore: a suspended account is refused on
 * every endpoint, and every endpoint except registration (and the two session endpoints) requires an existing profile.
 */
public class AccountStatusFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccountStatusFilter.class);

    private final UserRepository users;
    private final JsonSecurityErrorHandler errorHandler;

    public AccountStatusFilter(UserRepository users, JsonSecurityErrorHandler errorHandler) {
        this.users = users;
        this.errorHandler = errorHandler;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof FirebaseUserPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && "/api/auth/register".equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<UserProfile> profile;
        try {
            profile = users.findByUid(principal.uid());
        } catch (DataStoreException e) {
            log.error("Could not load the account state", e);
            errorHandler.write(response, HttpStatus.SERVICE_UNAVAILABLE, "DATA_STORE_UNAVAILABLE",
                    "SafeHer is temporarily unavailable. Please try again shortly.");
            return;
        }

        if (profile.isEmpty()) {
            if ("/api/auth/verify".equals(path) || "/api/auth/logout-all".equals(path)) {
                chain.doFilter(request, response); // the controller answers NOT_REGISTERED
                return;
            }
            errorHandler.write(response, HttpStatus.FORBIDDEN, "REGISTRATION_REQUIRED",
                    "Please complete registration before using SafeHer.");
            return;
        }

        if (profile.get().isSuspended()) {
            errorHandler.write(response, HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED",
                    "This account has been suspended. Please contact SafeHer support.");
            return;
        }
        chain.doFilter(request, response);
    }
}
