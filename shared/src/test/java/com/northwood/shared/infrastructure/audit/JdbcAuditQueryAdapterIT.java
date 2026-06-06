package com.northwood.shared.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.shared.application.audit.AuditEntry;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-Postgres test for {@link JdbcAuditQueryAdapter}, the shared
 * read-side adapter behind every service's audit-log endpoint. It reads
 * {@code outbox_message}; this IT seeds rows directly and exercises the
 * dynamically-assembled WHERE clause that a mocked unit test cannot:
 *
 * <ul>
 *   <li>newest-first ordering by {@code sequence_number DESC} + the {@code LIMIT};</li>
 *   <li>optional {@code aggregate_id} filter;</li>
 *   <li>optional {@code created_at} time-window filter;</li>
 *   <li>the {@code RowMapper} mapping onto {@link AuditEntry} (incl. the injected
 *       {@code sourceService} and nullable {@code correlation_id} → String).</li>
 * </ul>
 *
 * <p>Runs against the {@code product} schema.
 */
class JdbcAuditQueryAdapterIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcAuditQueryAdapter ADAPTER;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = product, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        ADAPTER = new JdbcAuditQueryAdapter(JDBC, "product");
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

    @Test
    void find_returns_entries_newest_first_and_maps_every_field() {
        UUID aggregateId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        insertOutbox(aggregateId, "product.ProductCreated", "alice", correlationId, Instant.now());
        insertOutbox(aggregateId, "product.SalesPriceChanged", "bob", null, Instant.now());

        List<AuditEntry> entries = ADAPTER.find(null, null, null, 10);

        assertThat(entries).extracting(AuditEntry::eventType)
            .containsExactly("product.SalesPriceChanged", "product.ProductCreated"); // seq DESC

        AuditEntry newest = entries.get(0);
        assertThat(newest.sourceService()).isEqualTo("product");
        assertThat(newest.aggregateType()).isEqualTo("Product");
        assertThat(newest.aggregateId()).isEqualTo(aggregateId);
        assertThat(newest.actorUserId()).isEqualTo("bob");
        assertThat(newest.correlationId()).isNull();
        assertThat(newest.sequenceNumber()).isNotNull();
        assertThat(newest.occurredAt()).isNotNull();

        AuditEntry oldest = entries.get(1);
        assertThat(oldest.correlationId()).isEqualTo(correlationId.toString());
    }

    @Test
    void find_filters_by_aggregate_id() {
        UUID wanted = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        insertOutbox(wanted, "product.ProductCreated", "alice", null, Instant.now());
        insertOutbox(other, "product.ProductCreated", "alice", null, Instant.now());
        insertOutbox(wanted, "product.SalesPriceChanged", "alice", null, Instant.now());

        List<AuditEntry> entries = ADAPTER.find(wanted, null, null, 10);

        assertThat(entries).hasSize(2)
            .allSatisfy(e -> assertThat(e.aggregateId()).isEqualTo(wanted));
    }

    @Test
    void find_respects_the_limit() {
        UUID aggregateId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            insertOutbox(aggregateId, "product.Event" + i, "alice", null, Instant.now());
        }

        assertThat(ADAPTER.find(null, null, null, 2)).hasSize(2);
    }

    @Test
    void find_filters_by_created_at_window() {
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        insertOutbox(aggregateId, "product.TwoDaysAgo", "alice", null, now.minus(Duration.ofDays(2)));
        insertOutbox(aggregateId, "product.OneDayAgo", "alice", null, now.minus(Duration.ofDays(1)));
        insertOutbox(aggregateId, "product.Now", "alice", null, now);

        List<AuditEntry> entries = ADAPTER.find(
            null, now.minus(Duration.ofHours(36)), now.plus(Duration.ofHours(1)), 10);

        assertThat(entries).extracting(AuditEntry::eventType)
            .containsExactlyInAnyOrder("product.OneDayAgo", "product.Now");
    }

    private void insertOutbox(UUID aggregateId, String eventType, String actor,
                              UUID correlationId, Instant createdAt) {
        // Supply outbox_message_id explicitly — every real writer (OutboxRow /
        // the per-service Jdbc*Repository) does, so the shared.uuid_generate_v7()
        // column default is never exercised at runtime.
        JDBC.update(
            """
            INSERT INTO product.outbox_message
                (outbox_message_id, aggregate_type, aggregate_id, event_type, event_version,
                 payload, correlation_id, actor_user_id, created_at)
            VALUES (?, 'Product', ?, ?, 1, '{}'::jsonb, ?, ?, ?)
            """,
            UUID.randomUUID(), aggregateId, eventType, correlationId, actor, Timestamp.from(createdAt)
        );
    }
}
