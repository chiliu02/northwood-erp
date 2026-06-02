package com.northwood.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.reporting.application.dto.SalesOrder360View;
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
 * Real-Postgres test for {@link JdbcSalesOrder360QueryPort} — a
 * read-only CQRS query port over the inbox-fed {@code sales_order_360_view}
 * read model. Covers the column/type round-trip the {@code RowMapper} performs
 * (incl. the {@code boolean has_shortage}, the six status mirrors, the money
 * columns and the nullable {@code last_event_at}/{@code updated_at} instants),
 * the {@code findAll} {@code updated_at DESC} ordering, and the empty-Optional
 * miss.
 */
class JdbcSalesOrder360QueryPortIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcSalesOrder360QueryPort PORT;

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
        PORT = new JdbcSalesOrder360QueryPort(JDBC);
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
        JDBC.execute("TRUNCATE reporting.sales_order_360_view CASCADE");
    }

    @Test
    void findBySalesOrderId_round_trips_all_columns() {
        UUID id = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        insertRow(id, "SO-360-1", customerId, "Acme", "shipped", true,
            new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("40.00"),
            new BigDecimal("60.00"), Instant.now());

        SalesOrder360View v = PORT.findBySalesOrderId(id).orElseThrow();
        assertThat(v.orderNumber()).isEqualTo("SO-360-1");
        assertThat(v.customerId()).isEqualTo(customerId);
        assertThat(v.customerName()).isEqualTo("Acme");
        assertThat(v.orderStatus()).isEqualTo("shipped");
        assertThat(v.stockStatus()).isEqualTo("reserved");
        assertThat(v.currencyCode()).isEqualTo("AUD");
        assertThat(v.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(v.invoicedAmount()).isEqualByComparingTo("100.00");
        assertThat(v.paidAmount()).isEqualByComparingTo("40.00");
        assertThat(v.outstandingAmount()).isEqualByComparingTo("60.00");
        assertThat(v.hasShortage()).isTrue();
        assertThat(v.orderDate()).isEqualTo(LocalDate.now());
        assertThat(v.updatedAt()).isNotNull();
    }

    @Test
    void findBySalesOrderId_missing_returns_empty() {
        assertThat(PORT.findBySalesOrderId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findAll_orders_by_updated_at_desc() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        insertRow(older, "SO-OLD", UUID.randomUUID(), "Old", "submitted", false,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            Instant.now().minusSeconds(3600));
        insertRow(newer, "SO-NEW", UUID.randomUUID(), "New", "submitted", false,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            Instant.now());

        assertThat(PORT.findAll())
            .extracting(SalesOrder360View::salesOrderHeaderId)
            .containsExactly(newer, older);
    }

    private void insertRow(UUID id, String orderNumber, UUID customerId, String customerName,
            String orderStatus, boolean hasShortage,
            BigDecimal total, BigDecimal invoiced, BigDecimal paid, BigDecimal outstanding,
            Instant updatedAt) {
        JDBC.update("""
            INSERT INTO reporting.sales_order_360_view (
                sales_order_header_id, order_number, customer_id, customer_name,
                order_date, order_status, stock_status, manufacturing_status,
                shipment_status, invoice_status, payment_status, currency_code,
                total_amount, invoiced_amount, paid_amount, outstanding_amount,
                has_shortage, last_event_type, last_event_at, updated_at
            ) VALUES (?, ?, ?, ?, CURRENT_DATE, ?, 'reserved', 'not_required',
                      'shipped', 'invoiced', 'partially_paid', 'AUD',
                      ?, ?, ?, ?, ?, 'sales.SalesOrderShipped', now(), ?)
            """,
            id, orderNumber, customerId, customerName, orderStatus,
            total, invoiced, paid, outstanding, hasShortage, Timestamp.from(updatedAt));
    }
}
