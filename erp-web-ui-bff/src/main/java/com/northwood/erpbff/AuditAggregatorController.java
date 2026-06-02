package com.northwood.erpbff;

import com.northwood.erpbff.security.BffAccessTokenAccessor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Cross-service audit-log aggregator. Fans out
 * {@code GET /api/audit?...} to every Northwood service in parallel, merges
 * the per-service rows by {@code occurredAt} descending, returns a single
 * timeline.
 *
 * <p>This handler is more specific than {@link ProxyController}'s
 * {@code /api/**} catch-all, so Spring's mapping precedence picks it for
 * {@code /api/audit} requests automatically.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditAggregatorController {

    private static final Logger log = LoggerFactory.getLogger(AuditAggregatorController.class);

    private static final List<String> SERVICES = List.of(
        "product", "sales", "inventory", "manufacturing", "purchasing", "finance", "reporting"
    );

    private final ErpBffTargets targets;
    private final BffAccessTokenAccessor tokens;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    public AuditAggregatorController(
        ErpBffTargets targets,
        BffAccessTokenAccessor tokens,
        ObjectMapper json
    ) {
        this.targets = targets;
        this.tokens = tokens;
        this.json = json;
    }

    @GetMapping
    public List<AuditRow> aggregate(
        @RequestParam(required = false) String aggregateId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) Integer limit
    ) {
        String token = tokens.currentAccessToken();
        String query = buildQuery(aggregateId, from, to, limit);

        List<CompletableFuture<List<AuditRow>>> futures = new ArrayList<>();
        for (String service : SERVICES) {
            futures.add(fetch(service, query, token));
        }

        List<AuditRow> merged = new ArrayList<>();
        for (CompletableFuture<List<AuditRow>> f : futures) {
            try {
                merged.addAll(f.join());
            } catch (RuntimeException e) {
                log.warn("audit aggregator: per-service fetch failed: {}", e.getMessage());
                // Continue with whatever responded — partial result is better
                // than blanking the timeline because one service was slow.
            }
        }
        merged.sort(Comparator.comparing(AuditRow::occurredAt).reversed());
        return merged;
    }

    private CompletableFuture<List<AuditRow>> fetch(String service, String query, String token) {
        URI uri = URI.create(targets.forName(service) + "/api/audit" + query);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(10))
            .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.sendAsync(builder.build(), BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() / 100 != 2) {
                    log.debug("audit aggregator: {} returned HTTP {}", service, response.statusCode());
                    return List.<AuditRow>of();
                }
                try {
                    return json.readValue(response.body(), new TypeReference<List<AuditRow>>() {});
                } catch (Exception e) {
                    log.debug("audit aggregator: failed to parse response from {}: {}", service, e.getMessage());
                    return List.<AuditRow>of();
                }
            })
            .exceptionally(ex -> {
                log.debug("audit aggregator: {} unreachable: {}", service, ex.getMessage());
                return List.of();
            });
    }

    private static String buildQuery(String aggregateId, String from, String to, Integer limit) {
        StringBuilder q = new StringBuilder();
        appendParam(q, "aggregateId", aggregateId);
        appendParam(q, "from", from);
        appendParam(q, "to", to);
        appendParam(q, "limit", limit == null ? null : limit.toString());
        return q.length() == 0 ? "" : "?" + q.substring(1);
    }

    private static void appendParam(StringBuilder q, String name, String value) {
        if (value == null || value.isBlank()) return;
        q.append('&').append(name).append('=').append(value);
    }

    /**
     * Wire shape mirroring the shared module's AuditEntry — but as a
     * record local to the BFF so we don't add a Maven dep on
     * the shared module (the BFF stays light: Spring web + Kafka only).
     *
     * <p>{@code traceId} is nullable: events emitted before the traceId field was introduced,
     * or unit-test runs without an active span, carry null.
     */
    public record AuditRow(
        String outboxMessageId,
        Long sequenceNumber,
        String sourceService,
        String aggregateType,
        String aggregateId,
        String eventType,
        String actorUserId,
        String correlationId,
        String traceId,
        java.time.Instant occurredAt
    ) {}
}
