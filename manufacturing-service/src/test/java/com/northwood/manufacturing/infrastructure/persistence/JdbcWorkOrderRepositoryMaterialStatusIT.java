package com.northwood.manufacturing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.5 Phase C: real-Postgres test for the §2.2 work-order material_status
 * projection (dev-done.md 2026-05-12). Specifically guards against a future
 * regression in {@link JdbcWorkOrderRepository#update} dropping the
 * {@code material_status = ?} column from its UPDATE SQL — which would
 * silently break the projection even though {@code WorkOrder.materialStatus}
 * was correctly mutated in memory.
 *
 * <p>Also verifies the schema's
 * {@code CHECK (material_status IN (...))} rejects unknown values.
 */
class JdbcWorkOrderRepositoryMaterialStatusIT {

    // Seeded by northwood_erp.sql — active BOM for the wooden table, referenced by FK.
    private static final UUID SEED_BOM_HEADER_ID =
        UUID.fromString("00000000-0000-7000-8000-000000000100");
    private static final UUID SEED_FG_PRODUCT_ID =
        UUID.fromString("00000000-0000-7000-8000-000000000001");

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
        loadBaseline();
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = manufacturing, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcWorkOrderRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
    }

    private static void loadBaseline() {
        Path file = Path.of("..", "db", "northwood_erp.sql");
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
            throw new IllegalStateException("Failed to apply baseline schema", e);
        }
    }

    private UUID workOrderId;

    @BeforeEach
    void seedWorkOrder() {
        // Bypass JdbcWorkOrderRepository.save's full insert path (which needs
        // matching ops + materials) — INSERT the row directly so we have a
        // reconstitutable header on the right FKs.
        workOrderId = UUID.randomUUID();
        JDBC.update("""
            INSERT INTO manufacturing.work_order (
                work_order_id, work_order_number,
                finished_product_id, finished_product_sku, finished_product_name,
                bom_header_id, planned_quantity, status, material_status, version
            ) VALUES (?, ?, ?, 'FG-TABLE-001', 'Wooden Dining Table',
                      ?, 1, 'released', 'reservation_pending', 1)
            """,
            workOrderId, "WO-IT-" + workOrderId.toString().substring(0, 8),
            SEED_FG_PRODUCT_ID, SEED_BOM_HEADER_ID
        );
    }

    private String dbMaterialStatus() {
        return JDBC.queryForObject(
            "SELECT material_status FROM manufacturing.work_order WHERE work_order_id = ?",
            String.class, workOrderId
        );
    }

    private long dbVersion() {
        return JDBC.queryForObject(
            "SELECT version FROM manufacturing.work_order WHERE work_order_id = ?",
            Long.class, workOrderId
        );
    }

    private WorkOrder reconstituteForUpdate() {
        return WorkOrder.reconstitute(
            WorkOrderId.of(workOrderId), "WO-IT",
            null, null, null,
            SEED_FG_PRODUCT_ID, "FG-TABLE-001", "Wooden Dining Table",
            SEED_BOM_HEADER_ID, BigDecimal.ONE,
            WorkOrder.Status.RELEASED, WorkOrder.MaterialStatus.RESERVATION_PENDING,
            BigDecimal.ZERO, null, null,
            1L,
            List.of(), List.of()
        );
    }

    @Test
    void update_persists_reserved_material_status() {
        WorkOrder wo = reconstituteForUpdate();
        wo.applyReservationOutcome(WorkOrder.MaterialStatus.RESERVED);

        TX.executeWithoutResult(s -> REPO.save(wo));

        assertThat(dbMaterialStatus()).isEqualTo("reserved");
        // Version was 1, save advances to 2.
        assertThat(dbVersion()).isEqualTo(2L);
    }

    @Test
    void update_persists_partially_reserved_material_status() {
        WorkOrder wo = reconstituteForUpdate();
        wo.applyReservationOutcome(WorkOrder.MaterialStatus.PARTIALLY_RESERVED);

        TX.executeWithoutResult(s -> REPO.save(wo));

        assertThat(dbMaterialStatus()).isEqualTo("partially_reserved");
    }

    @Test
    void update_persists_shortage_material_status() {
        WorkOrder wo = reconstituteForUpdate();
        wo.applyReservationOutcome(WorkOrder.MaterialStatus.SHORTAGE);

        TX.executeWithoutResult(s -> REPO.save(wo));

        assertThat(dbMaterialStatus()).isEqualTo("shortage");
    }

    @Test
    void schema_rejects_unknown_material_status_via_CHECK() {
        // Bypass the aggregate's guard (which already rejects in
        // applyReservationOutcome) and write a raw UPDATE — proves the
        // schema-side guard is also load-bearing.
        assertThatThrownBy(() ->
            JDBC.update("UPDATE manufacturing.work_order SET material_status = 'bogus' WHERE work_order_id = ?",
                workOrderId)
        )
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("violates check constraint");
    }
}
