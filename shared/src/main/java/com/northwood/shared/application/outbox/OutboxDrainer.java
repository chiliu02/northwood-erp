package com.northwood.shared.application.outbox;

import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.EventPublisher;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains a service's outbox table and forwards pending rows to the bus — the
 * background half of the outbox pattern (the foreground half is
 * {@link OutboxAppender}, which writes rows inside a use case). Pure
 * orchestration over two application ports: reads pending rows via
 * {@link OutboxPort}, publishes each through {@link EventPublisher}, and marks
 * the row published / failed. No concrete transport lives here — Kafka vs
 * in-process is behind {@link EventPublisher}, the DB behind {@link OutboxPort}.
 *
 * <p>Driven on a fixed schedule by an infrastructure-side scheduler: the
 * {@code @Scheduled} trigger lives there, not on this class, so the drain
 * orchestration stays free of framework-scheduling coupling. Each producer
 * service wires one drainer + one scheduler bean under {@code @Profile("kafka")}.
 *
 * <p>Uses the service's own {@code sequence_number} as the polling cursor —
 * never {@code created_at}. See {@code db/northwood_erp.sql} schema commentary
 * for why. Publish is synchronous per row: a row is marked {@code published}
 * only after the bus acknowledges, otherwise {@code failed} (with the error)
 * for retry on the next tick.
 *
 * <p><b>The {@code @Transactional} on {@link #drain()} is load-bearing — Risk 1.</b>
 * It holds {@code findPending}'s {@code FOR UPDATE SKIP LOCKED} row locks for the
 * whole batch, which is the property that lets multiple drainer workers run
 * safely (a sibling worker's {@code SKIP LOCKED} skips rows this transaction
 * still holds). It only takes effect when {@code drain()} is invoked as an
 * <em>external</em> call through the Spring proxy — i.e. from a <em>different</em>
 * bean ({@code OutboxDrainScheduler}). Never self-invoke it, and never construct
 * an {@code OutboxDrainer} outside Spring: a non-proxied instance runs with no
 * transaction, the {@code SELECT … FOR UPDATE} locks release at statement end
 * (auto-commit), and concurrent drains can double-publish. This is why the
 * drainer and the scheduler are two beans, not one.
 */
public class OutboxDrainer {

    private static final Logger log = LoggerFactory.getLogger(OutboxDrainer.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxPort outbox;
    private final EventPublisher bus;
    private final String serviceName;

    public OutboxDrainer(OutboxPort outbox, EventPublisher bus, String serviceName) {
        this.outbox = outbox;
        this.bus = bus;
        this.serviceName = serviceName;
    }

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
                outbox.update(row);
            } catch (Exception ex) {
                log.warn("[{}] outbox row {} failed: {}",
                    serviceName, row.getOutboxMessageId(), ex.getMessage());
                row.markFailed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                outbox.update(row);
                // Continue draining other rows; failed rows pick up on the next
                // tick if/when the bus recovers.
            }
        }
    }
}
