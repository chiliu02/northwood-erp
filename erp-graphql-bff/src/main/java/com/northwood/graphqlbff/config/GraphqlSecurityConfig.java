package com.northwood.graphqlbff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security wiring for the GraphQL BFF — the OIDC counterpart of
 * {@code erp-web-ui-bff}'s {@code BffSecurityConfig}. Runs the Keycloak
 * authorization-code flow and holds the tokens in the (Redis-backed) session;
 * the seven services remain pure resource-servers validating the Bearer token.
 *
 * <p>Everything except the actuator probes is authenticated, so an
 * unauthenticated browser navigation to {@code /graphiql} is redirected to
 * Keycloak by {@code oauth2Login}; once logged in, GraphiQL's same-origin
 * {@code POST /graphql} (and the {@code /graphql} WebSocket handshake) carry the
 * session cookie. CSRF is disabled — this is a JSON/WebSocket API, not a
 * cookie-authenticated HTML form surface (mirrors the proxy BFF).
 */
@Configuration
@EnableWebSecurity
public class GraphqlSecurityConfig {

    @Bean
    public SecurityFilterChain graphqlFilterChain(HttpSecurity http) throws Exception {
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
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
            )
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * Session-backed authorized-client store — the same fix as
     * {@code erp-web-ui-bff}: without it the default repository delegates to the
     * auto-configured {@code InMemoryOAuth2AuthorizedClientService}, leaving the
     * tokens in per-JVM memory (a restart would break upstream calls while the
     * Redis session still looked authenticated). See docs/redis.md (Part 1).
     */
    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }
}
