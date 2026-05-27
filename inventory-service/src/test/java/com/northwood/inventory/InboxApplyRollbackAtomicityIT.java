package com.northwood.inventory;

import com.northwood.product.domain.events.ReorderPolicyChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
 * Consumer-atomicity e2e for the inbox {@code @Transactional} boundary
 * (dev-todo §2.27 item 1; {@code docs/messaging.md} → <em>Reliability &amp;
 * idempotency</em> → "{@code apply} + {@code recordProcessed} atomic"): when a
 * handler's {@code apply} throws after a partial write, the whole
 * {@code handle()} transaction rolls back — neither the partial projection
 * write nor an inbox row survives — and the redelivery applies it exactly once.
 *
 * <p>A test-only {@link RollbackProbeHandler} (registered via the nested
 * {@link ProbeConfig}) handles a private {@code test.RollbackProbe} event type
 * so it never collides with the real {@code ReorderPolicyChanged} handler. On
 * the <b>first</b> delivery it writes a sentinel ({@code 111}) to the seed
 * {@code stock_item} row and then throws; on the <b>redelivery</b> it writes
 * the success value ({@code 222}) and returns normally so
 * {@code AbstractInboxHandler.handle} records the inbox row.
 *
 * <p>The probe's first-attempt failure throws a <em>retryable</em>
 * {@code RuntimeException} so the §2.28 Tier 1.A classify-and-backoff error
 * handler re-seeks (redelivers) it — a non-retryable (poison) exception would
 * correctly dead-letter instead, and this test is about reprocess-on-redelivery.
 *
 * <p>Two {@link CountDownLatch}es make the rollback observable deterministically
 * (the error handler redelivers after its backoff, so without gating the
 * success commit would race the assertion):
 * <ul>
 *   <li>{@code firstAttemptFailed} — counted down just before the first throw,
 *       so the test knows the failing attempt has happened;</li>
 *   <li>{@code allowRedelivery} — the redelivery blocks on this <em>before</em>
 *       its success write, so the test can assert the post-rollback state
 *       (seed value intact, zero inbox rows) before releasing it.</li>
 * </ul>
 *
 * <p>The post-failure read uses a separate connection (the autowired
 * {@code JdbcTemplate}); under {@code READ COMMITTED} it never sees the failing
 * attempt's uncommitted sentinel, so the assertion holds regardless of rollback
 * timing — what it proves is that nothing from the failed attempt was ever
 * <em>committed</em>.
 *
 * <p>Container + bootstrap shape mirrors {@link ReorderPolicyChangedSeamIT} —
 * see its Javadoc for the manual-lifecycle rationale.
 */
@SpringBootTest
@ActiveProfiles("kafka")
class InboxApplyRollbackAtomicityIT {

