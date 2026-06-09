package com.northwood.manufacturing.infrastructure.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.manufacturing.domain.saga.WorkOrderSaga;
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
 * Real-Postgres test for {@link JdbcWorkOrderSagaAdapter} — same
 * {@code claimDue} (FOR UPDATE SKIP LOCKED + lease) + optimistic-locked
 * {@code update} surface as the other two saga adapters. Its domain key
 * ({@code work_order_id}) is nullable until a work order is attached, so the
 * round-trip is asserted via {@code findBySagaId} and the keyed finder via a
 * work-order-attached row.
 */
class JdbcWorkOrderSagaAdapterIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcWorkOrderSagaAdapter ADAPTER;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = manufacturing, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        ADAPTER = new JdbcWorkOrderSagaAdapter(JDBC, Tracer.NOOP);
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
        JDBC.execute("TRUNCATE manufacturing.work_order_saga");
    }

    @Test
    void insert_then_find_round_trips() {
        UUID firstWo = UUID.randomUUID();
        WorkOrderSaga saga = WorkOrderSaga.attachedToWorkOrder(
            UUID.randomUUID(), UUID.randomUUID(), firstWo, "{}");
        ADAPTER.insert(saga);

        WorkOrderSaga r = ADAPTER.findBySagaId(saga.sagaId()).orElseThrow();
        assertThat(r.sagaId()).isEqualTo(saga.sagaId());
        assertThat(r.state()).isEqualTo(WorkOrderSaga.WORK_ORDER_CREATED);
        assertThat(r.workOrderId()).isEqualTo(firstWo);
        assertThat(r.version()).isEqualTo(1L);

        // Keyed finder works once a work order is attached.
        UUID workOrderId = UUID.randomUUID();
        WorkOrderSaga withWo = workOrderCreatedDue(workOrderId, Instant.now());
        ADAPTER.insert(withWo);
        assertThat(ADAPTER.findByWorkOrderId(workOrderId)).isPresent()
            .get().extracting(WorkOrderSaga::sagaId).isEqualTo(withWo.sagaId());
    }

    @Test
    void claimDue_leases_active_due_rows_and_blocks_immediate_reclaim() {
        ADAPTER.insert(workOrderCreatedDue(UUID.randomUUID(), Instant.now()));
        ADAPTER.insert(workOrderCreatedDue(UUID.randomUUID(), Instant.now()));

        var claimed = ADAPTER.claimDue(
            10, Set.of(WorkOrderSaga.WORK_ORDER_CREATED), "worker-1", Duration.ofSeconds(30));
        assertThat(claimed).hasSize(2)
            .allSatisfy(s -> assertThat(s.leaseOwner()).isEqualTo("worker-1"));

        assertThat(ADAPTER.claimDue(
            10, Set.of(WorkOrderSaga.WORK_ORDER_CREATED), "worker-2", Duration.ofSeconds(30)))
            .isEmpty();
    }

    @Test
    void claimDue_skips_rows_with_future_next_retry_at() {
        ADAPTER.insert(workOrderCreatedDue(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(1))));

        assertThat(ADAPTER.claimDue(
            10, Set.of(WorkOrderSaga.WORK_ORDER_CREATED), "worker-1", Duration.ofSeconds(30)))
            .isEmpty();
    }

    @Test
    void update_enforces_optimistic_lock_via_version() {
        WorkOrderSaga saga = workOrderCreatedDue(UUID.randomUUID(), Instant.now());
        ADAPTER.insert(saga);

        WorkOrderSaga loadedA = ADAPTER.findBySagaId(saga.sagaId()).orElseThrow();
        WorkOrderSaga loadedB = ADAPTER.findBySagaId(saga.sagaId()).orElseThrow();

        ADAPTER.update(loadedA); // 1 → 2
        assertThatThrownBy(() -> ADAPTER.update(loadedB))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private static WorkOrderSaga workOrderCreatedDue(UUID workOrderId, Instant nextRetryAt) {
        Instant now = Instant.now();
        return new WorkOrderSaga(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), workOrderId,
            WorkOrderSaga.WORK_ORDER_CREATED, "wait", null, 0, nextRetryAt, null, null,
            0L, "{}", now, now, null);
    }
}
