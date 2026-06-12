package com.northwood.manufacturing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderMaterial;
import com.northwood.manufacturing.domain.WorkOrderOperation;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-Postgres test for the full {@link JdbcWorkOrderRepository}
 * aggregate — header + material requirements + planned operations. Complements
 * the focused {@link JdbcWorkOrderRepositoryMaterialStatusIT} (which guards only
 * the {@code material_status} column + its CHECK) by exercising the rest of the
 * surface that only a real DB shows:
 *
 * <ul>
 *   <li>{@code release} → {@code findById} round-trip of header + materials +
 *       operations incl. the status / material_status / line-status enum
 *       {@code code()}/{@code fromCode()} round-trips, child-collection ordering
 *       ({@code component_sku} / {@code operation_sequence}) and the
 *       {@code WorkOrderCreated} outbox row;</li>
 *   <li>{@code completeOperation} advancing the WO {@code released → in_progress}
 *       then {@code → completed} when the last operation lands, persisting
 *       operation status + actual_minutes via the update path and emitting
 *       {@code OperationCompleted} + {@code WorkOrderManufacturingCompleted};</li>
 *   <li>the {@code WHERE version = ?} optimistic lock → {@code OptimisticLockingFailureException}.</li>
 * </ul>
 */
class JdbcWorkOrderRepositoryIT {

