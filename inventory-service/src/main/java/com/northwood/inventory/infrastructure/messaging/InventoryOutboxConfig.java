package com.northwood.inventory.infrastructure.messaging;

import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.infrastructure.outbox.OutboxPublisher;
import com.northwood.shared.application.outbox.OutboxPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Per-service outbox wiring for inventory-service. Inventory's outbox carries
 * its replies to sales (StockReserved, eventually StockReservationFailed) and
 * any inventory-originated events later. Active only under
 * {@code @Profile("kafka")}.
 */
@Configuration
@Profile("kafka")
public class InventoryOutboxConfig {

    private static final String SERVICE_NAME = "inventory";

    @Bean
    public OutboxPublisher inventoryOutboxPublisher(
        OutboxPort outboxPort,
        EventPublisher eventPublisher
    ) {
        return new OutboxPublisher(outboxPort, eventPublisher, SERVICE_NAME);
    }
}
