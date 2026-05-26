package com.northwood.shared.infrastructure.outbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.shared.application.outbox.OutboxRow;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.25 Tier 1: real-Postgres test for {@link JdbcOutboxAdapter}, the shared
 * outbox adapter reused by all 7 services. Covers what a mocked-{@code
 * JdbcTemplate} unit test structurally cannot:
 *
 * <ul>
 *   <li>the {@code appendPending} INSERT + {@code findPending} read round-trip
 *       through {@code OutboxRow.fromDb}, including the {@code ?::jsonb} casts
 *       and the DB-generated {@code sequence_number} / {@code created_at} /
 *       {@code status='pending'} / {@code retry_count} defaults;</li>
 *   <li>the documented null-{@code headers} → {@code '{}'} coercion in
 *       {@code appendPending} (the column is {@code JSONB NOT NULL DEFAULT '{}'},
 *       and the explicit-column INSERT would otherwise trip the NOT NULL);</li>
 *   <li>{@code findPending} ordering by {@code sequence_number}, the {@code
 *       LIMIT}, and the {@code status IN ('pending','failed')} filter;</li>
 *   <li>{@code update} flipping status / retry / last_error / published_at via the
 *       {@code WHERE outbox_message_id = ? AND created_at = ?} clause;</li>
 *   <li><b>{@code FOR UPDATE SKIP LOCKED}</b> — the headline behaviour: a row
 *       locked by a concurrent transaction is skipped, not blocked on.</li>
 * </ul>
 *
 * <p>Runs against the {@code product} schema (the adapter is service-agnostic;
 * SQL is unqualified and resolves via {@code search_path = product, shared}).
 */
class JdbcOutboxAdapterIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcOutboxAdapter ADAPTER;

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
        ADAPTER = new JdbcOutboxAdapter(JDBC);
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
    void clearOutbox() {
        JDBC.execute("TRUNCATE product.outbox_message");
    }

    // ------------------------------------------------------------------
    // appendPending + findPending round-trip
    // ------------------------------------------------------------------

    @Test
    void appendPending_inserts_pending_row_and_findPending_round_trips_every_column() {
        UUID id = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();
        String payload = "{\"sku\":\"FG-IT-1\",\"qty\":3}";
        String headers = "{\"traceparent\":\"00-abc-def-01\"}";

        ADAPTER.appendPending(OutboxRow.pending(
            id, "Product", aggregateId,
            "product.ProductCreated", 1,
            payload, headers, correlationId, causationId, "alice"
        ));

        List<OutboxRow> pending = ADAPTER.findPending(10);
        assertThat(pending).hasSize(1);
        OutboxRow r = pending.get(0);

        assertThat(r.getOutboxMessageId()).isEqualTo(id);
        assertThat(r.getAggregateType()).isEqualTo("Product");
        assertThat(r.getAggregateId()).isEqualTo(aggregateId);
        assertThat(r.getEventType()).isEqualTo("product.ProductCreated");
        assertThat(r.getEventVersion()).isEqualTo(1);
        assertThat(JSON.readTree(r.getPayload())).isEqualTo(JSON.readTree(payload));
        assertThat(JSON.readTree(r.getHeaders())).isEqualTo(JSON.readTree(headers));
        assertThat(r.getCorrelationId()).isEqualTo(correlationId);
        assertThat(r.getCausationId()).isEqualTo(causationId);
        assertThat(r.getActorUserId()).isEqualTo("alice");
        assertThat(r.getStatus()).isEqualTo(OutboxRow.PENDING);
        assertThat(r.getRetryCount()).isZero();
        assertThat(r.getLastError()).isNull();
        // DB-generated.
        assertThat(r.getSequenceNumber()).isNotNull().isPositive();
        assertThat(r.getCreatedAt()).isNotNull();
        assertThat(r.getPublishedAt()).isNull();
    }

    @Test
    void appendPending_coerces_null_headers_to_empty_json_object() {
        ADAPTER.appendPending(OutboxRow.pending(
            UUID.randomUUID(), "Product", UUID.randomUUID(),
            "product.ProductCreated", 1,
            "{}", null, null, null, null
        ));

        OutboxRow r = ADAPTER.findPending(10).get(0);
        assertThat(r.getHeaders()).isNotNull();
        assertThat(JSON.readTree(r.getHeaders())).isEqualTo(JSON.readTree("{}"));
    }

    // ------------------------------------------------------------------
    // findPending — ordering, limit, status filter
    // ------------------------------------------------------------------

    @Test
    void findPending_returns_rows_in_sequence_number_order_respecting_limit() {
        UUID first = appendPending();
        UUID second = appendPending();
        appendPending(); // third — beyond the limit

        List<OutboxRow> pending = ADAPTER.findPending(2);

        assertThat(pending)
            .extracting(OutboxRow::getOutboxMessageId)
            .containsExactly(first, second);
    }

    @Test
    void findPending_includes_failed_but_excludes_published() {
        UUID toPublish = appendPending();
        UUID toFail = appendPending();
        UUID stillPending = appendPending();

        OutboxRow published = findById(toPublish);
        published.markPublished();
        ADAPTER.update(published);

        OutboxRow failed = findById(toFail);
        failed.markFailed("broker unavailable");
        ADAPTER.update(failed);

        List<OutboxRow> pending = ADAPTER.findPending(10);

        assertThat(pending)
            .extracting(OutboxRow::getOutboxMessageId)
            .containsExactlyInAnyOrder(toFail, stillPending)
            .doesNotContain(toPublish);
        // The re-picked failed row carries its retry metadata.
        OutboxRow failedAgain = pending.stream()
            .filter(r -> r.getOutboxMessageId().equals(toFail)).findFirst().orElseThrow();
        assertThat(failedAgain.getRetryCount()).isEqualTo(1);
        assertThat(failedAgain.getLastError()).isEqualTo("broker unavailable");
        assertThat(stillPending).isNotNull();
    }

    // ------------------------------------------------------------------
    // update — status transition columns
    // ------------------------------------------------------------------

    @Test
    void update_marks_published_sets_published_at_and_drops_from_pending() {
        UUID id = appendPending();
        OutboxRow row = findById(id);

        row.markPublished();
        ADAPTER.update(row);

        assertThat(ADAPTER.findPending(10)).isEmpty();
        assertThat(dbStatus(id)).isEqualTo(OutboxRow.PUBLISHED);
        assertThat(dbPublishedAtIsNull(id)).isFalse();
    }

    // ------------------------------------------------------------------
    // FOR UPDATE SKIP LOCKED
    // ------------------------------------------------------------------

    @Test
    void findPending_skips_rows_locked_by_another_transaction() throws SQLException {
        List<UUID> ids = List.of(appendPending(), appendPending(), appendPending());
        UUID lockedA = ids.get(0);
        UUID lockedB = ids.get(1);
        UUID free = ids.get(2);

        try (Connection locker = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            locker.setAutoCommit(false);
            try (PreparedStatement ps = locker.prepareStatement(
                    "SELECT 1 FROM product.outbox_message "
                    + "WHERE outbox_message_id IN (?, ?) FOR UPDATE")) {
                ps.setObject(1, lockedA);
                ps.setObject(2, lockedB);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { /* fetch all rows so both are locked */ }
                }

                // While the locker holds rows A + B, the adapter must skip them
                // and surface only the unlocked row — not block.
                assertThat(ADAPTER.findPending(10))
                    .extracting(OutboxRow::getOutboxMessageId)
                    .containsExactly(free);
            }
            locker.rollback();
        }

        // Locks released → all three are claimable again.
        assertThat(ADAPTER.findPending(10)).hasSize(3);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UUID appendPending() {
        UUID id = UUID.randomUUID();
        ADAPTER.appendPending(OutboxRow.pending(
            id, "Product", UUID.randomUUID(),
            "product.ProductCreated", 1,
            "{}", "{}", null, null, "tester"
        ));
        return id;
    }

    private OutboxRow findById(UUID id) {
        return ADAPTER.findPending(100).stream()
            .filter(r -> r.getOutboxMessageId().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No pending row " + id));
    }

    private String dbStatus(UUID id) {
        return JDBC.queryForObject(
            "SELECT status FROM product.outbox_message WHERE outbox_message_id = ?",
            String.class, id);
    }

    private boolean dbPublishedAtIsNull(UUID id) {
        return Boolean.TRUE.equals(JDBC.queryForObject(
            "SELECT published_at IS NULL FROM product.outbox_message WHERE outbox_message_id = ?",
            Boolean.class, id));
    }
}
