package com.northwood.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.reporting.application.dto.MaterialShortageView;
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
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * §2.25 Tier 3: real-Postgres test for {@link JdbcMaterialShortageQueryPort} —
 * a read-only CQRS query port over the inbox-fed {@code material_shortage_view}
 * read model. Covers the column/type round-trip, the {@code findActive}
 * {@code status <> 'resolved'} filter, and the load-bearing CASE-based status
 * ordering ({@code open} → {@code purchase_requested} → {@code purchase_ordered}
 * → {@code resolved}, then {@code material_sku}) that only the real query
 * exercises.
 */
class JdbcMaterialShortageQueryPortIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcMaterialShortageQueryPort PORT;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = reporting, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PORT = new JdbcMaterialShortageQueryPort(JDBC);
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
        JDBC.execute("TRUNCATE reporting.material_shortage_view CASCADE");
    }

    @Test
    void findByProductId_round_trips_all_columns() {
        UUID productId = UUID.randomUUID();
        JDBC.update("""
            INSERT INTO reporting.material_shortage_view (
                material_product_id, material_sku, material_name,
                required_quantity, available_quantity, shortage_quantity,
                affected_work_orders_count, affected_sales_orders_count,
                open_purchase_orders_count, incoming_purchase_quantity,
                expected_receipt_date, status
            ) VALUES (?, 'RAW-9', 'Raw 9', 100, 30, 70, 2, 1, 1, 70, DATE '2026-06-01', 'purchase_ordered')
            """,
            productId);

        MaterialShortageView v = PORT.findByProductId(productId).orElseThrow();
        assertThat(v.materialSku()).isEqualTo("RAW-9");
        assertThat(v.requiredQuantity()).isEqualByComparingTo("100");
        assertThat(v.availableQuantity()).isEqualByComparingTo("30");
        assertThat(v.shortageQuantity()).isEqualByComparingTo("70");
        assertThat(v.affectedWorkOrdersCount()).isEqualTo(2);
        assertThat(v.affectedSalesOrdersCount()).isEqualTo(1);
        assertThat(v.openPurchaseOrdersCount()).isEqualTo(1);
        assertThat(v.incomingPurchaseQuantity()).isEqualByComparingTo("70");
        assertThat(v.expectedReceiptDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(v.status()).isEqualTo("purchase_ordered");
    }

    @Test
    void findAll_orders_by_status_priority_then_sku() {
        insertRow("M-B", "open");
        insertRow("M-A", "open");
        insertRow("M-C", "purchase_ordered");
        insertRow("M-D", "resolved");

        assertThat(PORT.findAll())
            .extracting(MaterialShortageView::status)
            .containsExactly("open", "open", "purchase_ordered", "resolved");
        assertThat(PORT.findAll())
            .extracting(MaterialShortageView::materialSku)
            .containsExactly("M-A", "M-B", "M-C", "M-D"); // open group sorted by sku
    }

    @Test
    void findActive_excludes_resolved() {
        insertRow("M-A", "open");
        insertRow("M-B", "purchase_ordered");
        insertRow("M-C", "resolved");

        assertThat(PORT.findActive())
            .extracting(MaterialShortageView::status)
            .containsExactly("open", "purchase_ordered")
            .doesNotContain("resolved");
    }

    private void insertRow(String sku, String status) {
        JDBC.update("""
            INSERT INTO reporting.material_shortage_view (
                material_product_id, material_sku, material_name, shortage_quantity, status
            ) VALUES (?, ?, ?, 10, ?)
            """,
            UUID.randomUUID(), sku, sku + " name", status);
    }
}
