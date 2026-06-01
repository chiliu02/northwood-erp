package com.northwood.shared.infrastructure.security;

import com.northwood.shared.application.security.CurrentUserAccessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource-server Spring Security wiring for every Northwood service.
 *
 * <p>Slice A's bar: every endpoint requires a valid Keycloak JWT, except a small
 * allow-list of operational paths ({@code /actuator/health}, OpenAPI, Swagger
 * UI). No role enforcement yet — that lands in Slice B via {@code @PreAuthorize}
 * on individual controller methods.
 *
 * <p>Auto-configured for any service that depends on
 * the shared module and has {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}
 * set — so existing unit tests that boot without the property continue to work
 * and any future service can opt out by leaving the property unset.
 *
 * <p>Stateless sessions (JWT carries everything) and CSRF disabled (no
 * cookie-based session, no browser-form posts hitting these endpoints — the BFF
 * is the only browser-facing piece).
 */
@AutoConfiguration
@ConditionalOnClass(SecurityFilterChain.class)
@ConditionalOnProperty(prefix = "spring.security.oauth2.resourceserver.jwt", name = "issuer-uri")
@EnableMethodSecurity
public class OAuth2ResourceServerSecurityConfig {

    @Bean
    public SecurityFilterChain northwoodResourceServerFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    // §1D.1: Prometheus scrapes this endpoint anonymously (no
                    // JWT-aware client on the Prometheus side). Acceptable
                    // because /actuator/prometheus exposes JVM/HTTP histograms,
                    // not business data — and the docker-compose stack runs on
                    // localhost. /actuator/metrics stays auth-gated because it
                    // exposes the metric registry's tag-set which can reveal
                    // route names; only /prometheus needs to be open.
                    "/actuator/prometheus",
                    "/v3/api-docs",
                    "/v3/api-docs/**",
                    "/v3/api-docs.yaml",
                    "/swagger-ui",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                jwt.jwtAuthenticationConverter(new KeycloakRealmRoleConverter())));
        return http.build();
    }

    @Bean
    public CurrentUserAccessor currentUserAccessor() {
        return new CurrentUserAccessor();
    }
}
