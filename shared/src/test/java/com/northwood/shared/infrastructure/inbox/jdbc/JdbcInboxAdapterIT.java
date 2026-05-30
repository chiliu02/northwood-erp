package com.northwood.shared.infrastructure.inbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.shared.application.inbox.InboxRow;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.25 Tier 1: real-Postgres test for {@link JdbcInboxAdapter}, the shared
 * idempotency-dedup adapter reused by all 7 services. Covers what a mocked
 * unit test cannot:
 *
 * <ul>
 *   <li>{@code alreadyProcessed} against a real {@code COUNT(*)} — false for an
 *       unseen message, true once {@code recordProcessed} has landed the row;</li>
 *   <li>dedup is keyed on {@code (message_id, consumer_name)} — the same message
 *       seen by a different consumer is still "unprocessed" for that consumer
 *       (the property that lets every service consume the same event);</li>
 *   <li>{@code recordProcessed} persists all columns and casts {@code payload}
 *       via {@code ?::jsonb};</li>
 *   <li>the default {@link AdvisoryLockInboxDedupStrategy} actually serializes a
 *       concurrent duplicate — two real transactions racing the same
 *       {@code (message_id, consumer_name)} can't both observe "not processed"
 *       (the race the in-process test harness can't exercise).</li>
 * </ul>
 *
 * <p>Runs against the {@code product} schema; the adapter is service-agnostic
 * (unqualified {@code inbox_message} resolves via {@code search_path}).
 */
class JdbcInboxAdapterIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcInboxAdapter ADAPTER;

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = product, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        ADAPTER = new JdbcInboxAdapter(JDBC, new AdvisoryLockInboxDedupStrategy(JDBC));
    }

    private static void applySqlFile(Path file) {
        String sql;
        try {
            sql = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file.toAbsolutePath(), e);
        }
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply " + file.getFileName(), e);
        }
    }

    @BeforeEach
    void clearInbox() {
        JDBC.execute("TRUNCATE product.inbox_message");
    }

    @Test
    void alreadyProcessed_is_false_for_an_unseen_message() {
        assertThat(ADAPTER.alreadyProcessed(UUID.randomUUID(), "product.SomeHandler")).isFalse();
    }

    @Test
    void recordProcessed_then_alreadyProcessed_is_true() {
        UUID messageId = UUID.randomUUID();
        String consumer = "product.ProductMaterialsCostComputedHandler";

        ADAPTER.recordProcessed(InboxRow.processed(
            UUID.randomUUID(), messageId, consumer,
            "manufacturing.ProductMaterialsCostComputed", 1, 7L, "{}"
        ));

        assertThat(ADAPTER.alreadyProcessed(messageId, consumer)).isTrue();
    }

    @Test
    void dedup_is_keyed_per_consumer() {
        UUID messageId = UUID.randomUUID();
        ADAPTER.recordProcessed(InboxRow.processed(
            UUID.randomUUID(), messageId, "consumer.A",
            "product.SomeEvent", 1, null, "{}"
        ));

        assertThat(ADAPTER.alreadyProcessed(messageId, "consumer.A")).isTrue();
        assertThat(ADAPTER.alreadyProcessed(messageId, "consumer.B")).isFalse();
    }

    @Test
    void recordProcessed_persists_all_columns_and_casts_payload_jsonb() {
        UUID messageId = UUID.randomUUID();
        String consumer = "product.SomeHandler";
        String payload = "{\"productId\":\"abc\",\"cost\":42.5}";

        ADAPTER.recordProcessed(InboxRow.processed(
            UUID.randomUUID(), messageId, consumer,
            "manufacturing.ProductMaterialsCostComputed", 2, 99L, payload
        ));

        Map<String, Object> row = JDBC.queryForMap(
            """
            SELECT event_type, event_version, source_sequence_number,
                   payload::text AS payload_text, status, processed_at
            FROM product.inbox_message
            WHERE message_id = ? AND consumer_name = ?
            """,
            messageId, consumer
        );

        assertThat(row.get("event_type")).isEqualTo("manufacturing.ProductMaterialsCostComputed");
        assertThat(row.get("event_version")).isEqualTo(2);
        assertThat(((Number) row.get("source_sequence_number")).longValue()).isEqualTo(99L);
        assertThat(row.get("status")).isEqualTo("processed");
        assertThat(row.get("processed_at")).isNotNull();
        assertThat(JSON.readTree((String) row.get("payload_text"))).isEqualTo(JSON.readTree(payload));
    }

    /**
     * Two transactions race to process the same {@code (message_id, consumer_name)}.
     * {@code first} acquires the advisory lock and records the row but does <em>not</em>
     * commit; {@code second} then calls {@code alreadyProcessed} — which must block on
     * the lock until {@code first} commits, and only then read a fresh snapshot showing
     * the row. The proof is {@code second} observing {@code true}: without the lock, its
     * {@code EXISTS} would run against {@code first}'s uncommitted insert and return
     * {@code false}, double-applying the event.
     */
    @Test
    void advisory_lock_serializes_a_concurrent_duplicate() throws Exception {
        UUID messageId = UUID.randomUUID();
        String consumer = "product.RaceHandler";

        CountDownLatch firstRecorded = new CountDownLatch(1);   // first inserted (uncommitted), holds the lock
        CountDownLatch secondAttempting = new CountDownLatch(1); // second is about to block on the lock
        CountDownLatch releaseFirst = new CountDownLatch(1);     // main → first may commit
        AtomicReference<Boolean> firstSaw = new AtomicReference<>();
        AtomicReference<Boolean> secondSaw = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread first = new Thread(() -> {
            try (var conn = newTxnConnection()) {
                JdbcInboxAdapter adapter = adapterOn(conn);
                firstSaw.set(adapter.alreadyProcessed(messageId, consumer)); // false; takes the lock
                adapter.recordProcessed(InboxRow.processed(
                    UUID.randomUUID(), messageId, consumer, "product.SomeEvent", 1, null, "{}"));
                firstRecorded.countDown();
                await(releaseFirst);
                conn.commit(); // releases the advisory lock
            } catch (Throwable t) {
                error.compareAndSet(null, t);
                firstRecorded.countDown(); // don't strand the other threads on failure
            }
        });

        Thread second = new Thread(() -> {
            try (var conn = newTxnConnection()) {
                JdbcInboxAdapter adapter = adapterOn(conn);
                await(firstRecorded);          // first holds the lock with an uncommitted row
                secondAttempting.countDown();
                secondSaw.set(adapter.alreadyProcessed(messageId, consumer)); // blocks, then true
                conn.commit();
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });

        first.start();
        second.start();
        await(secondAttempting);
        releaseFirst.countDown();
        first.join(10_000);
        second.join(10_000);

        assertThat(error.get()).withFailMessage("worker failed: %s", error.get()).isNull();
        assertThat(firstSaw.get()).isFalse();
        assertThat(secondSaw.get()).isTrue();
    }

    private static java.sql.Connection newTxnConnection() throws SQLException {
        java.sql.Connection c = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        c.setAutoCommit(false);
        return c;
    }

    private static JdbcInboxAdapter adapterOn(java.sql.Connection conn) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
        jdbc.execute("SET search_path = product, shared");
        return new JdbcInboxAdapter(jdbc, new AdvisoryLockInboxDedupStrategy(jdbc));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting on latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting on latch", e);
        }
    }
}
