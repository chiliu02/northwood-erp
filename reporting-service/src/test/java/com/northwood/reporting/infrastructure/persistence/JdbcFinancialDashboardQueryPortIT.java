package com.northwood.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.reporting.application.dto.FinancialDashboardSnapshot;
import com.northwood.reporting.application.dto.FinancialDashboardView;
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
 * Real-Postgres test for {@link JdbcFinancialDashboardQueryPort} —
 * the one reporting query port with genuine aggregation. Covers the flat
 * {@code findByDate}/{@code findByCurrency} round-trip over
 * {@code financial_dashboard_daily}, and (the meaty part) {@code findSnapshot}'s
 * cross-read-model aggregation:
 *
 * <ul>
 *   <li>AR = SUM(GREATEST(invoiced − paid, 0)) + open-SO COUNT FILTER over
 *       {@code sales_order_360_view};</li>
 *   <li>AP = same over {@code purchase_order_tracking_view};</li>
 *   <li>open-WO COUNT over {@code production_planning_board} (currency-blind);</li>
 *   <li>inventory_value = SUM(on_hand × standard_cost) via the INNER JOIN of
 *       {@code available_to_promise_view} × {@code product_card} (rows missing a
 *       cost card drop out; off-currency cost cards are filtered).</li>
 * </ul>
 */
class JdbcFinancialDashboardQueryPortIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcFinancialDashboardQueryPort PORT;

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
        PORT = new JdbcFinancialDashboardQueryPort(JDBC);
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
        JDBC.execute("TRUNCATE reporting.financial_dashboard_daily, reporting.sales_order_360_view, "
            + "reporting.purchase_order_tracking_view, reporting.production_planning_board, "
            + "reporting.available_to_promise_view, reporting.product_card CASCADE");
    }

    @Test
    void findByDate_and_findByCurrency_round_trip() {
        LocalDate today = LocalDate.now();
        JDBC.update("""
            INSERT INTO reporting.financial_dashboard_daily (
                dashboard_date, currency_code, sales_revenue, cost_of_goods_sold, gross_profit,
                accounts_receivable, accounts_payable, open_sales_orders_count
            ) VALUES (?, 'AUD', 1000.00, 600.00, 400.00, 250.00, 120.00, 3)
            """, java.sql.Date.valueOf(today));

        FinancialDashboardView v = PORT.findByDate(today, "AUD").orElseThrow();
        assertThat(v.salesRevenue()).isEqualByComparingTo("1000.00");
        assertThat(v.costOfGoodsSold()).isEqualByComparingTo("600.00");
        assertThat(v.grossProfit()).isEqualByComparingTo("400.00");
        assertThat(v.accountsReceivable()).isEqualByComparingTo("250.00");
        assertThat(v.openSalesOrdersCount()).isEqualTo(3);
        assertThat(v.updatedAt()).isNotNull();

        assertThat(PORT.findByCurrency("AUD"))
            .extracting(FinancialDashboardView::dashboardDate).containsExactly(today);
    }

    @Test
    void findByDate_missing_returns_empty() {
        assertThat(PORT.findByDate(LocalDate.now(), "AUD")).isEmpty();
    }

    @Test
    void findSnapshot_aggregates_across_read_models() {
        // sales_order_360_view (AUD): AR = 60 + 0 + 30 = 90; open SO = 1 (only the
        // shipped/outstanding one — the cancelled row is excluded from the count
        // but still contributes to AR). The USD row is filtered out entirely.
        seedSo("AUD", "100.00", "40.00", "60.00", "shipped");
        seedSo("AUD", "50.00", "50.00", "0.00", "completed");
        seedSo("AUD", "30.00", "0.00", "30.00", "cancelled");
        seedSo("USD", "1000.00", "0.00", "1000.00", "shipped");

        // purchase_order_tracking_view (AUD): AP = 150; open PO = 1 (the 'paid'
        // PO is excluded from the count).
        seedPo("AUD", "200.00", "50.00", "sent");
        seedPo("AUD", "100.00", "100.00", "paid");

        // production_planning_board: open WO = 2 (completed excluded), currency-blind.
        seedWo("in_progress");
        seedWo("completed");
        seedWo("released");

        // inventory_value (AUD) = 10×5 + 4×7 = 78. The USD card is filtered; the
        // ATP row with no card drops out of the INNER JOIN.
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        seedAtp(a, "10");
        seedAtp(b, "4");
        seedAtp(c, "100");
        seedAtp(d, "5");
        seedProductCard(a, "5.000000", "AUD");
        seedProductCard(b, "7.000000", "AUD");
        seedProductCard(c, "2.000000", "USD");
        // d: no product_card row → excluded from inventory_value.

        FinancialDashboardSnapshot snap = PORT.findSnapshot("AUD");
        assertThat(snap.currencyCode()).isEqualTo("AUD");
        assertThat(snap.accountsReceivable()).isEqualByComparingTo("90");
        assertThat(snap.accountsPayable()).isEqualByComparingTo("150");
        assertThat(snap.inventoryValue()).isEqualByComparingTo("78");
        assertThat(snap.wipValue()).isEqualByComparingTo("0");
        assertThat(snap.openSalesOrdersCount()).isEqualTo(1);
        assertThat(snap.openPurchaseOrdersCount()).isEqualTo(1);
        assertThat(snap.openWorkOrdersCount()).isEqualTo(2);
        assertThat(snap.asOf()).isNotNull();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void seedSo(String currency, String invoiced, String paid, String outstanding, String orderStatus) {
        JDBC.update("""
            INSERT INTO reporting.sales_order_360_view (
                sales_order_header_id, order_number, customer_id, customer_name,
                order_date, order_status, stock_status, manufacturing_status,
                shipment_status, invoice_status, payment_status, currency_code,
                invoiced_amount, paid_amount, outstanding_amount
            ) VALUES (?, ?, ?, 'Cust', CURRENT_DATE, ?, 'reserved', 'not_required',
                      'pending', 'invoiced', 'partially_paid', ?, ?, ?, ?)
            """,
            UUID.randomUUID(), "SO-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
            orderStatus, currency, new BigDecimal(invoiced), new BigDecimal(paid), new BigDecimal(outstanding));
    }

    private void seedPo(String currency, String invoiced, String paid, String poStatus) {
        JDBC.update("""
            INSERT INTO reporting.purchase_order_tracking_view (
                purchase_order_header_id, purchase_order_number, supplier_id, supplier_name,
                po_status, order_date, currency_code, invoiced_amount, paid_amount
            ) VALUES (?, ?, ?, 'Supp', ?, CURRENT_DATE, ?, ?, ?)
            """,
            UUID.randomUUID(), "PO-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
            poStatus, currency, new BigDecimal(invoiced), new BigDecimal(paid));
    }

    private void seedWo(String status) {
        JDBC.update("""
            INSERT INTO reporting.production_planning_board (
                work_order_id, work_order_number, finished_product_id,
                finished_product_sku, finished_product_name, planned_quantity,
                work_order_status, material_status
            ) VALUES (?, ?, ?, 'FG', 'Finished', 1, ?, 'reserved')
            """,
            UUID.randomUUID(), "WO-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(), status);
    }

    private void seedAtp(UUID productId, String onHand) {
        JDBC.update("""
            INSERT INTO reporting.available_to_promise_view (
                product_id, product_sku, product_name, on_hand_quantity
            ) VALUES (?, ?, 'Product', ?)
            """,
            productId, "SKU-" + productId.toString().substring(0, 8), new BigDecimal(onHand));
    }

    private void seedProductCard(UUID productId, String standardCost, String currency) {
        JDBC.update(
            "INSERT INTO reporting.product_card (product_id, standard_cost, currency_code) VALUES (?, ?, ?)",
            productId, new BigDecimal(standardCost), currency);
    }
}
