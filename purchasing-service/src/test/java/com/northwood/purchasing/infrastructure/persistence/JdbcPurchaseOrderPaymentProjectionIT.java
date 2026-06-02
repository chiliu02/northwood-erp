package com.northwood.purchasing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.purchasing.application.inbox.PurchaseOrderPaymentProjection;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-Postgres test for the JDBC adapter that hosted the
 * {@code invoiced_amount} bug (2026-05-12). The in-memory test
 * harness used by {@code PurchaseToPayHappyPathTest} doesn't have schema CHECK
 * constraints — only a real Postgres exercises the
 * {@code paid_amount &lt;= invoiced_amount} guard that broke saga completion
 * in scenario 7.1.
 *
 * <p>Lifecycle: one container per JVM (static), schema bootstrapped from
 * {@code db/northwood_erp.sql} once. Each test seeds its own
 * {@code purchase_order_header} row via {@link #seedPo(BigDecimal)} and
 * asserts the column transitions via direct SELECT.
 */
class JdbcPurchaseOrderPaymentProjectionIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static PurchaseOrderPaymentProjection PROJECTION;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        loadBaseline();
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        // Mirrors production HikariCP wiring — every connection sees its
        // service schema first, then shared.
        DATA_SOURCE.setConnectionInitSql("SET search_path = purchasing, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        PROJECTION = new JdbcPurchaseOrderPaymentProjection(JDBC);
    }

    /**
     * Run the full baseline schema once. Connects as postgres (superuser)
     * because the baseline creates roles + grants; the test-side DataSource
     * above still connects as postgres but with the production search_path.
     */
    private static void loadBaseline() {
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        applySqlFile(Path.of("..", "db", "northwood_erp_seed.sql"));
    }

    private static void applySqlFile(Path file) {
        String sql;
        try {
            sql = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Cannot read " + file.toAbsolutePath()
                    + " — run failsafe from the module directory (default cwd)", e
            );
        }
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply " + file.getFileName(), e);
        }
    }

    private UUID poId;

    @BeforeEach
    void seedFreshPo() {
        poId = seedPo(new BigDecimal("1000.00"));
    }

    // Seed supplier UUID from northwood_erp.sql — supplier_id has an FK to purchasing.supplier.
    private static final UUID SEED_SUPPLIER_ID =
        UUID.fromString("00000000-0000-7000-8000-000000000040");

    private UUID seedPo(BigDecimal totalAmount) {
        UUID id = UUID.randomUUID();
        JDBC.update("""
            INSERT INTO purchasing.purchase_order_header (
                purchase_order_header_id, purchase_order_number,
                supplier_id, supplier_code, supplier_name,
                currency_code, exchange_rate, exchange_rate_captured_at,
                subtotal_amount, total_amount, status, version
            ) VALUES (?, ?, ?, 'SUP-001', 'Australian Timber Supplies',
                      'AUD', 1.0, now(), ?, ?, 'received', 1)
            """,
            id, "PO-IT-" + id.toString().substring(0, 8),
            SEED_SUPPLIER_ID, totalAmount, totalAmount
        );
        return id;
    }

    private record PoSnapshot(String status, BigDecimal invoiced, BigDecimal paid) {}

    private PoSnapshot snapshot() {
        return JDBC.queryForObject("""
            SELECT status, invoiced_amount, paid_amount
              FROM purchasing.purchase_order_header
             WHERE purchase_order_header_id = ?
            """,
            (rs, n) -> new PoSnapshot(rs.getString("status"),
                rs.getBigDecimal("invoiced_amount"), rs.getBigDecimal("paid_amount")),
            poId
        );
    }

    @Test
    void addInvoicedAmount_bumps_invoiced_and_flips_status_to_invoiced_on_full_coverage() {
        TX.executeWithoutResult(s -> PROJECTION.addInvoicedAmount(poId, new BigDecimal("1000.00")));

        PoSnapshot snap = snapshot();
        assertThat(snap.invoiced()).isEqualByComparingTo("1000.00");
        assertThat(snap.paid()).isEqualByComparingTo("0.00");
        assertThat(snap.status()).isEqualTo("invoiced");
    }

    @Test
    void addInvoicedAmount_flips_status_to_partially_invoiced_on_partial_coverage() {
        TX.executeWithoutResult(s -> PROJECTION.addInvoicedAmount(poId, new BigDecimal("400.00")));

        PoSnapshot snap = snapshot();
        assertThat(snap.invoiced()).isEqualByComparingTo("400.00");
        assertThat(snap.status()).isEqualTo("partially_invoiced");
    }

    @Test
    void addInvoicedAmount_is_additive_across_multiple_invoices() {
        TX.executeWithoutResult(s -> PROJECTION.addInvoicedAmount(poId, new BigDecimal("400.00")));
        TX.executeWithoutResult(s -> PROJECTION.addInvoicedAmount(poId, new BigDecimal("600.00")));

        PoSnapshot snap = snapshot();
        assertThat(snap.invoiced()).isEqualByComparingTo("1000.00");
        assertThat(snap.status()).isEqualTo("invoiced");
    }

    @Test
    void markFullyPaid_without_prior_invoice_violates_schema_CHECK() {
        // This is the exact bug class fixed 2026-05-12 — paid_amount
        // would be set to total_amount (1000) but invoiced_amount is still 0,
        // violating CHECK (paid_amount <= invoiced_amount). Hardcoded against
        // any future drift in markFullyPaid's SQL.
        assertThatThrownBy(() ->
            TX.executeWithoutResult(s -> PROJECTION.markFullyPaid(poId))
        )
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("violates check constraint");
    }

    @Test
    void markFullyPaid_succeeds_after_invoice_covers_paid() {
        TX.executeWithoutResult(s -> PROJECTION.addInvoicedAmount(poId, new BigDecimal("1000.00")));
        TX.executeWithoutResult(s -> PROJECTION.markFullyPaid(poId));

        PoSnapshot snap = snapshot();
        assertThat(snap.invoiced()).isEqualByComparingTo("1000.00");
        assertThat(snap.paid()).isEqualByComparingTo("1000.00");
        assertThat(snap.status()).isEqualTo("paid");
    }

    @Test
    void addPartialPayment_bumps_paid_within_invoiced_envelope() {
        TX.executeWithoutResult(s -> PROJECTION.addInvoicedAmount(poId, new BigDecimal("1000.00")));
        TX.executeWithoutResult(s -> PROJECTION.addPartialPayment(poId, new BigDecimal("400.00")));

        PoSnapshot snap = snapshot();
        assertThat(snap.paid()).isEqualByComparingTo("400.00");
        // status stays at 'invoiced' — only markFullyPaid flips to 'paid'.
        assertThat(snap.status()).isEqualTo("invoiced");
    }

    @Test
    void addPartialPayment_exceeding_invoiced_violates_schema_CHECK() {
        TX.executeWithoutResult(s -> PROJECTION.addInvoicedAmount(poId, new BigDecimal("500.00")));
        assertThatThrownBy(() ->
            TX.executeWithoutResult(s -> PROJECTION.addPartialPayment(poId, new BigDecimal("600.00")))
        )
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("violates check constraint");
    }
}
