package com.northwood.inventory;

import com.northwood.inventory.application.inbox.ReorderPolicyChangedHandler;
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
 * Consumer-idempotency e2e for the inbox dedup gate
 * ({@code docs/messaging.md} → <em>Reliability &amp; idempotency</em> →
 * "Duplicate delivery … applied exactly once"): a redelivery carrying the same
 * {@code eventId} is applied once.
 *
 * <p>Three {@code product.ReorderPolicyChanged} envelopes are published to
 * {@code product.events}, <em>all keyed by the same {@code aggregateId}</em> so
 * Kafka routes them to one partition and the listener processes them strictly
 * in order on a single thread:
 * <ol>
 *   <li><b>A</b> — first delivery of {@code eventId E} (point/qty 11/22);</li>
 *   <li><b>B</b> — a duplicate carrying the same {@code eventId E} but
 *       <em>different</em> values (33/44) — the producer-re-publish scenario
 *       (crash after broker ack, before the {@code published} mark). The gate
 *       must skip it; the distinct values make a wrongly-applied B detectable;</li>
 *   <li><b>marker</b> — a fresh {@code eventId} (point/qty 55/66) whose effect
 *       is the test's synchronisation point: once 55 lands, A and B have both
 *       been fully processed (same partition, in-order, single-threaded), so
 *       asserting on B is no longer racy against "B not delivered yet".</li>
 * </ol>
 *
 * <p>The dedup gate ({@code AbstractInboxHandler.handle} →
 * {@code InboxPort.alreadyProcessed}) must short-circuit B, leaving exactly one
 * inbox row for {@code E}. The partitioned {@code inbox_message}'s
 * {@code UNIQUE (message_id, consumer_name, processed_at)} does <em>not</em>
 * enforce this (a second insert at a later instant succeeds — see
 * {@code docs/messaging.md} → Consumer-side idempotency), so a count of 1
 * proves the <em>gate</em> skipped B, not a constraint backstop.
 *
 * <p>Container + bootstrap shape mirrors {@link ReorderPolicyChangedSeamIT} —
 * see its Javadoc for the manual-lifecycle rationale (Testcontainers 1.20.x
 * {@code withInitScript} bug) and the single-broker KRaft defaults.
 */
@SpringBootTest
@ActiveProfiles("kafka")
class DuplicateDeliveryAppliedOnceIT {

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
        try (InputStream in = DuplicateDeliveryAppliedOnceIT.class
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
        r.add("northwood.kafka.subscribe-topics", () -> TOPIC);
        r.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void duplicateDelivery_isAppliedExactlyOnce() {
        UUID duplicatedEventId = UUID.randomUUID();
        UUID markerEventId = UUID.randomUUID();

        // A — first delivery of E.
        publish(duplicatedEventId, new BigDecimal("11"), new BigDecimal("22"));
        // B — duplicate of E with different values; the gate must skip it.
        publish(duplicatedEventId, new BigDecimal("33"), new BigDecimal("44"));
        // marker — fresh eventId; once 55 lands, A and B have both been processed.
        publish(markerEventId, new BigDecimal("55"), new BigDecimal("66"));

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            BigDecimal point = jdbc.queryForObject(
                "SELECT reorder_point FROM inventory.product_card WHERE product_id = ?",
                BigDecimal.class, SEED_PRODUCT_ID
            );
            assertThat(point).isEqualByComparingTo(new BigDecimal("55"));
        });

        // The duplicate eventId is recorded exactly once → the gate skipped B
        // (a wrongly-applied B would have inserted a second inbox row).
        Integer duplicateInboxRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory.inbox_message WHERE message_id = ? AND consumer_name = ?",
            Integer.class, duplicatedEventId, ReorderPolicyChangedHandler.CONSUMER_NAME
        );
        assertThat(duplicateInboxRows)
            .withFailMessage("duplicate eventId must be recorded exactly once (the dedup gate skips the redelivery)")
            .isEqualTo(1);

        // Sanity: the marker (distinct eventId) was itself recorded once and the
        // listener kept consuming past the deduped duplicate.
        Integer markerInboxRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory.inbox_message WHERE message_id = ? AND consumer_name = ?",
            Integer.class, markerEventId, ReorderPolicyChangedHandler.CONSUMER_NAME
        );
        assertThat(markerInboxRows).isEqualTo(1);
    }

    private void publish(UUID eventId, BigDecimal reorderPoint, BigDecimal reorderQuantity) {
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
                eventId, SEED_PRODUCT_ID, reorderPoint, reorderQuantity, Instant.now()
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
    }
}
