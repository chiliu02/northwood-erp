package com.northwood.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.inventory.domain.events.StockReserved;
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
 * Real-Postgres test for {@link JdbcSalesOrder360Projection}'s status lifecycle:
 * {@code order_status} advances {@code submitted → ready_to_ship → shipped →
 * completed} as the driving events land, and — because reporting consumes
 * several topics and so can see events out of order — each advance is
 * <b>forward-only</b>: a late event never downgrades a later state, and a
 * terminal {@code 'cancelled'} is preserved. Also covers the deposit/pegged/
 * refund derivations: a paid deposit does not complete the order (its balance is
 * still due), reaching {@code ready_to_ship} lifts a pegged buy-to-order line out
 * of {@code 'failed'} stock, and cancelling a paid order releases its reservation
 * and marks the payment {@code 'refunded'}.
 */
class JdbcSalesOrder360ProjectionIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcSalesOrder360Projection PROJECTION;

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
        PROJECTION = new JdbcSalesOrder360Projection(JDBC);
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
    void order_status_progresses_submitted_to_completed() {
        UUID id = UUID.randomUUID();
        createOrder(id);
        assertThat(orderStatus(id)).isEqualTo("submitted");

        PROJECTION.recordReadyToShip(id, Instant.now(), "system");
        assertThat(orderStatus(id)).isEqualTo("ready_to_ship");

        PROJECTION.recordShipment(id, Instant.now(), "mike");
        assertThat(orderStatus(id)).isEqualTo("shipped");

        PROJECTION.recordPayment(id, new BigDecimal("650.00"),
            CustomerPaymentReceived.INVOICE_STATUS_PAID, Instant.now(), "olivia");
        assertThat(orderStatus(id)).isEqualTo("completed");
    }

    @Test
    void late_ready_to_ship_does_not_downgrade_shipped() {
        UUID id = UUID.randomUUID();
        createOrder(id);
        PROJECTION.recordShipment(id, Instant.now(), "mike");
        assertThat(orderStatus(id)).isEqualTo("shipped");

        // A ready_to_ship event that arrives after the shipment (cross-topic
        // reordering) must not roll the status back.
        PROJECTION.recordReadyToShip(id, Instant.now(), "system");
        assertThat(orderStatus(id)).isEqualTo("shipped");
    }

    @Test
    void partial_payment_does_not_complete() {
        UUID id = UUID.randomUUID();
        createOrder(id);
        PROJECTION.recordShipment(id, Instant.now(), "mike");

        PROJECTION.recordPayment(id, new BigDecimal("300.00"),
            CustomerPaymentReceived.INVOICE_STATUS_PARTIALLY_PAID, Instant.now(), "olivia");

        assertThat(orderStatus(id)).isEqualTo("shipped");
        assertThat(JDBC.queryForObject(
            "SELECT payment_status FROM reporting.sales_order_360_view WHERE sales_order_header_id = ?",
            String.class, id)).isEqualTo("partially_paid");
    }

    @Test
    void cancellation_is_preserved_against_later_advances() {
        UUID id = UUID.randomUUID();
        createOrder(id);
        PROJECTION.recordCancellation(id, Instant.now(), "sam");
        assertThat(orderStatus(id)).isEqualTo("cancelled");

        // Forward-advance attempts after cancellation are no-ops on order_status.
        PROJECTION.recordReadyToShip(id, Instant.now(), "system");
        PROJECTION.recordShipment(id, Instant.now(), "mike");
        PROJECTION.recordPayment(id, new BigDecimal("650.00"),
            CustomerPaymentReceived.INVOICE_STATUS_PAID, Instant.now(), "olivia");
        assertThat(orderStatus(id)).isEqualTo("cancelled");
    }

    @Test
    void deposit_payment_does_not_complete_order_until_balance_is_paid() {
        UUID id = UUID.randomUUID();
        createOrder(id); // total 650.00
        PROJECTION.recordReadyToShip(id, Instant.now(), "system");

        // Deposit invoice paid in full (invoiceStatusAfter = PAID) but it is only
        // part of the order total — the order is NOT complete and stays cancellable.
        PROJECTION.recordPayment(id, new BigDecimal("325.00"),
            CustomerPaymentReceived.INVOICE_STATUS_PAID, Instant.now(), "olivia");
        assertThat(orderStatus(id)).isEqualTo("ready_to_ship");
        assertThat(paymentStatus(id)).isEqualTo("partially_paid");

        // Balance paid → order_status completes and payment settles in full.
        PROJECTION.recordShipment(id, Instant.now(), "mike");
        PROJECTION.recordPayment(id, new BigDecimal("325.00"),
            CustomerPaymentReceived.INVOICE_STATUS_PAID, Instant.now(), "olivia");
        assertThat(orderStatus(id)).isEqualTo("completed");
        assertThat(paymentStatus(id)).isEqualTo("paid");
    }

    @Test
    void ready_to_ship_lifts_a_pegged_line_out_of_failed_stock() {
        UUID id = UUID.randomUUID();
        createOrder(id);
        // Buy-to-order: the immediate reservation fails (0 free stock); the
        // dedicated supply is pegged at goods-receipt and the saga is advanced via
        // ReplenishmentFulfilled — no fresh inventory.StockReserved ever arrives.
        PROJECTION.recordStockReserved(id, StockReserved.STATUS_FAILED, Instant.now(), "system");
        assertThat(stockStatus(id)).isEqualTo("failed");

        PROJECTION.recordReadyToShip(id, Instant.now(), "system");
        assertThat(stockStatus(id)).isEqualTo("reserved");
    }

    @Test
    void cancelling_a_paid_deposit_releases_stock_and_marks_refunded() {
        UUID id = UUID.randomUUID();
        createOrder(id);
        PROJECTION.recordReadyToShip(id, Instant.now(), "system"); // stock → reserved
        PROJECTION.recordPayment(id, new BigDecimal("325.00"),
            CustomerPaymentReceived.INVOICE_STATUS_PAID, Instant.now(), "olivia");
        assertThat(stockStatus(id)).isEqualTo("reserved");

        PROJECTION.recordCancellation(id, Instant.now(), "sam");
        assertThat(orderStatus(id)).isEqualTo("cancelled");
        assertThat(stockStatus(id)).isEqualTo("released");
        assertThat(paymentStatus(id)).isEqualTo("refunded");
    }

    @Test
    void cancelling_an_unpaid_order_does_not_mark_refunded() {
        UUID id = UUID.randomUUID();
        createOrder(id);
        PROJECTION.recordCancellation(id, Instant.now(), "sam");
        assertThat(orderStatus(id)).isEqualTo("cancelled");
        // No money taken (on-shipment/COD cancelled pre-invoice) → nothing refunded.
        assertThat(paymentStatus(id)).isEqualTo("pending");
    }

    @Test
    void stock_status_records_outcome_and_full_reservation_is_sticky() {
        UUID id = UUID.randomUUID();
        createOrder(id);
        assertThat(stockStatus(id)).isEqualTo("pending");

        PROJECTION.recordStockReserved(id, StockReserved.STATUS_PARTIALLY_RESERVED, Instant.now(), "system");
        assertThat(stockStatus(id)).isEqualTo("partially_reserved");

        // A later full reservation advances it; then a stale partial must not roll it back.
        PROJECTION.recordStockReserved(id, StockReserved.STATUS_RESERVED, Instant.now(), "system");
        assertThat(stockStatus(id)).isEqualTo("reserved");
        PROJECTION.recordStockReserved(id, StockReserved.STATUS_PARTIALLY_RESERVED, Instant.now(), "system");
        assertThat(stockStatus(id)).isEqualTo("reserved");
    }

    @Test
    void stock_only_order_marks_manufacturing_not_required() {
        UUID id = UUID.randomUUID();
        createOrder(id);
        // No manufacturing event; reaching ready_to_ship implies stock-only.
        PROJECTION.recordReadyToShip(id, Instant.now(), "system");
        assertThat(manufacturingStatus(id)).isEqualTo("not_required");
    }

    @Test
    void make_to_order_keeps_manufacturing_completed_through_ready_to_ship() {
        UUID id = UUID.randomUUID();
        createOrder(id);
        PROJECTION.recordManufacturingCompleted(id, Instant.now(), "linda");
        assertThat(manufacturingStatus(id)).isEqualTo("completed");

        PROJECTION.recordReadyToShip(id, Instant.now(), "system");
        assertThat(manufacturingStatus(id)).isEqualTo("completed");
    }

    private void createOrder(UUID id) {
        PROJECTION.createFromOrder(
            id, "SO-360-LIFECYCLE", UUID.randomUUID(), "Sydney Home Living",
            LocalDate.now(), null, "AUD", new BigDecimal("650.00"),
            "on_shipment",
            Instant.now(), "sales.SalesOrderPlaced", "sarah");
    }

    private String orderStatus(UUID id) {
        return JDBC.queryForObject(
            "SELECT order_status FROM reporting.sales_order_360_view WHERE sales_order_header_id = ?",
            String.class, id);
    }

    private String stockStatus(UUID id) {
        return JDBC.queryForObject(
            "SELECT stock_status FROM reporting.sales_order_360_view WHERE sales_order_header_id = ?",
            String.class, id);
    }

    private String paymentStatus(UUID id) {
        return JDBC.queryForObject(
            "SELECT payment_status FROM reporting.sales_order_360_view WHERE sales_order_header_id = ?",
            String.class, id);
    }

    private String manufacturingStatus(UUID id) {
        return JDBC.queryForObject(
            "SELECT manufacturing_status FROM reporting.sales_order_360_view WHERE sales_order_header_id = ?",
            String.class, id);
    }
}
