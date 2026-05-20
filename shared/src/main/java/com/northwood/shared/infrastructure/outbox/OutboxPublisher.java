package com.northwood.shared.infrastructure.outbox;

import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains a service's outbox table and forwards pending rows to a bus.
 *
 * <p>This class is generic over service: each service wires it as a Spring
 * bean and provides its own {@link OutboxPort} (which reads/writes the
 * service's specific outbox table) and an {@link EventPublisher} (which knows
 * how to put bytes on the bus — Kafka in production, an in-process dispatcher
 * in showcase mode).
 *
 * <p>Polling cadence is set via the {@link Scheduled} annotation in the
 * service's @Configuration that creates this bean. The default in this base
 * class is one second.
 *
 * <p>The publisher uses the service's own {@code sequence_number} as the
 * polling cursor — never {@code created_at}. See v3 schema commentary for why.
 *
 * <p><b>§1D.2 — trace propagation:</b> each pending row is published inside a
 * Micrometer {@link Observation} named {@code outbox.publish}. While the
 * observation is open, {@link #currentTraceparent()} reads the active span's
 * {@link TraceContext} and stamps a W3C
 * {@code 00-<traceId>-<spanId>-<flags>} string into the envelope's
 * {@link EventEnvelope#HEADER_TRACEPARENT} header — so the BFF events
 * aggregator can render a trace-drilldown affordance without parsing Kafka
 * headers. Spring Kafka's observation-enabled producer (see
 * {@code spring.kafka.template.observation-enabled} in
 * {@code application-kafka.yml}) writes the same trace context onto the Kafka
 * record headers, and the listener-side observation continues the trace in
 * the consumer service.
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxPort outbox;
    private final EventPublisher bus;
    private final String serviceName;
    private final Tracer tracer;
    private final ObservationRegistry observationRegistry;

    public OutboxPublisher(
        OutboxPort outbox,
        EventPublisher bus,
        String serviceName,
        Tracer tracer,
        ObservationRegistry observationRegistry
    ) {
        this.outbox = outbox;
        this.bus = bus;
        this.serviceName = serviceName;
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
        this.observationRegistry = observationRegistry == null ? ObservationRegistry.NOOP : observationRegistry;
    }

    @Scheduled(fixedDelayString = "${northwood.outbox.poll-interval:1000}")
    @Transactional
    public void drain() {
        List<OutboxRow> batch = outbox.findPending(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("[{}] draining {} outbox rows", serviceName, batch.size());

        for (OutboxRow row : batch) {
            publishOne(row);
        }
    }

    private void publishOne(OutboxRow row) {
        Observation.createNotStarted("outbox.publish", observationRegistry)
            .lowCardinalityKeyValue("service", serviceName)
            .lowCardinalityKeyValue("eventType", row.getEventType())
            .observe(() -> {
                try {
                    Map<String, String> headers = new HashMap<>(2);
                    headers.put(EventEnvelope.HEADER_SOURCE_SERVICE, serviceName);
                    String traceparent = currentTraceparent();
                    if (traceparent != null) {
                        headers.put(EventEnvelope.HEADER_TRACEPARENT, traceparent);
                    }
                    EventEnvelope envelope = new EventEnvelope(
                        row.getOutboxMessageId(),
                        row.getAggregateType(),
                        row.getAggregateId(),
                        row.getEventType(),
                        row.getEventVersion(),
                        row.getPayload(),
                        Map.copyOf(headers),
                        row.getCorrelationId(),
                        row.getCausationId(),
                        row.getActorUserId(),
                        row.getCreatedAt()
                    );
                    bus.publish(envelope);
                    row.markPublished();
                    outbox.save(row);
                } catch (Exception ex) {
                    log.warn("[{}] outbox row {} failed: {}",
                        serviceName, row.getOutboxMessageId(), ex.getMessage());
                    row.markFailed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    outbox.save(row);
                    // Continue draining other rows; failed rows pick up on the next
                    // tick if/when the publisher recovers.
                }
            });
    }

    private String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext ctx = span.context();
        if (ctx == null || ctx.traceId() == null || ctx.spanId() == null) {
            return null;
        }
        String flags = Boolean.TRUE.equals(ctx.sampled()) ? "01" : "00";
        return "00-" + ctx.traceId() + "-" + ctx.spanId() + "-" + flags;
    }
}
