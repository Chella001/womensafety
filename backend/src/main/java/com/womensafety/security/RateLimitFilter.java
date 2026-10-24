package com.womensafety.security;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.womensafety.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sliding-window limiter per client address (one minute). Authentication endpoints get a lower limit than the rest of
 * the API. State is in memory, so with several application instances each enforces its own limit.
 *
 * <p>The client address is {@code request.getRemoteAddr()}. Behind a reverse proxy configure Spring's forwarded-header
 * support so the real client address is used; the {@code X-Forwarded-For} header is deliberately not trusted here.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_KEYS = 50_000;

    private final RateLimitProperties properties;
    private final JsonSecurityErrorHandler errorHandler;
    private final Clock clock;
    private final Map<String, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties, JsonSecurityErrorHandler errorHandler, Clock clock) {
        this.properties = properties;
        this.errorHandler = errorHandler;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled()
                || !request.getRequestURI().startsWith("/api/")
                || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String bucket = bucket(request);
        long retryAfter = tryAcquire(request.getRemoteAddr() + "|" + bucket, limit(bucket));
        if (retryAfter > 0) {
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            errorHandler.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests. Please wait a moment and try again.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String bucket(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/api/auth/")) {
            return "auth";
        }
        if (path.startsWith("/api/admin/") && !"GET".equals(method)) {
            return "admin-write";
        }
        if ("POST".equals(method) && path.startsWith("/api/emergency-contacts/") && path.contains("/verification")) {
            return "sensitive";
        }
        return "api";
    }

    private int limit(String bucket) {
        return switch (bucket) {
            case "auth" -> properties.authPerMinute();
            case "admin-write" -> properties.adminWritePerMinute();
            case "sensitive" -> properties.sensitivePerMinute();
            default -> properties.generalPerMinute();
        };
    }

    /** @return 0 when the request is allowed, otherwise the seconds the client should wait */
    long tryAcquire(String key, int limit) {
        long now = clock.millis();
        if (hits.size() > MAX_TRACKED_KEYS) {
            hits.clear(); // crude protection against unbounded growth under a flood of distinct addresses
        }
        ArrayDeque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return Math.max(1, (WINDOW_MILLIS - (now - window.peekFirst()) + 999) / 1000);
            }
            window.addLast(now);
            return 0;
        }
    }
}
