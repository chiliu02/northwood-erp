package com.northwood.purchasing.infrastructure.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
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
 * §2.25 Tier 3: real-Postgres test for {@link JdbcPurchaseToPaySagaAdapter} —
 * same {@code claimDue} (FOR UPDATE SKIP LOCKED + lease) + optimistic-locked
 * {@code save} surface as the sales fulfilment saga, keyed by
 * {@code purchase_order_header_id}.
 */
class JdbcPurchaseToPaySagaAdapterIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcPurchaseToPaySagaAdapter ADAPTER;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = purchasing, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        ADAPTER = new JdbcPurchaseToPaySagaAdapter(JDBC);
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
        JDBC.execute("TRUNCATE purchasing.purchase_to_pay_saga");
    }

    @Test
    void insert_then_find_round_trips() {
        UUID poId = UUID.randomUUID();
        PurchaseToPaySaga saga = PurchaseToPaySaga.started(poId);
        ADAPTER.insert(saga);

        PurchaseToPaySaga r = ADAPTER.findByPurchaseOrderId(poId).orElseThrow();
        assertThat(r.sagaId()).isEqualTo(saga.sagaId());
        assertThat(r.purchaseOrderHeaderId()).isEqualTo(poId);
        assertThat(r.state()).isEqualTo(PurchaseToPaySaga.STARTED);
        assertThat(r.version()).isEqualTo(1L);
        assertThat(ADAPTER.findBySagaId(saga.sagaId())).isPresent();
        assertThat(ADAPTER.findByPurchaseOrderId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void claimDue_leases_active_due_rows_and_blocks_immediate_reclaim() {
        ADAPTER.insert(approvedDue(UUID.randomUUID(), Instant.now()));
        ADAPTER.insert(approvedDue(UUID.randomUUID(), Instant.now()));

        var claimed = ADAPTER.claimDue(
            10, Set.of(PurchaseToPaySaga.PURCHASE_ORDER_APPROVED), "worker-1", Duration.ofSeconds(30));
        assertThat(claimed).hasSize(2)
            .allSatisfy(s -> assertThat(s.leaseOwner()).isEqualTo("worker-1"));

        assertThat(ADAPTER.claimDue(
            10, Set.of(PurchaseToPaySaga.PURCHASE_ORDER_APPROVED), "worker-2", Duration.ofSeconds(30)))
            .isEmpty();
    }

    @Test
    void claimDue_skips_rows_with_future_next_retry_at() {
        ADAPTER.insert(approvedDue(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(1))));

        assertThat(ADAPTER.claimDue(
            10, Set.of(PurchaseToPaySaga.PURCHASE_ORDER_APPROVED), "worker-1", Duration.ofSeconds(30)))
            .isEmpty();
    }

    @Test
    void save_enforces_optimistic_lock_via_version() {
        UUID poId = UUID.randomUUID();
        ADAPTER.insert(PurchaseToPaySaga.started(poId));

        PurchaseToPaySaga loadedA = ADAPTER.findByPurchaseOrderId(poId).orElseThrow();
        PurchaseToPaySaga loadedB = ADAPTER.findByPurchaseOrderId(poId).orElseThrow();

        ADAPTER.save(loadedA); // 1 → 2
        assertThatThrownBy(() -> ADAPTER.save(loadedB))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private static PurchaseToPaySaga approvedDue(UUID poId, Instant nextRetryAt) {
        Instant now = Instant.now();
        return new PurchaseToPaySaga(
            UUID.randomUUID(), poId, PurchaseToPaySaga.PURCHASE_ORDER_APPROVED,
            "wait_for_goods", null, 0, nextRetryAt, null, null, 0L, "{}", now, now, null);
    }
}
