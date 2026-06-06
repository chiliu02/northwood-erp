package com.northwood.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.reporting.application.dto.ProductionPlanningView;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-Postgres test for {@link JdbcProductionPlanningQueryPort} —
 * a read-only CQRS query port over the inbox-fed {@code production_planning_board}
 * read model. Covers the column/type round-trip (incl. the nullable
 * sales-order linkage, the shortage counters and the {@code priority} CHECK
 * value), the {@code findAll} {@code updated_at DESC} ordering, and the
 * empty-Optional miss.
 */
class JdbcProductionPlanningQueryPortIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcProductionPlanningQueryPort PORT;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = reporting, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PORT = new JdbcProductionPlanningQueryPort(JDBC);
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
        JDBC.execute("TRUNCATE reporting.production_planning_board CASCADE");
    }

    @Test
    void findByWorkOrderId_round_trips_all_columns() {
        UUID woId = UUID.randomUUID();
        UUID soId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        JDBC.update("""
            INSERT INTO reporting.production_planning_board (
                work_order_id, work_order_number, sales_order_header_id, order_number,
                finished_product_id, finished_product_sku, finished_product_name,
                planned_quantity, completed_quantity, work_order_status, material_status,
                shortage_materials_count, shortage_summary, open_purchase_orders_count,
                priority, updated_at
            ) VALUES (?, 'WO-PB-1', ?, 'SO-1', ?, 'FG-1', 'Finished 1',
                      5, 2, 'in_progress', 'shortage', 1, '1 short: RAW-1', 1, 'high', now())
            """,
            woId, soId, productId);

        ProductionPlanningView v = PORT.findByWorkOrderId(woId).orElseThrow();
        assertThat(v.workOrderNumber()).isEqualTo("WO-PB-1");
        assertThat(v.salesOrderHeaderId()).isEqualTo(soId);
        assertThat(v.orderNumber()).isEqualTo("SO-1");
        assertThat(v.finishedProductSku()).isEqualTo("FG-1");
        assertThat(v.plannedQuantity()).isEqualByComparingTo("5");
        assertThat(v.completedQuantity()).isEqualByComparingTo("2");
        assertThat(v.workOrderStatus()).isEqualTo("in_progress");
        assertThat(v.materialStatus()).isEqualTo("shortage");
        assertThat(v.shortageMaterialsCount()).isEqualTo(1);
        assertThat(v.openPurchaseOrdersCount()).isEqualTo(1);
        assertThat(v.priority()).isEqualTo("high");
        assertThat(v.updatedAt()).isNotNull();
    }

    @Test
    void findByWorkOrderId_missing_returns_empty() {
        assertThat(PORT.findByWorkOrderId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findAll_orders_by_updated_at_desc() {
        UUID older = insertMinimal("WO-OLD", Instant.now().minusSeconds(3600));
        UUID newer = insertMinimal("WO-NEW", Instant.now());

        assertThat(PORT.findAll())
            .extracting(ProductionPlanningView::workOrderId)
            .containsExactly(newer, older);
    }

    private UUID insertMinimal(String number, Instant updatedAt) {
        UUID woId = UUID.randomUUID();
        JDBC.update("""
            INSERT INTO reporting.production_planning_board (
                work_order_id, work_order_number, finished_product_id,
                finished_product_sku, finished_product_name, planned_quantity,
                work_order_status, material_status, updated_at
            ) VALUES (?, ?, ?, 'FG-X', 'Finished X', 1, 'released', 'reserved', ?)
            """,
            woId, number, UUID.randomUUID(), Timestamp.from(updatedAt));
        return woId;
    }
}
