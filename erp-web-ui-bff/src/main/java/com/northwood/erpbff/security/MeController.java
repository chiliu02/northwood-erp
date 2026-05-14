package com.northwood.erpbff.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tiny BFF-local endpoint so the SPA can render the current user (username +
 * full name + roles) without learning to decode the JWT. Hit by AppBar on
 * mount; returns 401 when no session, which trips the SPA's redirect.
 *
 * <p>Roles are read from the Keycloak <b>access token</b>'s
 * {@code realm_access.roles} claim, not the ID token. Keycloak's default
 * client scope puts realm roles on the access token only — the ID token
 * carries them only if a custom "realm roles" mapper sets
 * {@code Add to ID token: ON}, which the seeded realm doesn't. Reading the
 * access token here matches what the resource-server services do via
 * {@link com.northwood.shared.infrastructure.security.KeycloakRealmRoleConverter}
 * so the UI gate and {@code @PreAuthorize} agree.
 */
@RestController
public class MeController {

    public record MeResponse(String username, String fullName, List<String> roles) {}

    private static final Logger log = LoggerFactory.getLogger(MeController.class);

    private final BffAccessTokenAccessor tokenAccessor;
    private final ObjectMapper json;

    public MeController(BffAccessTokenAccessor tokenAccessor, ObjectMapper json) {
        this.tokenAccessor = tokenAccessor;
        this.json = json;
    }

    @GetMapping("/api/me")
    public ResponseEntity<MeResponse> me(
        @AuthenticationPrincipal OidcUser principal,
        OAuth2AuthenticationToken auth
    ) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        String username = principal.getPreferredUsername();
        String fullName = principal.getFullName();
        List<String> roles = extractRealmRolesFromAccessToken();
        if (roles.isEmpty()) {
            // Fallbacks: ID token (if realm has a "realm roles -> id token"
            // mapper) and Spring's default scope/oidc authorities (last resort
            // so the endpoint never returns 500 if both token shapes fail).
            roles = extractRealmRolesFromIdToken(principal);
        }
        if (roles.isEmpty()) {
            roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        }
        return ResponseEntity.ok(new MeResponse(username, fullName, roles));
    }

    private List<String> extractRealmRolesFromAccessToken() {
        String token = tokenAccessor.currentAccessToken();
        if (token == null) return List.of();
        String[] parts = token.split("\\.");
        if (parts.length < 2) return List.of();
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode root = json.readTree(new String(payload, StandardCharsets.UTF_8));
            JsonNode roles = root.path("realm_access").path("roles");
            if (!roles.isArray()) return List.of();
            List<String> out = new ArrayList<>(roles.size());
            roles.forEach(r -> out.add(r.asText()));
            return out;
        } catch (RuntimeException e) {
            log.warn("Could not parse realm_access.roles from access token: {}", e.toString());
            return List.of();
        }
    }

    private static List<String> extractRealmRolesFromIdToken(OidcUser principal) {
        Object claim = principal.getClaims().get("realm_access");
        if (!(claim instanceof Map<?, ?> map)) return List.of();
        Object roles = map.get("roles");
        return (roles instanceof List<?> list)
            ? list.stream().map(Object::toString).toList()
            : List.of();
    }
}
