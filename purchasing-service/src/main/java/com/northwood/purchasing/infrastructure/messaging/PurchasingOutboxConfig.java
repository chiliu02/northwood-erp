package com.northwood.purchasing.infrastructure.messaging;

import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.infrastructure.outbox.OutboxPublisher;
import com.northwood.shared.application.outbox.OutboxPort;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Per-service outbox wiring for purchasing-service. Purchasing's outbox
 * carries {@code PurchaseRequisitionCreated} (phase 1) and, once phase 2
 * lands, {@code PurchaseOrderCreated}. Active only under
 * {@code @Profile("kafka")}.
 */
@Configuration
@Profile("kafka")
public class PurchasingOutboxConfig {

    private static final String SERVICE_NAME = "purchasing";

    @Bean
    public OutboxPublisher purchasingOutboxPublisher(
        OutboxPort outboxPort,
        EventPublisher eventPublisher,
        Tracer tracer,
        ObservationRegistry observationRegistry
    ) {
        return new OutboxPublisher(outboxPort, eventPublisher, SERVICE_NAME, tracer, observationRegistry);
    }
}