    private static final UUID WORK_CENTER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID BOM_HEADER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcWorkOrderRepository REPO;

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
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcWorkOrderRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
        // FK parents for the operation (work_center) and header (bom_header).
        JDBC.update("INSERT INTO manufacturing.work_center (work_center_id, work_center_code, name) "
            + "VALUES (?, 'WC-IT', 'Assembly IT')", WORK_CENTER_ID);
        JDBC.update("INSERT INTO manufacturing.bom_header (bom_header_id, finished_product_id, "
            + "finished_product_sku, finished_product_name) VALUES (?, ?, 'FG-IT', 'Finished IT')",
            BOM_HEADER_ID, UUID.randomUUID());
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
        JDBC.execute("TRUNCATE manufacturing.work_order_operation, manufacturing.work_order_material, "
            + "manufacturing.work_order, manufacturing.outbox_message CASCADE");
    }

    @Test
    void release_then_findById_round_trips_header_materials_and_operations() {
        WorkOrder wo = releasedWorkOrder("WO-RT-001");
        save(wo);

        WorkOrder r = REPO.findById(wo.id()).orElseThrow();
        assertThat(r.workOrderNumber()).isEqualTo("WO-RT-001");
        assertThat(r.status()).isEqualTo(WorkOrder.Status.RELEASED);
        assertThat(r.materialStatus()).isEqualTo(WorkOrder.MaterialStatus.RESERVATION_PENDING);
        assertThat(r.plannedQuantity()).isEqualByComparingTo("5");
        assertThat(r.version()).isEqualTo(1L);

        assertThat(r.materials()).hasSize(2);
        assertThat(r.materials().get(0).componentSku()).isEqualTo("RAW-A"); // ORDER BY component_sku
        assertThat(r.materials().get(0).status()).isEqualTo(WorkOrder.MaterialLineStatus.REQUIRED);
        assertThat(r.materials().get(0).requiredQuantity()).isEqualByComparingTo("10");

        assertThat(r.operations()).hasSize(2);
        assertThat(r.operations().get(0).operationSequence()).isEqualTo(10); // ORDER BY operation_sequence
        assertThat(r.operations().get(0).status()).isEqualTo(WorkOrder.OperationStatus.PLANNED);
        assertThat(r.operations().get(0).plannedRunMinutes()).isEqualByComparingTo("60");

        assertThat(countOutbox(wo.id().value())).isEqualTo(1L); // WorkOrderCreated
    }

    @Test
    void completeOperations_advances_to_completed_and_emits_events() {
        WorkOrder wo = releasedWorkOrder("WO-CO-001");
        save(wo);
        UUID woId = wo.id().value();

        // First op: WO moves released → in_progress, but not all ops done yet.
        WorkOrder afterFirst = REPO.findById(wo.id()).orElseThrow();
        afterFirst.completeOperation(10, new BigDecimal("30"), true);
        save(afterFirst);
        assertThat(statusOf(woId)).isEqualTo("in_progress");

        // Last op: all operations complete + no pending children → WO completed.
        WorkOrder afterSecond = REPO.findById(wo.id()).orElseThrow();
        afterSecond.completeOperation(20, new BigDecimal("45"), true);
        save(afterSecond);

        WorkOrder r = REPO.findById(wo.id()).orElseThrow();
        assertThat(r.status()).isEqualTo(WorkOrder.Status.COMPLETED);
        assertThat(r.completedQuantity()).isEqualByComparingTo("5"); // = planned_quantity
        assertThat(r.actualStartAt()).isNotNull();
        assertThat(r.actualCompletedAt()).isNotNull();
        assertThat(r.operations()).allMatch(op -> op.status() == WorkOrder.OperationStatus.COMPLETED);
        assertThat(r.operations().get(0).actualMinutes()).isEqualByComparingTo("30");
        assertThat(r.operations().get(1).actualMinutes()).isEqualByComparingTo("45");

        // WorkOrderCreated + 2×OperationCompleted + WorkOrderManufacturingCompleted.
        assertThat(countOutbox(woId)).isEqualTo(4L);
    }

    @Test
    void stale_version_update_raises_optimistic_lock_failure() {
        WorkOrder wo = releasedWorkOrder("WO-LOCK-001");
        save(wo);

        WorkOrder loadedA = REPO.findById(wo.id()).orElseThrow();
        WorkOrder loadedB = REPO.findById(wo.id()).orElseThrow();

        loadedB.applyReservationOutcome(WorkOrder.MaterialStatus.RESERVED);
        save(loadedB); // version 1 → 2

        loadedA.applyReservationOutcome(WorkOrder.MaterialStatus.SHORTAGE);
        assertThatThrownBy(() -> save(loadedA))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static WorkOrder releasedWorkOrder(String number) {
        List<WorkOrderMaterial> materials = List.of(
            new WorkOrderMaterial(UUID.randomUUID(), UUID.randomUUID(), "RAW-A", "Raw A",
                new BigDecimal("10"), new BigDecimal("2.500000"), WorkOrder.MaterialLineStatus.REQUIRED),
            new WorkOrderMaterial(UUID.randomUUID(), UUID.randomUUID(), "RAW-B", "Raw B",
                new BigDecimal("4"), new BigDecimal("7.000000"), WorkOrder.MaterialLineStatus.REQUIRED));
        List<WorkOrderOperation> operations = List.of(
            new WorkOrderOperation(UUID.randomUUID(), 10, "OP-10", "Cut", WORK_CENTER_ID,
                new BigDecimal("5"), new BigDecimal("60"), WorkOrder.OperationStatus.PLANNED),
            new WorkOrderOperation(UUID.randomUUID(), 20, "OP-20", "Assemble", WORK_CENTER_ID,
                new BigDecimal("5"), new BigDecimal("45"), WorkOrder.OperationStatus.PLANNED));
        return WorkOrder.release(
            number, UUID.randomUUID(), UUID.randomUUID(), null,
            UUID.randomUUID(), "FG-IT-1", "Finished 1",
            BOM_HEADER_ID, new BigDecimal("5"), materials, operations);
    }

    private void save(WorkOrder wo) {
        TX.executeWithoutResult(s -> REPO.save(wo));
    }

    private String statusOf(UUID workOrderId) {
        return JDBC.queryForObject(
            "SELECT status FROM manufacturing.work_order WHERE work_order_id = ?", String.class, workOrderId);
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM manufacturing.outbox_message WHERE aggregate_id = ?", Long.class, aggregateId);
    }
}
