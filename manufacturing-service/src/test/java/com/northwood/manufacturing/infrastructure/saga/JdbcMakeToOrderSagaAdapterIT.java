package com.northwood.manufacturing.infrastructure.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.manufacturing.domain.saga.MakeToOrderSaga;
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
 * §2.25 Tier 3: real-Postgres test for {@link JdbcMakeToOrderSagaAdapter} —
 * same {@code claimDue} (FOR UPDATE SKIP LOCKED + lease) + optimistic-locked
 * {@code update} surface as the other two saga adapters. Its domain key
 * ({@code work_order_id}) is nullable until a work order is attached, so the
 * round-trip is asserted via {@code findBySagaId} and the keyed finder via a
 * work-order-attached row.
 */
class JdbcMakeToOrderSagaAdapterIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcMakeToOrderSagaAdapter ADAPTER;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = manufacturing, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        ADAPTER = new JdbcMakeToOrderSagaAdapter(JDBC);
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
        JDBC.execute("TRUNCATE manufacturing.make_to_order_saga");
    }

    @Test
    void insert_then_find_round_trips() {
        MakeToOrderSaga saga = MakeToOrderSaga.started(UUID.randomUUID(), UUID.randomUUID(), "{}");
        ADAPTER.insert(saga);

        MakeToOrderSaga r = ADAPTER.findBySagaId(saga.sagaId()).orElseThrow();
        assertThat(r.sagaId()).isEqualTo(saga.sagaId());
        assertThat(r.state()).isEqualTo(MakeToOrderSaga.STARTED);
        assertThat(r.workOrderId()).isNull();
        assertThat(r.version()).isEqualTo(1L);

        // Keyed finder works once a work order is attached.
        UUID workOrderId = UUID.randomUUID();
        MakeToOrderSaga withWo = workOrderCreatedDue(workOrderId, Instant.now());
        ADAPTER.insert(withWo);
        assertThat(ADAPTER.findByWorkOrderId(workOrderId)).isPresent()
            .get().extracting(MakeToOrderSaga::sagaId).isEqualTo(withWo.sagaId());
    }

    @Test
    void claimDue_leases_active_due_rows_and_blocks_immediate_reclaim() {
        ADAPTER.insert(MakeToOrderSaga.started(UUID.randomUUID(), UUID.randomUUID(), "{}"));
        ADAPTER.insert(MakeToOrderSaga.started(UUID.randomUUID(), UUID.randomUUID(), "{}"));

        var claimed = ADAPTER.claimDue(
            10, Set.of(MakeToOrderSaga.STARTED), "worker-1", Duration.ofSeconds(30));
        assertThat(claimed).hasSize(2)
            .allSatisfy(s -> assertThat(s.leaseOwner()).isEqualTo("worker-1"));

        assertThat(ADAPTER.claimDue(
            10, Set.of(MakeToOrderSaga.STARTED), "worker-2", Duration.ofSeconds(30)))
            .isEmpty();
    }

    @Test
    void claimDue_skips_rows_with_future_next_retry_at() {
        ADAPTER.insert(workOrderCreatedDue(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(1))));

        assertThat(ADAPTER.claimDue(
            10, Set.of(MakeToOrderSaga.WORK_ORDER_CREATED), "worker-1", Duration.ofSeconds(30)))
            .isEmpty();
    }

    @Test
    void update_enforces_optimistic_lock_via_version() {
        MakeToOrderSaga saga = MakeToOrderSaga.started(UUID.randomUUID(), UUID.randomUUID(), "{}");
        ADAPTER.insert(saga);

        MakeToOrderSaga loadedA = ADAPTER.findBySagaId(saga.sagaId()).orElseThrow();
        MakeToOrderSaga loadedB = ADAPTER.findBySagaId(saga.sagaId()).orElseThrow();

        ADAPTER.update(loadedA); // 1 → 2
        assertThatThrownBy(() -> ADAPTER.update(loadedB))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private static MakeToOrderSaga workOrderCreatedDue(UUID workOrderId, Instant nextRetryAt) {
        Instant now = Instant.now();
        return new MakeToOrderSaga(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), workOrderId,
            MakeToOrderSaga.WORK_ORDER_CREATED, "wait", null, 0, nextRetryAt, null, null,
            0L, "{}", now, now, null);
    }
}
