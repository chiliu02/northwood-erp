package com.northwood.shared.infrastructure.outbox;

import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
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
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxPort outbox;
    private final EventPublisher bus;
    private final String serviceName;

    public OutboxPublisher(OutboxPort outbox, EventPublisher bus, String serviceName) {
        this.outbox = outbox;
        this.bus = bus;
        this.serviceName = serviceName;
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
            try {
                EventEnvelope envelope = new EventEnvelope(
                    row.getOutboxMessageId(),
                    row.getAggregateType(),
                    row.getAggregateId(),
                    row.getEventType(),
                    row.getEventVersion(),
                    row.getPayload(),
                    Map.of(EventEnvelope.HEADER_SOURCE_SERVICE, serviceName),
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
        }
    }
}
