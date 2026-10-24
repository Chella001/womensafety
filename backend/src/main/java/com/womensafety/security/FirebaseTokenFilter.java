package com.womensafety.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.womensafety.config.AdminProperties;
import com.womensafety.validation.PhoneNumbers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from its {@code Authorization: Bearer <Firebase ID token>} header.
 *
 * <ul>
 *   <li>No header: the request stays anonymous and the security rules answer 401.</li>
 *   <li>Invalid, expired, revoked token or disabled user: 401.</li>
 *   <li>Valid token that was NOT produced by phone sign-in (for example an e-mail or custom token): 403, because
 *       SafeHer identifies users by their verified phone number only.</li>
 * </ul>
 *
 * Administrator authority comes exclusively from {@link AdminProperties}; nothing in the token or request can grant it.
 */
public class FirebaseTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseTokenFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final FirebaseAuth firebaseAuth;
    private final boolean checkRevoked;
    private final JsonSecurityErrorHandler errorHandler;
    private final PhoneNumbers phoneNumbers;
    private final AdminProperties adminProperties;

    public FirebaseTokenFilter(FirebaseAuth firebaseAuth, boolean checkRevoked, JsonSecurityErrorHandler errorHandler,
                               PhoneNumbers phoneNumbers, AdminProperties adminProperties) {
        this.firebaseAuth = firebaseAuth;
        this.checkRevoked = checkRevoked;
        this.errorHandler = errorHandler;
        this.phoneNumbers = phoneNumbers;
        this.adminProperties = adminProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            chain.doFilter(request, response);
            return;
        }

        FirebaseToken token;
        try {
            token = firebaseAuth.verifyIdToken(header.substring(BEARER_PREFIX.length()).trim(), checkRevoked);
        } catch (FirebaseAuthException | IllegalArgumentException e) {
            // The token itself is never logged.
            log.debug("Rejected Firebase ID token: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            errorHandler.commence(request, response, new BadCredentialsException("Invalid Firebase ID token", e));
            return;
        }

        String phone = phoneNumberOf(token);
        if (phone == null) {
            SecurityContextHolder.clearContext();
            errorHandler.write(response, HttpStatus.FORBIDDEN, "PHONE_AUTH_REQUIRED",
                    "SafeHer requires phone number sign-in. Please sign in with your phone number.");
            return;
        }

        boolean admin = adminProperties.isAdmin(token.getUid());
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (admin) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                new FirebaseUserPrincipal(token.getUid(), phone, admin), null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        chain.doFilter(request, response);
    }

    /** The verified E.164 number, or null when the token was not issued by Firebase phone sign-in. */
    private String phoneNumberOf(FirebaseToken token) {
        Map<String, Object> claims = token.getClaims();
        Object firebase = claims.get("firebase");
        Object provider = firebase instanceof Map<?, ?> map ? map.get("sign_in_provider") : null;
        Object phone = claims.get("phone_number");
        if ("phone".equals(provider) && phone instanceof String number && phoneNumbers.isValidE164(number)) {
            return number;
        }
        return null;
    }
}
