package com.northwood.finance.infrastructure.messaging;

import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.application.outbox.OutboxDrainer;
import com.northwood.shared.infrastructure.messaging.OutboxDrainScheduler;
import com.northwood.shared.application.outbox.OutboxPort;
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

    // Two beans, not one: merging silently drops drain()'s @Transactional + the
    // FOR UPDATE SKIP LOCKED batch lock. See OutboxDrainScheduler.
    @Bean
    public OutboxDrainer financeOutboxDrainer(
        OutboxPort outboxPort,
        EventPublisher eventPublisher
    ) {
        return new OutboxDrainer(outboxPort, eventPublisher, SERVICE_NAME);
    }

    @Bean
    public OutboxDrainScheduler financeOutboxDrainScheduler(OutboxDrainer drainer) {
        return new OutboxDrainScheduler(drainer);
    }
}
