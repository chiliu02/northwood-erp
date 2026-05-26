package com.northwood.sales.infrastructure.messaging;

import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.application.outbox.OutboxDrainer;
import com.northwood.shared.infrastructure.messaging.OutboxDrainScheduler;
import com.northwood.shared.application.outbox.OutboxPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Per-service outbox wiring for sales-service. Active only under
 * {@code @Profile("kafka")}: under {@code dev} the outbox accumulates with no
 * drain, mirroring product-service's setup.
 */
@Configuration
@Profile("kafka")
public class SalesOutboxConfig {

    private static final String SERVICE_NAME = "sales";

    // Two beans, not one: merging silently drops drain()'s @Transactional + the
    // FOR UPDATE SKIP LOCKED batch lock. See OutboxDrainScheduler.
    @Bean
    public OutboxDrainer salesOutboxDrainer(
        OutboxPort outboxPort,
        EventPublisher eventPublisher
    ) {
        return new OutboxDrainer(outboxPort, eventPublisher, SERVICE_NAME);
    }

    @Bean
    public OutboxDrainScheduler salesOutboxDrainScheduler(OutboxDrainer drainer) {
        return new OutboxDrainScheduler(drainer);
    }
}
