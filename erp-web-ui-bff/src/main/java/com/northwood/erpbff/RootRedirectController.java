package com.northwood.erpbff;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redirects browser hits on the BFF root ({@code GET /}) to the operational
 * ERP SPA. The OIDC callback lands the browser on the BFF's own origin (the
 * realm-registered redirect URI is {@code http://localhost:8089/login/oauth2/
 * code/keycloak}), so after a successful login Spring's default
 * {@code defaultSuccessUrl="/"} sends the user to {@code localhost:8089/} —
 * which would otherwise hit the Whitelabel 404 page (the BFF is a pure API +
 * security-chain host with no UI).
 *
 * <p>The session cookie set by the callback is scoped to host ({@code
 * localhost}) so it's available to the SPA's origin too — the user lands on
 * the SPA already authenticated.
 *
 * <p>In production / non-dev environments, override
 * {@code northwood.bff.web-ui-url} to whatever URL serves the SPA. The
 * default is the Vite dev server port for the local development loop.
 */
@RestController
public class RootRedirectController {

    private final String webUiUrl;

    public RootRedirectController(
        @Value("${northwood.bff.web-ui-url:http://localhost:5174/}") String webUiUrl
    ) {
        this.webUiUrl = webUiUrl;
    }

    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, webUiUrl)
            .build();
    }
}
