package com.northwood.erpbff;

import com.northwood.erpbff.security.BffAccessTokenAccessor;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generic HTTP reverse proxy for every {@code /api/**} path the SPA hits.
 * The routing table in {@link RouteTable} maps path prefixes to service
 * names; {@link ErpBffTargets} resolves those to URLs.
 *
 * <p>Streaming response bodies (SSE) are NOT proxied — the JDK HttpClient's
 * buffered send blocks. Aggregated SSE channels are composed locally
 * ({@link EventsAggregatorController}) which has higher mapping precedence
 * than this catch-all.
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private static final Set<String> FILTERED_HEADERS = Set.of(
        // The browser's session cookie + the user's incoming Authorization
        // header are stripped on the way upstream — the BFF replaces them with
        // a fresh Bearer token sourced from the OIDC session via
        // BffAccessTokenAccessor. "cookie" is filtered so the JSESSIONID set
        // by Spring Security on this BFF doesn't leak to backend services
        // (which would ignore it anyway, but cleanliness matters).
        "host", "content-length", "transfer-encoding", "connection", "upgrade",
        "keep-alive", "proxy-authorization", "proxy-authenticate", "te", "trailer",
        "authorization", "cookie"
    );

    private final RouteTable routes;
    private final ErpBffTargets targets;
    private final BffAccessTokenAccessor tokens;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    public ProxyController(RouteTable routes, ErpBffTargets targets, BffAccessTokenAccessor tokens) {
        this.routes = routes;
        this.targets = targets;
        this.tokens = tokens;
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> proxy(
        HttpServletRequest request,
        @RequestBody(required = false) byte[] body
    ) throws IOException, InterruptedException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        String query = request.getQueryString();

        RouteTable.Route route = routes.find(path);
        if (route == null) {
            return ResponseEntity.status(404)
                .contentType(MediaType.TEXT_PLAIN)
                .body(("BFF: no route for " + path).getBytes());
        }

        String target = targets.forName(route.target());
        String rewrittenPath = rewrite(path, route);
        URI uri = URI.create(target + rewrittenPath + (query != null ? "?" + query : ""));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(30))
            .method(method, body != null && body.length > 0 ? BodyPublishers.ofByteArray(body) : BodyPublishers.noBody());
        copyHeaders(request, builder);
        String accessToken = tokens.currentAccessToken();
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }

        try {
            HttpResponse<byte[]> upstream = http.send(builder.build(), BodyHandlers.ofByteArray());
            return mirror(upstream);
        } catch (IOException | InterruptedException e) {
            log.warn("Upstream {} {} failed: {}", method, uri, e.getMessage());
            throw e;
        }
    }

    static String rewrite(String path, RouteTable.Route route) {
        if (route.rewrite() == null) return path;
        return route.rewrite() + path.substring(route.prefix().length());
    }

    private static void copyHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (FILTERED_HEADERS.contains(name.toLowerCase())) continue;
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                try {
                    builder.header(name, values.nextElement());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private static ResponseEntity<byte[]> mirror(HttpResponse<byte[]> upstream) {
        HttpHeaders headers = new HttpHeaders();
        upstream.headers().map().forEach((name, values) -> {
            if (FILTERED_HEADERS.contains(name.toLowerCase())) return;
            headers.put(name, List.copyOf(values));
        });
        return ResponseEntity.status(upstream.statusCode())
            .headers(headers)
            .body(upstream.body());
    }
}
