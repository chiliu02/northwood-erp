package com.northwood.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.finance.domain.events.SupplierPaymentMade;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-Postgres test for {@link JdbcPurchaseOrderTrackingProjection} (REQ-RPT-010): the PO Tracking
 * view's money-flow bars (ordered / received / invoiced / paid) accumulate as the purchasing →
 * inventory → finance events land, and the receipt / invoice / payment lozenges + {@code po_status}
 * advance accordingly. {@code outstanding_amount = ordered − paid}; a draft PO is approved to
 * {@code sent} and may be cancelled.
 */
class JdbcPurchaseOrderTrackingProjectionIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcPurchaseOrderTrackingProjection PROJECTION;

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
        PROJECTION = new JdbcPurchaseOrderTrackingProjection(JDBC);
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
        JDBC.execute("TRUNCATE reporting.purchase_order_tracking_view CASCADE");
    }

    @Test
    void money_flow_bars_accumulate_ordered_received_invoiced_paid() {
        UUID po = UUID.randomUUID();
        PROJECTION.createFromPurchaseOrder(po, "PO-RPT-1", UUID.randomUUID(), "Pinewood Supplies",
            "sent", "AUD", new BigDecimal("1000.00"), null, Instant.now(), "tom");
        assertThat(amount(po, "ordered_amount")).isEqualByComparingTo("1000.00");
        assertThat(amount(po, "outstanding_amount")).isEqualByComparingTo("1000.00");
        assertThat(status(po, "receipt_status")).isEqualTo("not_received");
        assertThat(status(po, "invoice_status")).isEqualTo("not_invoiced");
        assertThat(status(po, "payment_status")).isEqualTo("unpaid");

        // Partial then full goods receipt → receipt_status + po_status advance.
        PROJECTION.recordGoodsReceived(po, UUID.randomUUID(), new BigDecimal("600.00"), Instant.now(), "mike");
        assertThat(status(po, "receipt_status")).isEqualTo("partially_received");
        PROJECTION.recordGoodsReceived(po, UUID.randomUUID(), new BigDecimal("400.00"), Instant.now(), "mike");
        assertThat(status(po, "receipt_status")).isEqualTo("received");
        assertThat(status(po, "po_status")).isEqualTo("received");

        // Supplier invoice approved (3-way matched).
        PROJECTION.recordInvoiceApproved(po, UUID.randomUUID(), new BigDecimal("1000.00"), Instant.now(), "olivia");
        assertThat(status(po, "invoice_status")).isEqualTo("invoiced");
        assertThat(status(po, "match_status")).isEqualTo("matched");

        // Partial then full payment → payment_status + po_status=paid, outstanding drains.
        PROJECTION.recordPayment(po, UUID.randomUUID(), new BigDecimal("600.00"),
            SupplierPaymentMade.INVOICE_STATUS_PARTIALLY_PAID, Instant.now(), "olivia");
        assertThat(status(po, "payment_status")).isEqualTo("partially_paid");
        assertThat(amount(po, "outstanding_amount")).isEqualByComparingTo("400.00");
        PROJECTION.recordPayment(po, UUID.randomUUID(), new BigDecimal("400.00"),
            SupplierPaymentMade.INVOICE_STATUS_PAID, Instant.now(), "olivia");
        assertThat(status(po, "payment_status")).isEqualTo("paid");
        assertThat(status(po, "po_status")).isEqualTo("paid");
        assertThat(amount(po, "outstanding_amount")).isEqualByComparingTo("0.00");
    }

    @Test
    void draft_po_is_approved_to_sent() {
        UUID po = UUID.randomUUID();
        PROJECTION.createFromPurchaseOrder(po, "PO-RPT-2", UUID.randomUUID(), "Pinewood Supplies",
            "draft", "AUD", new BigDecimal("500.00"), null, Instant.now(), "tom");
        assertThat(status(po, "po_status")).isEqualTo("draft");

        PROJECTION.recordPoApproved(po, Instant.now(), "priya");
        assertThat(status(po, "po_status")).isEqualTo("sent");
    }

    @Test
    void a_draft_po_can_be_cancelled() {
        UUID po = UUID.randomUUID();
        PROJECTION.createFromPurchaseOrder(po, "PO-RPT-3", UUID.randomUUID(), "Pinewood Supplies",
            "draft", "AUD", new BigDecimal("500.00"), null, Instant.now(), "tom");

        PROJECTION.recordPoCancelled(po, Instant.now(), "priya");
        assertThat(status(po, "po_status")).isEqualTo("cancelled");
    }

    private BigDecimal amount(UUID po, String column) {
        return JDBC.queryForObject(
            "SELECT " + column + " FROM reporting.purchase_order_tracking_view WHERE purchase_order_header_id = ?",
            BigDecimal.class, po);
    }

    private String status(UUID po, String column) {
        return JDBC.queryForObject(
            "SELECT " + column + " FROM reporting.purchase_order_tracking_view WHERE purchase_order_header_id = ?",
            String.class, po);
    }
}
