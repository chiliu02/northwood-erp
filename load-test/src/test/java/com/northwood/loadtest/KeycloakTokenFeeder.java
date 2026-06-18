package com.northwood.loadtest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mints one Keycloak bearer token per demo user via the OAuth2 password grant, so
 * the REST execution drives genuinely <em>different users</em> (distinct
 * {@code preferred_username} → distinct {@code created_by}; see
 * {@code docs/concurrent-load-test.md} §5). The returned list is a Gatling-shaped
 * feeder ({@code [{user, token}, …]}); a virtual user reads {@code #{token}} into
 * the {@code Authorization} header.
 *
 * <p>Plain {@code java.net.http} + a regex token extract (no JSON dependency) — a
 * test feeder, not production code. Tokens are pre-fetched once in the simulation's
 * {@code before {}} hook; they outlive a short load run, so no refresh logic.
 */
public final class KeycloakTokenFeeder {

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

    private final String tokenEndpoint;
    private final String clientId;
    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * @param issuerUri e.g. {@code http://localhost:8090/realms/northwood}
     * @param clientId  the public/direct-grant client configured for the realm
     */
    public KeycloakTokenFeeder(String issuerUri, String clientId) {
        this.tokenEndpoint = issuerUri.replaceAll("/+$", "") + "/protocol/openid-connect/token";
        this.clientId = clientId;
    }

    /** One feeder row {@code {user, token}} per username, sharing one password. */
    public List<Map<String, Object>> tokensFor(List<String> usernames, String password) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String username : usernames) {
            rows.add(Map.of("user", username, "token", fetchToken(username, password)));
        }
        return rows;
    }

    private String fetchToken(String username, String password) {
        String form = "grant_type=password"
            + "&client_id=" + enc(clientId)
            + "&username=" + enc(username)
            + "&password=" + enc(password);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                    "Keycloak token request for " + username + " failed: HTTP " + response.statusCode() + " " + response.body());
            }
            Matcher matcher = ACCESS_TOKEN.matcher(response.body());
            if (!matcher.find()) {
                throw new IllegalStateException("No access_token in Keycloak response for " + username);
            }
            return matcher.group(1);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Keycloak token request for " + username + " failed", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
