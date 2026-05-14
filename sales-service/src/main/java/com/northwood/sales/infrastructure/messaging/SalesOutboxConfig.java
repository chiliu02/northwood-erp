package com.northwood.sales.infrastructure.messaging;

import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.infrastructure.outbox.OutboxPublisher;
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

    @Bean
    public OutboxPublisher salesOutboxPublisher(
        OutboxPort outboxPort,
        EventPublisher eventPublisher
    ) {
        return new OutboxPublisher(outboxPort, eventPublisher, SERVICE_NAME);
    }
}
