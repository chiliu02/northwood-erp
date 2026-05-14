package com.northwood.bff;

import java.net.http.HttpRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the auth header the BFF stamps on every outbound
 * call to a Northwood service. The demo SPA has no login UI by design, so the
 * BFF cannot relay a per-user Bearer token (the way erp-web-ui-bff does). It
 * uses a shared-secret header instead — the matching server-side filter is
 * {@code DemoBypassAuthenticationFilter} in the {@code shared} module.
 *
 * <p>Token defaults to the same literal the server-side filter defaults to so
 * a fresh local checkout works without env-var setup. Override via env var
 * {@code NORTHWOOD_SECURITY_DEMOBYPASS_TOKEN} (or set the property to an empty
 * string) to disable.
 *
 * <p>All BFF outbound HTTP clients ({@link ProxyController},
 * {@link SagaAggregatorController}) call {@link #applyTo(HttpRequest.Builder)}
 * to add the header — never set it directly.
 */
@Component
public class BackendAuthHeader {

    public static final String HEADER = "X-Northwood-Demo-Bypass";

    private final String token;

    public BackendAuthHeader(
        @Value("${northwood.security.demo-bypass.token:northwood-local-demo-bypass-2026}") String token
    ) {
        this.token = token == null ? "" : token;
    }

    public void applyTo(HttpRequest.Builder builder) {
        if (!token.isBlank()) {
            builder.header(HEADER, token);
        }
    }
}
