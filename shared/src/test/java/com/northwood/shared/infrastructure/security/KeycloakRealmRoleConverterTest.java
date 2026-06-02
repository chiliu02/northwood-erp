package com.northwood.shared.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void prefixes_realm_roles_with_ROLE() {
        Jwt jwt = jwt(Map.of(
            "realm_access", Map.of("roles", List.of("catalog_manager", "auditor"))
        ));

        Set<String> authorities = authorities(converter.convert(jwt));

        assertTrue(authorities.contains("ROLE_catalog_manager"));
        assertTrue(authorities.contains("ROLE_auditor"));
    }

    @Test
    void hasRole_works_against_emitted_authorities() {
        // hasRole('catalog_manager') is the runtime expression role enforcement uses;
        // make sure the prefix shape matches what Spring Security expects.
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("catalog_manager"))));
        AbstractAuthenticationToken token = converter.convert(jwt);
        Set<String> authorities = authorities(token);
        assertTrue(authorities.contains("ROLE_catalog_manager"),
            "Spring Security's hasRole('X') matches authority 'ROLE_X' — missing the prefix would break @PreAuthorize");
    }

    @Test
    void no_realm_access_claim_yields_no_role_authorities() {
        Jwt jwt = jwt(Map.of()); // anonymous-ish token, just scopes.
        Set<String> authorities = authorities(converter.convert(jwt));
        assertFalse(authorities.stream().anyMatch(a -> a.startsWith("ROLE_")));
    }

    @Test
    void empty_roles_list_yields_no_role_authorities() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of())));
        Set<String> authorities = authorities(converter.convert(jwt));
        assertFalse(authorities.stream().anyMatch(a -> a.startsWith("ROLE_")));
    }

    @Test
    void scope_authorities_are_preserved_alongside_realm_roles() {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuer("http://localhost:8090/realms/northwood")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("scope", "openid profile email")
            .claim("realm_access", Map.of("roles", List.of("sales_clerk")))
            .build();

        Set<String> authorities = authorities(converter.convert(jwt));

        assertTrue(authorities.contains("ROLE_sales_clerk"));
        assertTrue(authorities.contains("SCOPE_openid"));
        assertTrue(authorities.contains("SCOPE_profile"));
        assertTrue(authorities.contains("SCOPE_email"));
    }

    @Test
    void principal_name_defaults_to_sub_claim() {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuer("http://localhost:8090/realms/northwood")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .subject("user-uuid-123")
            .claim("preferred_username", "emma")
            .claim("realm_access", Map.of("roles", List.of("catalog_manager")))
            .build();

        AbstractAuthenticationToken token = converter.convert(jwt);
        // Spring's default JwtAuthenticationConverter uses the `sub` claim as
        // principal name. The Keycloak preferred_username is reachable via
        // the JWT claims map, but is not the principal name today.
        assertEquals("user-uuid-123", token.getName());
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuer("http://localhost:8090/realms/northwood")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .subject("user-uuid");
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static Set<String> authorities(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());
    }
}
