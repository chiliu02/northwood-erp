package com.northwood.sales.infrastructure.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.tracing.Tracer;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-Postgres test for {@link JdbcSalesOrderFulfilmentSagaAdapter}
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
 *       AND version = ?} → {@link OptimisticLockingFailureException};</li>
 *   <li>{@code claimDue} under <em>genuine</em> concurrency — two workers polling the
 *       same due row at the same instant, where {@code FOR UPDATE SKIP LOCKED} grants
 *       it to exactly one (the cross-partition saga race; the others above prove the
 *       mechanisms only sequentially).</li>
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
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = sales, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        ADAPTER = new JdbcSalesOrderFulfilmentSagaAdapter(JDBC, Tracer.NOOP);
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

    /**
     * The cross-partition race in DB terms: two workers — standing in for two
     * consequence events delivered on different Kafka partitions / threads — poll
     * the <em>same</em> due saga row at the same instant. {@code claimDue}'s
     * {@code FOR UPDATE SKIP LOCKED} must grant it to exactly one; the other meets
     * the live row lock, skips it, and claims nothing — so no saga is ever advanced
     * twice. This is the concurrency the in-process {@code SynchronousBus} harness
     * cannot exercise: the saga twin of
     * {@code JdbcInboxAdapterIT.advisory_lock_serializes_a_concurrent_duplicate}.
     *
     * <p>Determinism comes from holding worker A's transaction open: A claims the
     * only due row and parks on the row lock (uncommitted) while worker B runs its
     * own {@code claimDue}. B therefore meets a live lock — the simultaneous-poll
     * case — and {@code SKIP LOCKED} makes it return empty rather than block or
     * double-claim. A commits only after B has finished.
     */
    @Test
    void claimDue_under_concurrency_grants_a_due_saga_to_exactly_one_worker() throws Exception {
        UUID salesOrderId = UUID.randomUUID();
        ADAPTER.insert(SalesOrderFulfilmentSaga.started(salesOrderId, "{}"));

        CountDownLatch aClaimed = new CountDownLatch(1);   // A holds the row lock (uncommitted)
        CountDownLatch releaseA = new CountDownLatch(1);   // main → A may commit
        AtomicReference<List<SalesOrderFulfilmentSaga>> aResult = new AtomicReference<>();
        AtomicReference<List<SalesOrderFulfilmentSaga>> bResult = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread a = new Thread(() -> {
            try (Connection conn = newTxnConnection()) {
                JdbcSalesOrderFulfilmentSagaAdapter adapter = adapterOn(conn);
                aResult.set(adapter.claimDue(
                    10, Set.of(SalesOrderFulfilmentSaga.STARTED), "worker-A", Duration.ofSeconds(30)));
                aClaimed.countDown();
                await(releaseA);
                conn.commit();   // releases the row lock
            } catch (Throwable t) {
                error.compareAndSet(null, t);
                aClaimed.countDown();   // don't strand B on failure
            }
        });

        Thread b = new Thread(() -> {
            try (Connection conn = newTxnConnection()) {
                JdbcSalesOrderFulfilmentSagaAdapter adapter = adapterOn(conn);
                await(aClaimed);   // A holds the lock on the only due row
                bResult.set(adapter.claimDue(
                    10, Set.of(SalesOrderFulfilmentSaga.STARTED), "worker-B", Duration.ofSeconds(30)));
                conn.commit();
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });

        a.start();
        b.start();
        b.join(10_000);          // B completes its claim attempt while A's lock is held
        releaseA.countDown();    // now A may commit
        a.join(10_000);

        assertThat(error.get()).withFailMessage("worker failed: %s", error.get()).isNull();
        assertThat(aResult.get()).hasSize(1)
            .first().satisfies(s -> assertThat(s.salesOrderId()).isEqualTo(salesOrderId));
        assertThat(bResult.get())
            .withFailMessage("SKIP LOCKED should have denied the second concurrent worker")
            .isEmpty();
    }

    private static Connection newTxnConnection() throws SQLException {
        Connection c = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        c.setAutoCommit(false);
        return c;
    }

    private static JdbcSalesOrderFulfilmentSagaAdapter adapterOn(Connection conn) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
        jdbc.execute("SET search_path = sales, shared");
        return new JdbcSalesOrderFulfilmentSagaAdapter(jdbc, Tracer.NOOP);
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
