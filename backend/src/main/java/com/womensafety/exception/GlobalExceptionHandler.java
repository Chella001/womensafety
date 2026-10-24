package com.womensafety.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.womensafety.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception to the {@link ApiError} body. Standard Spring MVC problems
 * (bad JSON, wrong method, unknown path…) are handled by the parent class and rendered the same way.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> handleNotFound(ResourceNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getCode(), e.getMessage(), null);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> handleConflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e.getCode(), e.getMessage(), null);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Object> handleInvalidRequest(InvalidRequestException e) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request contains invalid data.",
                Map.of(e.getField(), e.getMessage()));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<Object> handleTooManyRequests(TooManyRequestsException e) {
        ApiError error = new ApiError(Instant.now(), 429, "RATE_LIMITED", e.getMessage(), null);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(error);
    }

    @ExceptionHandler(DataStoreException.class)
    ResponseEntity<Object> handleDataStore(DataStoreException e) {
        log.error("Firestore operation failed", e);
        return body(HttpStatus.SERVICE_UNAVAILABLE, "DATA_STORE_UNAVAILABLE",
                "SafeHer is temporarily unavailable. Please try again shortly.", null);
    }

    @ExceptionHandler(NotificationUnavailableException.class)
    ResponseEntity<Object> handleNotificationUnavailable(NotificationUnavailableException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "NOTIFICATION_UNAVAILABLE", e.getMessage(), null);
    }

    @ExceptionHandler(IdentityServiceException.class)
    ResponseEntity<Object> handleIdentityService(IdentityServiceException e) {
        log.error("Firebase Authentication operation failed", e);
        return body(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_SERVICE_UNAVAILABLE",
                "The sign-in service is temporarily unavailable. Please try again shortly.", null);
    }

    /** Lets Spring Security's own handlers produce the 401/403 instead of turning them into a 500. */
    @ExceptionHandler(AccessDeniedException.class)
    void rethrowAccessDenied(AccessDeniedException e) {
        throw e;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong on our side. Please try again.", null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request contains invalid data.", details);
    }

    /** Renders all remaining Spring MVC exceptions (400, 404, 405, 415…) with the common body. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        ApiError error = new ApiError(Instant.now(), status.value(), status.name(), status.getReasonPhrase(), null);
        return new ResponseEntity<>(error, headers, status);
    }

    private static ResponseEntity<Object> body(HttpStatus status, String code, String message,
                                               Map<String, String> details) {
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now(), status.value(), code, message, details));
    }
}
