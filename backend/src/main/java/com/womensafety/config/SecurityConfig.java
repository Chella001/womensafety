package com.womensafety.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.google.firebase.auth.FirebaseAuth;
import com.womensafety.repository.UserRepository;
import com.womensafety.security.AccountStatusFilter;
import com.womensafety.security.FirebaseTokenFilter;
import com.womensafety.security.JsonSecurityErrorHandler;
import com.womensafety.security.RateLimitFilter;
import com.womensafety.validation.PhoneNumbers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless API security. Every {@code /api/**} call must carry a Firebase ID token that the Admin SDK has verified
 * (and that came from phone sign-in); {@code /api/admin/**} additionally requires {@code ROLE_ADMIN}, which only the
 * server-side administrator allow-list can grant. No cookies or sessions are used, so CSRF protection does not apply.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            FirebaseAuth firebaseAuth,
            FirebaseProperties firebaseProperties,
            JsonSecurityErrorHandler errorHandler,
            PhoneNumbers phoneNumbers,
            AdminProperties adminProperties,
            RateLimitProperties rateLimitProperties,
            UserRepository userRepository,
            Clock clock) throws Exception {

        // The filters are not beans on purpose: a Filter bean would also be registered with the servlet container.
        RateLimitFilter rateLimitFilter = new RateLimitFilter(rateLimitProperties, errorHandler, clock);
        FirebaseTokenFilter tokenFilter = new FirebaseTokenFilter(
                firebaseAuth, firebaseProperties.checkRevoked(), errorHandler, phoneNumbers, adminProperties);
        AccountStatusFilter accountStatusFilter = new AccountStatusFilter(userRepository, errorHandler);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.deny()))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Provider delivery receipts carry no Firebase token; the controller authenticates them by signature.
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(accountStatusFilter, FirebaseTokenFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("Idempotent-Replayed", "Retry-After"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
