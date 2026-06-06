package com.northwood.finance.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.CustomerInvoiceLine;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * Tier-2 real-Postgres test for {@link JdbcCustomerInvoiceRepository} —
 * a write-once aggregate (no update path; no optimistic lock). Covers the
 * behaviour only a real DB exhibits:
 *
 * <ul>
 *   <li>{@code create} → {@code findById} round-trip of header + line incl.
 *       status {@code dbValue()}/{@code fromDb()} + the {@code CustomerInvoiceCreated}
 *       outbox row;</li>
 *   <li>the write-once guard: a second {@code save} (version &gt; 0) →
 *       {@code IllegalStateException};</li>
 *   <li>the {@code maintain_allocation_totals} trigger flipping {@code paid_amount}
 *       and {@code status} ({@code posted → partially_paid → paid}) as payment
 *       allocations land against the invoice — the AR running total maintained
 *       by the engine, not by Java.</li>
 * </ul>
 */
class JdbcCustomerInvoiceRepositoryIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcCustomerInvoiceRepository REPO;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = finance, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcCustomerInvoiceRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
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
        JDBC.execute("TRUNCATE finance.payment_allocation, finance.payment, "
            + "finance.customer_invoice_line, finance.customer_invoice_header, "
            + "finance.outbox_message CASCADE");
    }

    @Test
    void create_then_findById_round_trips_header_and_line_and_emits_outbox() {
        UUID salesOrderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CustomerInvoice ci = postedInvoice("INV-RT-001", salesOrderId, customerId, productId);
        save(ci);

        CustomerInvoice r = REPO.findById(ci.id()).orElseThrow();
        assertThat(r.invoiceNumber()).isEqualTo("INV-RT-001");
        assertThat(r.salesOrderHeaderId()).isEqualTo(salesOrderId);
        assertThat(r.customerId()).isEqualTo(customerId);
        assertThat(r.status()).isEqualTo(CustomerInvoice.Status.POSTED);
        assertThat(r.currencyCode()).isEqualTo("AUD");
        assertThat(r.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(r.version()).isEqualTo(1L);
        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).productId()).isEqualTo(productId);
        assertThat(r.lines().get(0).quantity()).isEqualByComparingTo("2");
        assertThat(r.lines().get(0).lineTotal()).isEqualByComparingTo("100.00");
        assertThat(countOutbox(ci.id().value())).isEqualTo(1L); // CustomerInvoiceCreated
    }

    @Test
    void update_path_is_rejected_write_once() {
        CustomerInvoice ci = postedInvoice("INV-WO-001", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        save(ci);

        // findById returns the row at version 1 — re-saving hits the version>0
        // branch, which the write-once aggregate explicitly rejects.
        CustomerInvoice loaded = REPO.findById(ci.id()).orElseThrow();
        assertThatThrownBy(() -> save(loaded))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allocation_trigger_flips_paid_amount_then_status_posted_partially_paid_paid() {
        CustomerInvoice ci = postedInvoice("INV-PAY-001", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        save(ci);
        UUID invoiceId = ci.id().value();

        UUID paymentId = insertCustomerPaymentParent(new BigDecimal("100.00"));

        // First allocation: 40 of 100 → partially_paid.
        insertAllocation(paymentId, invoiceId, new BigDecimal("40.00"));
        assertThat(paidAmount(invoiceId)).isEqualByComparingTo("40.00");
        assertThat(status(invoiceId)).isEqualTo("partially_paid");

        // Second allocation: remaining 60 → paid.
        insertAllocation(paymentId, invoiceId, new BigDecimal("60.00"));
        assertThat(paidAmount(invoiceId)).isEqualByComparingTo("100.00");
        assertThat(status(invoiceId)).isEqualTo("paid");

        // The trigger also rolls up amount_allocated on the payment side.
        BigDecimal allocated = JDBC.queryForObject(
            "SELECT amount_allocated FROM finance.payment WHERE payment_id = ?", BigDecimal.class, paymentId);
        assertThat(allocated).isEqualByComparingTo("100.00");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static CustomerInvoice postedInvoice(String invoiceNumber, UUID salesOrderId, UUID customerId, UUID productId) {
        CustomerInvoiceLine line = new CustomerInvoiceLine(
            UUID.randomUUID(), 1, UUID.randomUUID(), productId, "FG-IT-1", "Finished 1",
            new BigDecimal("2"), new BigDecimal("50.000000"), BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("100.00"));
        return CustomerInvoice.create(
            invoiceNumber, salesOrderId, customerId, "CUST-INV-IT", "Customer IT", "AUD", List.of(line));
    }

    private void save(CustomerInvoice ci) {
        TX.executeWithoutResult(s -> REPO.save(ci));
    }

    private UUID insertCustomerPaymentParent(BigDecimal amount) {
        UUID paymentId = UUID.randomUUID();
        JDBC.update("""
            INSERT INTO finance.payment (
                payment_id, payment_number, payment_direction, payment_type,
                customer_id, party_name, payment_method, amount, status, version
            ) VALUES (?, ?, 'incoming', 'customer_payment', ?, 'Customer IT', 'bank_transfer', ?, 'posted', 1)
            """,
            paymentId, "PAY-IT-" + paymentId.toString().substring(0, 8), UUID.randomUUID(), amount);
        return paymentId;
    }

    private void insertAllocation(UUID paymentId, UUID customerInvoiceId, BigDecimal amount) {
        JDBC.update("""
            INSERT INTO finance.payment_allocation (
                allocation_id, payment_id, customer_invoice_header_id, allocated_amount, status
            ) VALUES (?, ?, ?, ?, 'posted')
            """,
            UUID.randomUUID(), paymentId, customerInvoiceId, amount);
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM finance.outbox_message WHERE aggregate_id = ?", Long.class, aggregateId);
    }

    private BigDecimal paidAmount(UUID invoiceId) {
        return JDBC.queryForObject(
            "SELECT paid_amount FROM finance.customer_invoice_header WHERE customer_invoice_header_id = ?",
            BigDecimal.class, invoiceId);
    }

    private String status(UUID invoiceId) {
        return JDBC.queryForObject(
            "SELECT status FROM finance.customer_invoice_header WHERE customer_invoice_header_id = ?",
            String.class, invoiceId);
    }
}
