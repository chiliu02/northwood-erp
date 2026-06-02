package com.northwood.shared.application.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pulls the current authenticated user out of the SecurityContext for
 * application services that need to record the actor on a domain mutation
 * (e.g. {@code created_by} / {@code last_modified_by} columns, event
 * {@code actorUserId} fields).
 *
 * <p>{@link #currentUsername()} returns the Keycloak {@code preferred_username}
 * when the request carried a JWT (security guarantees this for every endpoint
 * except the actuator allow-list). Returns {@code Optional.empty()} when no
 * authentication is in progress — applies to the actuator paths, to
 * Liquibase / outbox-publisher / saga-worker threads that run outside an HTTP
 * request, and to unit tests that don't set up a {@link SecurityContextHolder}.
 *
 * <p>The application layer uses this to stamp the actor on
 * aggregate-header rows + outbox event payloads. At minimum the
 * accessor only needs to exist + be wired so existing code can start
 * preparing call sites.
 *
 * <p>Registered as a {@code @Bean} via
 * {@link OAuth2ResourceServerSecurityConfig} rather than {@code @Component}
 * because shared-module classes aren't auto-scanned by services (each
 * service's component scan starts at its own {@code @SpringBootApplication}
 * package).
 */
public class CurrentUserAccessor {

    public Optional<String> currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) {
            String preferred = jwt.getClaimAsString("preferred_username");
            if (preferred != null && !preferred.isBlank()) return Optional.of(preferred);
        }
        // Fallback for non-JWT principals (none today, but keeps the contract
        // honest for tests / future authentication strategies).
        String name = auth.getName();
        return (name == null || name.isBlank()) ? Optional.empty() : Optional.of(name);
    }
}
