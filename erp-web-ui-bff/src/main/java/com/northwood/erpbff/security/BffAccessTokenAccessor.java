package com.northwood.erpbff.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Pulls the current user's Keycloak access token out of the OIDC session so
 * {@link com.northwood.erpbff.ProxyController} can forward it as a Bearer
 * header on every upstream call. Returns null when no OIDC session is in
 * progress (e.g. during anonymous health probes that bypass auth).
 *
 * <p>Reads the authorized client through the {@link OAuth2AuthorizedClientRepository}
 * rather than an {@code OAuth2AuthorizedClientService}: the repository bean is
 * the session-backed {@code HttpSessionOAuth2AuthorizedClientRepository} (see
 * {@link BffSecurityConfig#authorizedClientRepository()}), so the token lives in
 * the HttpSession — and therefore in Redis once Spring Session is on — and
 * survives a BFF restart. The service-based default kept the token in per-JVM
 * memory, which a restart wiped while the session itself (in Redis) still looked
 * authenticated. The repository load needs the current request; this component is
 * a singleton but every call runs on a request thread, so it resolves the request
 * from {@link RequestContextHolder} and the four callers keep their signatures.
 */
@Component
public class BffAccessTokenAccessor {

    private final OAuth2AuthorizedClientRepository authorizedClients;

    public BffAccessTokenAccessor(OAuth2AuthorizedClientRepository authorizedClients) {
        this.authorizedClients = authorizedClients;
    }

    public String currentAccessToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof OAuth2AuthenticationToken oauth)) return null;
        HttpServletRequest request = currentRequest();
        if (request == null) return null;
        OAuth2AuthorizedClient client = authorizedClients.loadAuthorizedClient(
            oauth.getAuthorizedClientRegistrationId(),
            oauth,
            request
        );
        if (client == null || client.getAccessToken() == null) return null;
        return client.getAccessToken().getTokenValue();
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest();
        }
        return null;
    }
}
