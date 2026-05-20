package com.northwood.product.infrastructure.messaging;

import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.infrastructure.outbox.OutboxPublisher;
import com.northwood.shared.application.outbox.OutboxPort;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the per-service {@link OutboxPublisher} for product-service. Active
 * only under {@code @Profile("kafka")}: under the default {@code dev} profile
 * we deliberately do not drain the outbox — there is no consumer side, so
 * letting events accumulate in {@code product.outbox_message} is the correct
 * single-JVM behaviour. When the testbench shifts to dual-JVM in-process bus
 * later we can broaden the profile activation.
 */
@Configuration
@Profile("kafka")
public class ProductOutboxConfig {

    private static final String SERVICE_NAME = "product";

    @Bean
    public OutboxPublisher productOutboxPublisher(
        OutboxPort outboxPort,
        EventPublisher eventPublisher,
        Tracer tracer,
        ObservationRegistry observationRegistry
    ) {
        return new OutboxPublisher(outboxPort, eventPublisher, SERVICE_NAME, tracer, observationRegistry);
    }
}
