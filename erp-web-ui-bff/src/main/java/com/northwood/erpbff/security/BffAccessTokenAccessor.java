package com.northwood.erpbff.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Pulls the current user's Keycloak access token out of the OIDC session so
 * {@link com.northwood.erpbff.ProxyController} can forward it as a Bearer
 * header on every upstream call. Returns null when no OIDC session is in
 * progress (e.g. during anonymous health probes that bypass auth).
 */
@Component
public class BffAccessTokenAccessor {

    private final OAuth2AuthorizedClientService clientService;

    public BffAccessTokenAccessor(OAuth2AuthorizedClientService clientService) {
        this.clientService = clientService;
    }

    public String currentAccessToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof OAuth2AuthenticationToken oauth)) return null;
        OAuth2AuthorizedClient client = clientService.loadAuthorizedClient(
            oauth.getAuthorizedClientRegistrationId(),
            oauth.getName()
        );
        if (client == null || client.getAccessToken() == null) return null;
        return client.getAccessToken().getTokenValue();
    }
}