    private static final UUID SEED_PRODUCT_ID =
        UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final BigDecimal SEED_REORDER_POINT = new BigDecimal("2");
    private static final BigDecimal SENTINEL_VALUE = new BigDecimal("111");
    private static final BigDecimal SUCCESS_VALUE = new BigDecimal("222");
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
        try (InputStream in = InboxApplyRollbackAtomicityIT.class
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
    @Autowired ProbeState probe;

    @Test
    void applyThrows_rollsBackAtomically_thenAppliesOnceOnRedelivery() throws Exception {
        UUID eventId = UUID.randomUUID();
        publishProbe(eventId);

        // (1) The failing first attempt has written its sentinel and thrown.
        assertThat(probe.firstAttemptFailed.await(20, TimeUnit.SECONDS))
            .withFailMessage("probe handler's first attempt never ran").isTrue();

        // Post-failure, pre-redelivery: the @Transactional rollback means neither
        // the sentinel write (111) nor an inbox row was committed. The redelivery
        // is still parked on allowRedelivery, so its success write can't interfere.
        BigDecimal pointAfterFailure = jdbc.queryForObject(
            "SELECT reorder_point FROM inventory.stock_item WHERE product_id = ?",
            BigDecimal.class, SEED_PRODUCT_ID
        );
        assertThat(pointAfterFailure)
            .withFailMessage("partial write must have rolled back to the seed value, was %s", pointAfterFailure)
            .isEqualByComparingTo(SEED_REORDER_POINT);

        Integer inboxAfterFailure = jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory.inbox_message WHERE message_id = ? AND consumer_name = ?",
            Integer.class, eventId, RollbackProbeHandler.CONSUMER_NAME
        );
        assertThat(inboxAfterFailure)
            .withFailMessage("a failed apply must not leave an inbox row").isZero();

        // (2) Release the redelivery → the success write + inbox row commit atomically.
        probe.allowRedelivery.countDown();

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            BigDecimal point = jdbc.queryForObject(
                "SELECT reorder_point FROM inventory.stock_item WHERE product_id = ?",
                BigDecimal.class, SEED_PRODUCT_ID
            );
            assertThat(point).isEqualByComparingTo(SUCCESS_VALUE);
        });

        Integer inboxFinal = jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory.inbox_message WHERE message_id = ? AND consumer_name = ?",
            Integer.class, eventId, RollbackProbeHandler.CONSUMER_NAME
        );
        assertThat(inboxFinal)
            .withFailMessage("redelivery must record exactly one inbox row").isEqualTo(1);

        // The handler ran twice (fail, then success) but applied its effect once.
        assertThat(probe.attempts.get()).isEqualTo(2);
    }

    private void publishProbe(UUID eventId) {
        String payloadJson = """
            {
              "eventId": "%s",
              "aggregateId": "%s",
              "oldReorderPoint": 2.0000,
              "newReorderPoint": 222.0000,
              "oldReorderQuantity": 5.0000,
              "newReorderQuantity": 5.0000,
              "occurredAt": "%s"
            }
            """.formatted(eventId, SEED_PRODUCT_ID, Instant.now());

        EventEnvelope envelope = new EventEnvelope(
            eventId,
            "Product",
            SEED_PRODUCT_ID,
            RollbackProbeHandler.EVENT_TYPE,
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

    /** Shared latches/counter between the test thread and the probe handler. */
    static final class ProbeState {
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch firstAttemptFailed = new CountDownLatch(1);
        final CountDownLatch allowRedelivery = new CountDownLatch(1);
    }

    /**
     * Inbox handler for the private {@code test.RollbackProbe} event. First
     * delivery: sentinel write then throw (the whole {@code handle()} tx must
     * roll back). Redelivery: parks on {@code allowRedelivery}, then writes the
     * success value and returns so the inbox row is recorded.
     */
    static class RollbackProbeHandler extends AbstractInboxHandler<ReorderPolicyChanged> {

        static final String EVENT_TYPE = "test.RollbackProbe";
        static final String CONSUMER_NAME = "inventory.rollback-probe-test";

        private final JdbcTemplate jdbc;
        private final ProbeState state;

        RollbackProbeHandler(InboxPort inbox, ObjectMapper json, JdbcTemplate jdbc, ProbeState state) {
            super(inbox, json, ReorderPolicyChanged.class, EVENT_TYPE, CONSUMER_NAME);
            this.jdbc = jdbc;
            this.state = state;
        }

        @Override
        protected void apply(ReorderPolicyChanged payload, EventEnvelope envelope) {
            int attempt = state.attempts.incrementAndGet();
            if (attempt == 1) {
                jdbc.update(
                    "UPDATE inventory.stock_item SET reorder_point = ? WHERE product_id = ?",
                    SENTINEL_VALUE, payload.aggregateId()
                );
                state.firstAttemptFailed.countDown();
                // Must be a RETRYABLE exception so the §2.28 Tier 1.A error handler
                // re-seeks (redelivers) rather than dead-letters it — a non-retryable
                // (poison) exception like IllegalStateException would correctly go
                // straight to the DLT, and this test is about reprocess-on-redelivery.
                throw new RuntimeException("simulated transient mid-apply failure after a partial write");
            }
            // Redelivery: block until the test has asserted the post-rollback state.
            try {
                if (!state.allowRedelivery.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release the redelivery in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            jdbc.update(
                "UPDATE inventory.stock_item SET reorder_point = ? WHERE product_id = ?",
                SUCCESS_VALUE, payload.aggregateId()
            );
        }
    }

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        ProbeState probeState() {
            return new ProbeState();
        }

        @Bean
        RollbackProbeHandler rollbackProbeHandler(
            InboxPort inbox, ObjectMapper json, JdbcTemplate jdbc, ProbeState state
        ) {
            return new RollbackProbeHandler(inbox, json, jdbc, state);
        }
    }
}
