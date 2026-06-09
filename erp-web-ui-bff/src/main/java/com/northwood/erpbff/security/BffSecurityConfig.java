package com.northwood.erpbff.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * BFF-side Spring Security wiring. The browser-facing piece runs the OIDC
 * authorization-code flow against Keycloak and holds the resulting tokens in
 * a server-side session. The seven Northwood services are pure resource-servers
 * — see the shared module's
 * {@code OAuth2ResourceServerSecurityConfig}.
 *
 * <p>Two distinct unauthenticated-request strategies coexist:
 *
 * <ul>
 *   <li><b>{@code /api/**}</b> returns plain HTTP 401. The SPA's fetch wrapper
 *       detects the 401 and triggers {@code window.location =
 *       "/oauth2/authorization/keycloak"} so the user navigates to the IdP.
 *       Returning a redirect status to an XHR/fetch call wouldn't move the
 *       browser anywhere — fetch follows the redirect server-side and ends
 *       up with a CORS-blocked Keycloak HTML response.</li>
 *   <li><b>Everything else</b> falls back to the default oauth2Login()
 *       entry point, which redirects browser navigations to
 *       {@code /oauth2/authorization/keycloak} directly. Useful if the user
 *       hits the BFF at the root URL without going through the SPA.</li>
 * </ul>
 *
 * <p>{@code /actuator/health} is permitted unauthenticated for the Docker
 * healthcheck, and {@code /actuator/prometheus} so the observability box can
 * scrape BFF metrics (it has no session); both mirror how the resource-server
 * services expose them. {@code /api/events} (the SSE notification stream) is
 * treated as any other authenticated endpoint — the session cookie carries
 * the auth.
 *
 * <p>CSRF is disabled because the SPA hits this BFF as a JSON API only; no
 * cookie-authenticated HTML forms exist. The session cookie is still required
 * (CSRF is about cross-origin form posts, not session presence).
 */
@Configuration
@EnableWebSecurity
public class BffSecurityConfig {

    @Bean
    public SecurityFilterChain bffFilterChain(
        HttpSecurity http,
        LogoutSuccessHandler oidcLogoutSuccessHandler
    ) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/actuator/prometheus"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(Customizer.withDefaults())
            .logout(logout -> logout
                .logoutUrl("/logout")
                // OpenID Connect RP-Initiated Logout — ends both the BFF
                // session AND the Keycloak SSO session by redirecting to
                // Keycloak's end_session_endpoint with id_token_hint. Without
                // this, the BFF session is cleared but Keycloak's SSO cookie
                // remains; the next /oauth2/authorization/keycloak silently
                // auto-logs the user back in as the same persona, defeating
                // both logout and the persona switcher.
                .logoutSuccessHandler(oidcLogoutSuccessHandler)
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
            )
            .csrf(csrf -> csrf.disable())
            .exceptionHandling(eh -> eh.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                PathPatternRequestMatcher.withDefaults().matcher("/api/**")
            ));
        return http.build();
    }

    /**
     * Post-logout: redirect the browser to Keycloak's
     * {@code end_session_endpoint}, which ends the SSO cookie and then
     * redirects back to {@code northwood.bff.web-ui-url}. The redirect URI
     * must be registered in the realm's
     * {@code post.logout.redirect.uris} client attribute, otherwise Keycloak
     * rejects the redirect after end-session.
     */
    @Bean
    public LogoutSuccessHandler oidcLogoutSuccessHandler(
        ClientRegistrationRepository clientRegistrationRepository,
        @Value("${northwood.bff.web-ui-url:http://localhost:5174/}") String webUiUrl
    ) {
        OidcClientInitiatedLogoutSuccessHandler handler =
            new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri(webUiUrl);
        return handler;
    }
}
