package com.northwood.shared.infrastructure.messaging;

import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.application.outbox.OutboxDrainer;
import com.northwood.shared.application.outbox.OutboxPort;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires a producer service's outbox drain — {@link OutboxDrainer} (the
 * {@code @Transactional} drain orchestration) plus {@link OutboxDrainScheduler}
 * (the {@code @Scheduled} trigger) — from properties, so every producer gets
 * both without a hand-written {@code <Service>OutboxConfig}. Replaces the six
 * near-identical per-service config classes; the only per-service input is now
 * the {@code northwood.service-name} value.
 *
 * <p><b>Gated on {@code northwood.outbox.drain.enabled=true}</b>, which each
 * producer sets only in {@code application-kafka.yml}. Under the default
 * {@code dev} profile that file isn't loaded, the property is absent, and no
 * drain beans are created (the outbox accumulates undrained — the correct
 * single-JVM showcase behaviour). Property gating rather than
 * {@code @Profile("kafka")} because {@code @Profile} on {@code @AutoConfiguration}
 * is unreliable on Spring Boot 4 (see {@code ~/.claude/notes/spring-boot-4.md});
 * {@code @ConditionalOnProperty} is the sanctioned autoconfig gate.
 *
 * <p>{@code northwood.service-name} (e.g. {@code product}) stamps the
 * {@code source-service} header / derives the Kafka topic. Injected with
 * <em>no default</em>, so a producer that enables draining but forgets the name
 * fails fast at startup rather than publishing a blank source.
 *
 * <p>Two beans, deliberately not one — see {@link OutboxDrainScheduler} for why
 * merging them would drop {@code drain()}'s transaction + the
 * {@code FOR UPDATE SKIP LOCKED} batch lock. {@code @ConditionalOnMissingBean}
 * lets a service (or a test) substitute its own.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "northwood.outbox.drain", name = "enabled", havingValue = "true")
public class OutboxDrainAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OutboxDrainer.class)
    public OutboxDrainer outboxDrainer(
        OutboxPort outboxPort,
        EventPublisher eventPublisher,
        @Value("${northwood.service-name}") String serviceName,
        ObjectProvider<Tracer> tracer,
        ObjectMapper objectMapper
    ) {
        // The drainer links each publish span to the originating request
        // trace (span-link; captured into each row's headers at append time).
        // Tracer is resolved via ObjectProvider so a producer that enables
        // draining without the observability stack still starts (the drainer
        // falls back to the NOOP tracer in its constructor → no link).
        // ObjectMapper parses the trace context captured into each row's headers.
        return new OutboxDrainer(
            outboxPort,
            eventPublisher,
            serviceName,
            tracer.getIfAvailable(),
            objectMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean(OutboxDrainScheduler.class)
    public OutboxDrainScheduler outboxDrainScheduler(OutboxDrainer drainer) {
        return new OutboxDrainScheduler(drainer);
    }
}
