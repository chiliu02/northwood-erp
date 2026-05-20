package com.northwood.finance.infrastructure.messaging;

import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.infrastructure.outbox.OutboxPublisher;
import com.northwood.shared.application.outbox.OutboxPort;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Per-service outbox wiring for finance-service. Carries
 * {@code SupplierInvoiceApproved} (phase 4) and, in later phases,
 * customer-invoice + payment events. Active only under
 * {@code @Profile("kafka")}.
 */
@Configuration
@Profile("kafka")
public class FinanceOutboxConfig {

    private static final String SERVICE_NAME = "finance";

    @Bean
    public OutboxPublisher financeOutboxPublisher(
        OutboxPort outboxPort,
        EventPublisher eventPublisher,
        Tracer tracer,
        ObservationRegistry observationRegistry
    ) {
        return new OutboxPublisher(outboxPort, eventPublisher, SERVICE_NAME, tracer, observationRegistry);
    }
}
