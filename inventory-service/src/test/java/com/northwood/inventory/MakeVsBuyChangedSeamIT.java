package com.northwood.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.northwood.product.domain.events.MakeVsBuyChanged;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.io.IOException;
import java.io.InputStream;
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
 * §2.35 Slice A seam test for the inventory-side projection of
 * {@code product.MakeVsBuyChanged}. Mirrors {@link ReorderPolicyChangedSeamIT}
 * — drives a Kafka envelope onto {@code product.events} and asserts that
 * inventory's {@code @KafkaListener}, dispatcher, and
 * {@link com.northwood.inventory.application.inbox.MakeVsBuyChangedHandler}
 * land the new flags on {@code inventory.product_card}, and that
 * the inbox row records the message_id for redelivery dedup.
 *
 * <p>Container lifecycle + bootstrap rationale: see
 * {@link ReorderPolicyChangedSeamIT} class Javadoc.
 */
@SpringBootTest
@ActiveProfiles("kafka")
class MakeVsBuyChangedSeamIT {

    private static final UUID SEED_PRODUCT_ID =
        UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final String EVENT_TYPE = MakeVsBuyChanged.EVENT_TYPE;
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
        try (InputStream in = MakeVsBuyChangedSeamIT.class
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
    void makeVsBuyChanged_fromKafka_updatesProductCardProjection() {
        UUID eventId = UUID.randomUUID();

        // FG-TABLE-001 is seeded as a finished_good — make-only by default.
        // Flip to vertically-integrated (both true): exercises the upsert UPDATE
        // path on a row that the seed-from-ProductCreated would have inserted
        // in production. The seed didn't run in this test (no ProductCreated
        // event arrived), so this acts as INSERT here — that's intentional:
        // tolerates out-of-order delivery (MakeVsBuyChanged before
        // ProductCreated's seed).
        String payloadJson = """
            {
              "eventId": "%s",
              "aggregateId": "%s",
              "oldIsPurchased": false,
              "newIsPurchased": true,
              "oldIsManufactured": true,
              "newIsManufactured": true,
              "occurredAt": "%s"
            }
            """.formatted(eventId, SEED_PRODUCT_ID, Instant.now());

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

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            Boolean purchased = jdbc.queryForObject(
                "SELECT is_purchased FROM inventory.product_card WHERE product_id = ?",
                Boolean.class, SEED_PRODUCT_ID
            );
            Boolean manufactured = jdbc.queryForObject(
                "SELECT is_manufactured FROM inventory.product_card WHERE product_id = ?",
                Boolean.class, SEED_PRODUCT_ID
            );
            assertThat(purchased).isTrue();
            assertThat(manufactured).isTrue();
        });

        Integer inboxCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory.inbox_message WHERE message_id = ? AND consumer_name = ?",
            Integer.class, eventId, "inventory.product-replenishment-projector"
        );
        assertThat(inboxCount).isEqualTo(1);
    }
}
