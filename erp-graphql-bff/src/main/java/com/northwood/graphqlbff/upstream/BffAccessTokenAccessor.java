package com.northwood.graphqlbff.upstream;

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
 * Pulls the current user's Keycloak access token out of the OIDC session so it
 * can be forwarded as a Bearer header on every upstream call. Copied from
 * {@code erp-web-ui-bff} (the BFF keeps no Maven dependency on the services).
 *
 * <p>Reads the authorized client through the session-backed
 * {@link OAuth2AuthorizedClientRepository} (see
 * {@code GraphqlSecurityConfig#authorizedClientRepository()}), so the token
 * lives in the HttpSession — and therefore in Redis — and survives a restart.
 * The repository load needs the current request; this is a singleton, but every
 * call runs on the servlet request thread, so it resolves the request from
 * {@link RequestContextHolder}. Captured once per request by
 * {@code TokenPropagationInterceptor} and stashed in the GraphQLContext, since
 * batch loaders run on threads where {@link SecurityContextHolder} is empty.
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
