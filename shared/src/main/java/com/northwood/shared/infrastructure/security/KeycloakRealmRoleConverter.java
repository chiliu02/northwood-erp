package com.northwood.shared.infrastructure.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Maps a Keycloak JWT into Spring Security authorities so {@code @PreAuthorize}
 * works against realm roles.
 *
 * <p>By default Spring Security's {@link JwtAuthenticationConverter} pulls
 * authorities from the {@code scope} / {@code scp} claims only — it doesn't
 * know about Keycloak-specific role layouts. Keycloak surfaces realm roles
 * under the {@code realm_access.roles} JSON path; we pull each role, prefix
 * it with {@code ROLE_}, and concatenate onto the existing scope-derived
 * authorities.
 *
 * <p>Result: a token with {@code realm_access.roles = ["catalog_manager"]}
 * yields the authority {@code ROLE_catalog_manager}, which
 * {@code @PreAuthorize("hasRole('catalog_manager')")} recognises. Both the
 * scope-claim authorities and the realm-role authorities are present, so
 * existing scope-based annotations (if any are added later) keep working.
 *
 * <p>Client roles ({@code resource_access.<client>.roles}) aren't read today —
 * the codebase uses realm roles only. Add a second extractor here if that
 * ever changes.
 */
public final class KeycloakRealmRoleConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_CLAIM = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtAuthenticationConverter delegate;

    public KeycloakRealmRoleConverter() {
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
        this.delegate = new JwtAuthenticationConverter();
        this.delegate.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopeConverter.convert(jwt));
            authorities.addAll(extractRealmRoles(jwt));
            return authorities;
        });
        // Spring Security's default principal name is the `sub` claim (a UUID);
        // keep that, but the username is reachable via getTokenAttributes() for
        // anything that wants the human-readable form.
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return delegate.convert(jwt);
    }

    @SuppressWarnings("unchecked")
    private static List<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        Object claim = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (!(claim instanceof Map<?, ?> map)) return List.of();
        Object roles = map.get(ROLES_CLAIM);
        if (!(roles instanceof List<?> list)) return List.of();
        return list.stream()
            .map(Object::toString)
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
            .toList();
    }
}
