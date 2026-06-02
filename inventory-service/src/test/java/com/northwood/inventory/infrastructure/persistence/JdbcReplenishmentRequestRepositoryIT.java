package com.northwood.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequest.Reason;
import com.northwood.inventory.domain.ReplenishmentRequest.Status;
import com.northwood.inventory.domain.ReplenishmentRequest.TargetService;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-Postgres test for {@link JdbcReplenishmentRequestRepository}.
 * Covers the {@code save → findById} round-trip with all three enum columns
 * (status / target_service / reason), the outbox row, and the one-open-per-
 * (product, warehouse) partial unique index (the load-bearing safety net for
 * concurrent detection triggers).
 */
class JdbcReplenishmentRequestRepositoryIT {

    private static final UUID WAREHOUSE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcReplenishmentRequestRepository REPO;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = inventory, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcReplenishmentRequestRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
        JDBC.update(
            "INSERT INTO inventory.warehouse (warehouse_id, warehouse_code, name) VALUES (?, 'WH-IT-R', 'Warehouse Replenish IT')",
            WAREHOUSE_ID);
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
    void clearTables() {
        JDBC.execute("TRUNCATE inventory.replenishment_request, inventory.outbox_message CASCADE");
    }

    @Test
    void save_then_findById_round_trips_with_all_enums() {
        UUID productId = UUID.randomUUID();
        ReplenishmentRequest r = ReplenishmentRequest.request(
            productId, WAREHOUSE_ID, new BigDecimal("25"),
            TargetService.PURCHASING, Reason.WORK_ORDER_SHORTAGE
        );
        save(r);

        ReplenishmentRequest reloaded = REPO.findById(r.id()).orElseThrow();
        assertThat(reloaded.productId()).isEqualTo(productId);
        assertThat(reloaded.warehouseId()).isEqualTo(WAREHOUSE_ID);
        assertThat(reloaded.requestedQuantity()).isEqualByComparingTo("25");
        assertThat(reloaded.targetService()).isEqualTo(TargetService.PURCHASING);
        assertThat(reloaded.reason()).isEqualTo(Reason.WORK_ORDER_SHORTAGE);
        assertThat(reloaded.status()).isEqualTo(Status.REQUESTED);
        assertThat(reloaded.version()).isEqualTo(1L);
        assertThat(countOutbox(r.id().value())).isEqualTo(1L);
    }

    @Test
    void second_open_request_for_same_product_warehouse_violates_partial_unique_index() {
        UUID productId = UUID.randomUUID();
        save(ReplenishmentRequest.request(
            productId, WAREHOUSE_ID, new BigDecimal("10"),
            TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
        ));

        ReplenishmentRequest dup = ReplenishmentRequest.request(
            productId, WAREHOUSE_ID, new BigDecimal("20"),
            TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
        );
        assertThatThrownBy(() -> save(dup))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void open_request_for_different_product_at_same_warehouse_is_allowed() {
        save(ReplenishmentRequest.request(
            UUID.randomUUID(), WAREHOUSE_ID, new BigDecimal("10"),
            TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
        ));
        save(ReplenishmentRequest.request(
            UUID.randomUUID(), WAREHOUSE_ID, new BigDecimal("10"),
            TargetService.PURCHASING, Reason.REORDER_POINT_BREACH
        ));

        Long openCount = JDBC.queryForObject(
            "SELECT COUNT(*) FROM inventory.replenishment_request WHERE warehouse_id = ? AND status = 'requested'",
            Long.class, WAREHOUSE_ID
        );
        assertThat(openCount).isEqualTo(2L);
    }

    @Test
    void slice_E_mark_dispatched_and_fulfilled_round_trip() {
        UUID productId = UUID.randomUUID();
        UUID workOrderId = UUID.randomUUID();
        ReplenishmentRequest r = ReplenishmentRequest.request(
            productId, WAREHOUSE_ID, new BigDecimal("10"),
            TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
        );
        save(r);

        ReplenishmentRequest loadedForDispatch = REPO.findById(r.id()).orElseThrow();
        loadedForDispatch.markDispatched(
            com.northwood.inventory.domain.ReplenishmentRequest.DispatchedAggregateKind.WORK_ORDER,
            workOrderId
        );
        save(loadedForDispatch);

        ReplenishmentRequest afterDispatch = REPO.findByDispatchedAggregateId(workOrderId).orElseThrow();
        assertThat(afterDispatch.status()).isEqualTo(Status.DISPATCHED);
        assertThat(afterDispatch.dispatchedAggregateId()).isEqualTo(workOrderId);
        assertThat(afterDispatch.dispatchedAt()).isNotNull();
        assertThat(afterDispatch.version()).isEqualTo(2L);

        afterDispatch.markFulfilled();
        save(afterDispatch);

        ReplenishmentRequest afterFulfil = REPO.findById(r.id()).orElseThrow();
        assertThat(afterFulfil.status()).isEqualTo(Status.FULFILLED);
        assertThat(afterFulfil.fulfilledAt()).isNotNull();
        assertThat(afterFulfil.version()).isEqualTo(3L);
        // ReplenishmentRequested at insert + ReplenishmentFulfilled at mark-fulfilled.
        assertThat(countOutbox(r.id().value())).isEqualTo(2L);
    }

    @Test
    void slice_E_linkPurchaseOrder_then_findByLinkedPurchaseOrderId() {
        UUID productId = UUID.randomUUID();
        UUID prId = UUID.randomUUID();
        UUID poId = UUID.randomUUID();
        ReplenishmentRequest r = ReplenishmentRequest.request(
            productId, WAREHOUSE_ID, new BigDecimal("5"),
            TargetService.PURCHASING, Reason.REORDER_POINT_BREACH
        );
        save(r);

        ReplenishmentRequest dispatched = REPO.findById(r.id()).orElseThrow();
        dispatched.markDispatched(
            com.northwood.inventory.domain.ReplenishmentRequest.DispatchedAggregateKind.PURCHASE_REQUISITION,
            prId
        );
        save(dispatched);

        ReplenishmentRequest linkable = REPO.findById(r.id()).orElseThrow();
        linkable.linkPurchaseOrder(poId);
        save(linkable);

        assertThat(REPO.findByLinkedPurchaseOrderId(poId)).isPresent();
        assertThat(REPO.findByLinkedPurchaseOrderId(UUID.randomUUID())).isEmpty();
    }

    private void save(ReplenishmentRequest r) {
        TX.executeWithoutResult(s -> REPO.save(r));
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM inventory.outbox_message WHERE aggregate_id = ?",
            Long.class, aggregateId);
    }
}
