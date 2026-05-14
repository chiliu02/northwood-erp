package com.northwood.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Backend-for-Frontend for the Northwood demo SPA. Sits in front of the seven
 * services and gives the SPA a single origin (port 8080) plus an aggregated
 * saga SSE channel. No DB, no domain — composes, fans out, translates only.
 *
 * <p>{@code @EnableKafka} is required so the {@code @KafkaListener} on
 * {@code EventsAggregatorController} is actually wired up under
 * {@code SPRING_PROFILES_ACTIVE=kafka}. The shared module's
 * {@code KafkaMessagingAutoConfiguration} carries the same annotation for the
 * services; this BFF doesn't depend on shared, so it declares the annotation
 * itself. Without it the SSE endpoint still responds 200, the SPA connects
 * cleanly — but Kafka events never push through (Event log + bottom Event
 * stream both stay at "Waiting for events…").
 */
@SpringBootApplication
@EnableScheduling
@EnableKafka
@EnableConfigurationProperties(BffTargets.class)
public class BffApplication {
    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
