package com.northwood.manufacturing.infrastructure.messaging;

import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.application.outbox.OutboxDrainer;
import com.northwood.shared.infrastructure.messaging.OutboxDrainScheduler;
import com.northwood.shared.application.outbox.OutboxPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("kafka")
public class ManufacturingOutboxConfig {

    private static final String SERVICE_NAME = "manufacturing";

    // Two beans, not one: merging silently drops drain()'s @Transactional + the
    // FOR UPDATE SKIP LOCKED batch lock. See OutboxDrainScheduler.
    @Bean
    public OutboxDrainer manufacturingOutboxDrainer(
        OutboxPort outboxPort,
        EventPublisher eventPublisher
    ) {
        return new OutboxDrainer(outboxPort, eventPublisher, SERVICE_NAME);
    }

    @Bean
    public OutboxDrainScheduler manufacturingOutboxDrainScheduler(OutboxDrainer drainer) {
        return new OutboxDrainScheduler(drainer);
    }
}
