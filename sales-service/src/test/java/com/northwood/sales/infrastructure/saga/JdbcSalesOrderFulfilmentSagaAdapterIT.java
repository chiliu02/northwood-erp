package com.northwood.sales.infrastructure.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * §2.25 Tier 3: real-Postgres test for {@link JdbcSalesOrderFulfilmentSagaAdapter}
 * — the saga-state port whose {@code claimDue} routine is the architectural twin
 * of the outbox's {@code FOR UPDATE SKIP LOCKED} drain. Covers what only a real
 * DB exhibits:
 *
 * <ul>
 *   <li>{@code insert}→{@code findBySalesOrderId}/{@code findBySagaId} round-trip;</li>
 *   <li>{@code claimDue} stamping a lease on active, due rows and a second
 *       worker's immediate claim returning nothing (lease not expired);</li>
 *   <li>{@code claimDue} skipping rows whose {@code next_retry_at} is in the
 *       future (parked / backed-off sagas);</li>
 *   <li>{@code update} enforcing optimistic concurrency via {@code WHERE saga_id = ?
 *       AND version = ?} → {@link OptimisticLockingFailureException}.</li>
 * </ul>
 */
class JdbcSalesOrderFulfilmentSagaAdapterIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcSalesOrderFulfilmentSagaAdapter ADAPTER;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = sales, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        ADAPTER = new JdbcSalesOrderFulfilmentSagaAdapter(JDBC);
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
    void clearTable() {
        JDBC.execute("TRUNCATE sales.sales_order_fulfilment_saga");
    }

    @Test
    void insert_then_find_round_trips() {
        UUID salesOrderId = UUID.randomUUID();
        SalesOrderFulfilmentSaga saga = SalesOrderFulfilmentSaga.started(salesOrderId, "{}");
        ADAPTER.insert(saga);

        SalesOrderFulfilmentSaga byOrder = ADAPTER.findBySalesOrderId(salesOrderId).orElseThrow();
        assertThat(byOrder.sagaId()).isEqualTo(saga.sagaId());
        assertThat(byOrder.salesOrderId()).isEqualTo(salesOrderId);
        assertThat(byOrder.state()).isEqualTo(SalesOrderFulfilmentSaga.STARTED);
        assertThat(byOrder.currentStep()).isEqualTo("wait_for_worker_pickup");
        assertThat(byOrder.version()).isEqualTo(1L);

        assertThat(ADAPTER.findBySagaId(saga.sagaId())).isPresent();
        assertThat(ADAPTER.findBySalesOrderId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void claimDue_leases_active_due_rows_and_blocks_immediate_reclaim() {
        ADAPTER.insert(SalesOrderFulfilmentSaga.started(UUID.randomUUID(), "{}"));
        ADAPTER.insert(SalesOrderFulfilmentSaga.started(UUID.randomUUID(), "{}"));

        List<SalesOrderFulfilmentSaga> claimed = ADAPTER.claimDue(
            10, Set.of(SalesOrderFulfilmentSaga.STARTED), "worker-1", Duration.ofSeconds(30));

        assertThat(claimed).hasSize(2)
            .allSatisfy(s -> assertThat(s.leaseOwner()).isEqualTo("worker-1"));

        // A sibling worker immediately polling sees both leases held (not expired).
        assertThat(ADAPTER.claimDue(
            10, Set.of(SalesOrderFulfilmentSaga.STARTED), "worker-2", Duration.ofSeconds(30)))
            .isEmpty();
    }

    @Test
    void claimDue_skips_rows_with_future_next_retry_at() {
        // Parked one hour out — not yet due.
        Instant future = Instant.now().plus(Duration.ofHours(1));
        SalesOrderFulfilmentSaga parked = new SalesOrderFulfilmentSaga(
            UUID.randomUUID(), UUID.randomUUID(), SalesOrderFulfilmentSaga.STARTED,
            "parked", null, 0, future, null, null, 0L, "{}",
            Instant.now(), Instant.now(), null);
        ADAPTER.insert(parked);

        assertThat(ADAPTER.claimDue(
            10, Set.of(SalesOrderFulfilmentSaga.STARTED), "worker-1", Duration.ofSeconds(30)))
            .isEmpty();
    }

    @Test
    void claimDue_reclaims_a_row_whose_lease_has_expired() {
        // A worker crashed mid-step: the row still carries its lease_owner, but
        // lease_expires_at is in the past. claimDue must reclaim it (the
        // `lease_owner IS NULL OR lease_expires_at < now()` branch) — the
        // crashed-worker auto-recovery the disaster-recovery doc claims.
        SalesOrderFulfilmentSaga abandoned = new SalesOrderFulfilmentSaga(
            UUID.randomUUID(), UUID.randomUUID(), SalesOrderFulfilmentSaga.STARTED,
            "started", null, 0, Instant.now().minusSeconds(1),
            "dead-worker", Instant.now().minus(Duration.ofMinutes(1)),
            0L, "{}", Instant.now(), Instant.now(), null);
        ADAPTER.insert(abandoned);

        List<SalesOrderFulfilmentSaga> claimed = ADAPTER.claimDue(
            10, Set.of(SalesOrderFulfilmentSaga.STARTED), "worker-2", Duration.ofSeconds(30));

        assertThat(claimed).hasSize(1)
            .first().satisfies(s -> {
                assertThat(s.sagaId()).isEqualTo(abandoned.sagaId());
                assertThat(s.leaseOwner()).isEqualTo("worker-2");
            });
    }

    @Test
    void update_enforces_optimistic_lock_via_version() {
        UUID salesOrderId = UUID.randomUUID();
        ADAPTER.insert(SalesOrderFulfilmentSaga.started(salesOrderId, "{}"));

        SalesOrderFulfilmentSaga loadedA = ADAPTER.findBySalesOrderId(salesOrderId).orElseThrow();
        SalesOrderFulfilmentSaga loadedB = ADAPTER.findBySalesOrderId(salesOrderId).orElseThrow();

        ADAPTER.update(loadedA); // version 1 → 2

        assertThatThrownBy(() -> ADAPTER.update(loadedB))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
