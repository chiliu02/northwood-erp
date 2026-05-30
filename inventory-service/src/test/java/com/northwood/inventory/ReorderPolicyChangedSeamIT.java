package com.northwood.inventory;

import com.northwood.product.domain.events.ReorderPolicyChanged;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.northwood.shared.application.messaging.EventEnvelope;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end seam test for the Shape A reorder-policy slice.
 *
 * <p>Drives a {@code product.ReorderPolicyChanged} envelope onto the
 * {@code product.events} Kafka topic (simulating product-service's outbox
 * publisher) and asserts that inventory-service's {@code @KafkaListener},
 * dispatcher, and projection handler land the updated values on
 * {@code inventory.product_card}. The inbox row is also asserted to verify
 * idempotency machinery is in place.
 *
 * <p>Containers: a fresh PostgreSQL with a minimal schema bootstrap (just the
 * inventory pieces — see {@code test-bootstrap.sql}) and an Apache Kafka 4.1.2
 * broker in KRaft mode. The {@link KafkaContainer} ships with single-broker
 * defaults ({@code offsets.topic.replication.factor=1} etc.) so the
 * {@code __consumer_offsets} topic auto-creates cleanly — no docker-compose-style
 * env-var overrides needed at this layer.
 *
 * <p>Lifecycle is managed manually rather than via {@code @Testcontainers}
 * because we need to run {@code test-bootstrap.sql} between container start
 * and Spring context init. {@code PostgreSQLContainer.withInitScript} would do
 * that, but Testcontainers 1.20.4's {@code ScriptUtils} references a shaded
 * commons-io class that's missing from the JAR (a known bug).
 */
@SpringBootTest
@ActiveProfiles("kafka")
class ReorderPolicyChangedSeamIT {

    private static final UUID SEED_PRODUCT_ID =
        UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final String EVENT_TYPE = ReorderPolicyChanged.EVENT_TYPE;
    private static final String TOPIC = "product.events";

    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    static final KafkaContainer KAFKA =
        new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.2"));

    static {
        Startables.deepStart(POSTGRES, KAFKA).join();
        runBootstrap();
    }

    private static void runBootstrap() {
        String sql;
        try (InputStream in = ReorderPolicyChangedSeamIT.class
                .getResourceAsStream("/test-bootstrap.sql")) {
            if (in == null) {
                throw new IllegalStateException("test-bootstrap.sql not on classpath");
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read test-bootstrap.sql", e);
        }
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            // The driver tolerates multi-statement scripts; the bootstrap is
            // idempotent (CREATE SCHEMA IF NOT EXISTS, CREATE OR REPLACE
            // FUNCTION) but we only run it once per container.
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply test-bootstrap.sql", e);
        }
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // The dispatcher bean is gated on this property; without it the
        // @KafkaListener is never registered and the test would hang.
        r.add("northwood.kafka.subscribe-topics", () -> TOPIC);
        r.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void reorderPolicyChanged_fromKafka_updatesProductCardProjection() {
        UUID eventId = UUID.randomUUID();
        BigDecimal newReorderPoint = new BigDecimal("11");
        BigDecimal newReorderQuantity = new BigDecimal("22");

        // Build the event envelope as if product-service had emitted it.
        // payloadJson mirrors product-service's ReorderPolicyChanged record's
        // wire shape — inventory deserialises this into the same record now
        // exposed via product-events.
        String payloadJson = """
            {
              "eventId": "%s",
              "aggregateId": "%s",
              "oldReorderPoint": 2.0000,
              "newReorderPoint": %s,
              "oldReorderQuantity": 5.0000,
              "newReorderQuantity": %s,
              "occurredAt": "%s"
            }
            """.formatted(
                eventId, SEED_PRODUCT_ID, newReorderPoint, newReorderQuantity, Instant.now()
            );

        EventEnvelope envelope = new EventEnvelope(
            eventId,
            "Product",
            SEED_PRODUCT_ID,
            EVENT_TYPE,
            1,
            payloadJson,
            Map.of(EventEnvelope.HEADER_SOURCE_SERVICE, "product"),
            null,
            null,
            null,
            Instant.now()
        );

        kafkaTemplate.send(TOPIC, SEED_PRODUCT_ID.toString(), json.writeValueAsString(envelope))
            .join();

        // Poll the projection until the handler has applied the update.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            BigDecimal point = jdbc.queryForObject(
                "SELECT reorder_point FROM inventory.product_card WHERE product_id = ?",
                BigDecimal.class, SEED_PRODUCT_ID
            );
            BigDecimal qty = jdbc.queryForObject(
                "SELECT reorder_quantity FROM inventory.product_card WHERE product_id = ?",
                BigDecimal.class, SEED_PRODUCT_ID
            );
            assertThat(point).isEqualByComparingTo(newReorderPoint);
            assertThat(qty).isEqualByComparingTo(newReorderQuantity);
        });

        // The inbox row records the message_id so a redelivery would short-circuit
        // via InboxPort#alreadyProcessed.
        Integer inboxCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory.inbox_message WHERE message_id = ? AND consumer_name = ?",
            Integer.class, eventId, "inventory.stock-item-projector"
        );
        assertThat(inboxCount).isEqualTo(1);
    }
}
