package com.northwood.manufacturing.infrastructure.messaging;

import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.infrastructure.outbox.OutboxPublisher;
import com.northwood.shared.application.outbox.OutboxPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("kafka")
public class ManufacturingOutboxConfig {

    private static final String SERVICE_NAME = "manufacturing";

    @Bean
    public OutboxPublisher manufacturingOutboxPublisher(
        OutboxPort outboxPort,
        EventPublisher eventPublisher
    ) {
        return new OutboxPublisher(outboxPort, eventPublisher, SERVICE_NAME);
    }
}
