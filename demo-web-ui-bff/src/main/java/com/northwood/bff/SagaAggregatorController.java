package com.northwood.bff;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * Aggregates the three per-service saga streams into one. Polls each service's
 * {@code GET /api/sagas} list endpoint at 1s intervals, diffs by
 * {@code (sagaId, version)}, and re-broadcasts version-changed rows to all
 * SPA subscribers via a single {@code SseEmitter}.
 *
 * <p>The {@code /api/sagas} list endpoint returns the merged list of all
 * three saga types ordered by {@code updatedAt DESC} so the SPA can do a
 * single fetch for initial state.
 *
 * <p>Why poll instead of subscribing to each upstream SSE: subscribing to
 * three SSE streams from a non-reactive (servlet) Spring app is awkward and
 * the per-service controllers themselves already poll their tables at 1s, so
 * adding an SSE-of-SSE relay would just chain two poll cycles. Polling the
 * list endpoint gives the same end-to-end latency with simpler code.
 */
@RestController
@RequestMapping("/api/sagas")
public class SagaAggregatorController {

    private static final Logger log = LoggerFactory.getLogger(SagaAggregatorController.class);

    /**
     * Wire shape mirrored from the per-service {@code SagaApiController.SagaRow}
     * record. We deliberately don't share-pkg this — the BFF stays
     * domain-agnostic, and a tiny duplicated record is cheaper than a
     * cross-module dependency.
     */
    public record SagaRow(
        UUID sagaId,
        UUID domainKey,
        String domainKeyLabel,
        String sagaType,
        String state,
        String currentStep,
        String lastError,
        int retryCount,
        long version,
        // §1D.4: W3C trace ID stamped at row INSERT by §1D.3's saga adapter.
        // Passed through verbatim from upstream `/api/sagas` so the SPA's Saga
        // Console can render a `↗ trace` affordance per row that deep-links to
        // Grafana Tempo Explore. Nullable for saga rows written before §1D.3.
        String traceId,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
    ) {}

    private final BffTargets targets;
    private final ObjectMapper json;
    private final BackendAuthHeader auth;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    /** seenVersion[sagaId] = last version we broadcast for that saga. */
    private final Map<UUID, Long> seenVersion = new ConcurrentHashMap<>();

    public SagaAggregatorController(BffTargets targets, ObjectMapper json, BackendAuthHeader auth) {
        this.targets = targets;
        this.json = json;
        this.auth = auth;
    }

    /** Aggregated list across all three saga services, newest activity first. */
    @GetMapping
    public List<SagaRow> list() {
        List<SagaRow> all = new ArrayList<>();
        for (String service : List.of("sales", "manufacturing", "purchasing")) {
            try {
                all.addAll(fetchUpstream(service));
            } catch (Exception e) {
                log.warn("Failed to fetch sagas from {}: {}", service, e.getMessage());
            }
        }
        all.sort(Comparator.comparing(
            SagaRow::updatedAt,
            Comparator.nullsLast(Comparator.reverseOrder())
        ));
        return all;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception ignored) {
            subscribers.remove(emitter);
        }
        return emitter;
    }

    @Scheduled(fixedDelay = 1000)
    public void pump() {
        if (subscribers.isEmpty()) return;
        for (String service : List.of("sales", "manufacturing", "purchasing")) {
            List<SagaRow> rows;
            try {
                rows = fetchUpstream(service);
            } catch (Exception e) {
                // Don't spam logs per poll cycle; one warning per pump if any
                // service is down is plenty.
                log.debug("upstream {} unreachable: {}", service, e.getMessage());
                continue;
            }
            for (SagaRow row : rows) {
                Long last = seenVersion.get(row.sagaId());
                if (last == null || last < row.version()) {
                    seenVersion.put(row.sagaId(), row.version());
                    broadcast(row);
                }
            }
        }
    }

    private List<SagaRow> fetchUpstream(String service) throws IOException, InterruptedException {
        URI uri = URI.create(targets.forName(service) + "/api/sagas");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(3))
            .GET();
        auth.applyTo(builder);
        HttpRequest req = builder.build();
        var response = http.send(req, BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException(service + " /api/sagas → " + response.statusCode());
        }
        return json.readerForListOf(SagaRow.class).readValue(response.body());
    }

    private void broadcast(SagaRow row) {
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("saga").data(row));
            } catch (Exception e) {
                log.debug("SSE subscriber dropped: {}", e.getMessage());
                subscribers.remove(emitter);
            }
        }
    }
}
