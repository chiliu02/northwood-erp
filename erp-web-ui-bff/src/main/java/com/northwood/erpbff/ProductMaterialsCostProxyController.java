package com.northwood.erpbff;

import com.northwood.erpbff.security.BffAccessTokenAccessor;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes {@code GET /api/products/{id}/materials-cost} to
 * manufacturing-service. The generic {@link ProxyController} matches
 * {@code /api/products} → product-service first; this more-specific
 * mapping wins over the {@code /api/**} catch-all by Spring's standard
 * mapping precedence (longest pattern wins).
 *
 * <p>Why a one-off proxy and not a route-table entry: the route table is
 * pure prefix-based, but the materialsCost path is a *suffix* under
 * {@code /api/products/{id}/...}. Adding a Predicate-based route just for
 * this one endpoint isn't worth the complexity yet — when a second
 * suffix-routed endpoint shows up we'll generalise.
 */
@RestController
public class ProductMaterialsCostProxyController {

    private final ErpBffTargets targets;
    private final BffAccessTokenAccessor tokens;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    public ProductMaterialsCostProxyController(
        ErpBffTargets targets,
        BffAccessTokenAccessor tokens
    ) {
        this.targets = targets;
        this.tokens = tokens;
    }

    @GetMapping("/api/products/{productId}/materials-cost")
    public ResponseEntity<byte[]> get(@PathVariable UUID productId) throws IOException, InterruptedException {
        URI uri = URI.create(
            targets.forName("manufacturing") + "/api/products/" + productId + "/materials-cost"
        );
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(10))
            .GET();
        String token = tokens.currentAccessToken();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<byte[]> upstream = http.send(builder.build(), BodyHandlers.ofByteArray());
        return ResponseEntity.status(upstream.statusCode())
            .contentType(MediaType.APPLICATION_JSON)
            .body(upstream.body());
    }
}
