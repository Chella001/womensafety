package com.womensafety.security;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.womensafety.dto.ApiError;
import com.womensafety.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Writes 401 / 403 responses from the security filter chain using the same JSON shape as
 * {@link com.womensafety.exception.GlobalExceptionHandler}. A 403 on an admin path is also recorded as a security event.
 */
@Component
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonSecurityErrorHandler.class);

    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public JsonSecurityErrorHandler(ObjectMapper objectMapper, AuditService auditService) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required. Sign in again and retry.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        if (request.getRequestURI().startsWith("/api/admin")) {
            recordDeniedAdminAccess(request);
        }
        write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to perform this action.");
    }

    public void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        objectMapper.writeValue(response.getOutputStream(),
                new ApiError(Instant.now(), status.value(), code, message, null));
    }

    private void recordDeniedAdminAccess(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String uid = authentication != null && authentication.getPrincipal() instanceof FirebaseUserPrincipal p ? p.uid() : null;
        try {
            auditService.recordSecurityEvent("ADMIN_ACCESS_DENIED", uid,
                    Map.of("method", request.getMethod(), "path", request.getRequestURI()));
        } catch (RuntimeException e) {
            log.warn("Could not record the denied admin access in the audit log", e);
        }
    }
}
