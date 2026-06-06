package com.northwood.finance.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.finance.domain.Payment;
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
import java.time.LocalDate;
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
 * Tier-2 real-Postgres test for {@link JdbcPaymentRepository} — a
 * write-once aggregate (header + allocation lines; no update path). Covers the
 * behaviour only a real DB exhibits:
 *
 * <ul>
 *   <li>{@code recordSupplierPayment} / {@code recordCustomerPayment} →
 *       {@code findById} round-trip of header + allocation incl. the
 *       direction/type/method/status enum {@code dbValue()}/{@code fromDb()}
 *       round-trips and the nullable {@code customer_id}/{@code supplier_id} XOR;</li>
 *   <li>the {@code SupplierPaymentMade} / {@code CustomerPaymentReceived} outbox row;</li>
 *   <li>the {@code maintain_allocation_totals} trigger rolling
 *       {@code payment.amount_allocated} up and flipping the target invoice's
 *       {@code paid_amount}/{@code status} as the allocation lands;</li>
 *   <li>the write-once guard: a second {@code save} (version &gt; 0) →
 *       {@code IllegalStateException}.</li>
 * </ul>
 */
class JdbcPaymentRepositoryIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcPaymentRepository REPO;

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
        REPO = new JdbcPaymentRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
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
            + "finance.supplier_invoice_line, finance.supplier_invoice_header, "
            + "finance.customer_invoice_line, finance.customer_invoice_header, "
            + "finance.outbox_message CASCADE");
    }

    @Test
    void recordSupplierPayment_round_trips_and_trigger_marks_supplier_invoice_paid() {
        UUID supplierId = UUID.randomUUID();
        UUID poId = UUID.randomUUID();
        UUID invoiceId = seedSupplierInvoice("approved", new BigDecimal("100.00"), supplierId, poId);

        Payment payment = Payment.recordSupplierPayment(
            "PAY-SUP-001", supplierId, "Supplier IT", LocalDate.now(), Payment.Method.BANK_TRANSFER,
            "AUD", new BigDecimal("100.00"), invoiceId, poId, "paid");
        save(payment);

        Payment r = REPO.findById(payment.id()).orElseThrow();
        assertThat(r.paymentNumber()).isEqualTo("PAY-SUP-001");
        assertThat(r.paymentDirection()).isEqualTo(Payment.Direction.OUTGOING);
        assertThat(r.paymentType()).isEqualTo(Payment.Type.SUPPLIER_PAYMENT);
        assertThat(r.paymentMethod()).isEqualTo(Payment.Method.BANK_TRANSFER);
        assertThat(r.status()).isEqualTo(Payment.Status.POSTED);
        assertThat(r.supplierId()).isEqualTo(supplierId);
        assertThat(r.customerId()).isNull();
        assertThat(r.amount()).isEqualByComparingTo("100.00");
        assertThat(r.version()).isEqualTo(1L);
        assertThat(r.allocations()).hasSize(1);
        assertThat(r.allocations().get(0).supplierInvoiceHeaderId()).isEqualTo(invoiceId);
        assertThat(r.allocations().get(0).allocatedAmount()).isEqualByComparingTo("100.00");
        assertThat(r.allocations().get(0).status()).isEqualTo(Payment.AllocationStatus.POSTED);

        assertThat(countOutbox(payment.id().value())).isEqualTo(1L); // SupplierPaymentMade
        assertThat(amountAllocated(payment.id().value())).isEqualByComparingTo("100.00");
        assertThat(supplierInvoicePaidAmount(invoiceId)).isEqualByComparingTo("100.00");
        assertThat(supplierInvoiceStatus(invoiceId)).isEqualTo("paid");
    }

    @Test
    void recordCustomerPayment_round_trips_incoming_and_trigger_marks_customer_invoice() {
        UUID customerId = UUID.randomUUID();
        UUID salesOrderId = UUID.randomUUID();
        UUID invoiceId = seedCustomerInvoice("posted", new BigDecimal("200.00"), customerId, salesOrderId);

        Payment payment = Payment.recordCustomerPayment(
            "PAY-CUS-001", customerId, "Customer IT", LocalDate.now(), Payment.Method.CARD,
            "AUD", new BigDecimal("80.00"), invoiceId, salesOrderId, "partially_paid");
        save(payment);

        Payment r = REPO.findById(payment.id()).orElseThrow();
        assertThat(r.paymentDirection()).isEqualTo(Payment.Direction.INCOMING);
        assertThat(r.paymentType()).isEqualTo(Payment.Type.CUSTOMER_PAYMENT);
        assertThat(r.paymentMethod()).isEqualTo(Payment.Method.CARD);
        assertThat(r.customerId()).isEqualTo(customerId);
        assertThat(r.supplierId()).isNull();
        assertThat(r.allocations().get(0).customerInvoiceHeaderId()).isEqualTo(invoiceId);

        assertThat(countOutbox(payment.id().value())).isEqualTo(1L); // CustomerPaymentReceived
        assertThat(customerInvoicePaidAmount(invoiceId)).isEqualByComparingTo("80.00");
        assertThat(customerInvoiceStatus(invoiceId)).isEqualTo("partially_paid");
    }

    @Test
    void update_path_is_rejected_write_once() {
        UUID supplierId = UUID.randomUUID();
        UUID poId = UUID.randomUUID();
        UUID invoiceId = seedSupplierInvoice("approved", new BigDecimal("100.00"), supplierId, poId);
        Payment payment = Payment.recordSupplierPayment(
            "PAY-WO-001", supplierId, "Supplier IT", LocalDate.now(), Payment.Method.BANK_TRANSFER,
            "AUD", new BigDecimal("100.00"), invoiceId, poId, "paid");
        save(payment);

        Payment loaded = REPO.findById(payment.id()).orElseThrow();
        assertThatThrownBy(() -> save(loaded))
            .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void save(Payment p) {
        TX.executeWithoutResult(s -> REPO.save(p));
    }

    private UUID seedSupplierInvoice(String status, BigDecimal total, UUID supplierId, UUID poId) {
        UUID id = UUID.randomUUID();
        String suffix = id.toString().substring(0, 8);
        JDBC.update("""
            INSERT INTO finance.supplier_invoice_header (
                supplier_invoice_header_id, supplier_invoice_number, internal_invoice_number,
                purchase_order_header_id, supplier_id, supplier_name,
                status, match_status, total_amount, version
            ) VALUES (?, ?, ?, ?, ?, 'Supplier IT', ?, 'matched', ?, 1)
            """,
            id, "SUP-" + suffix, "SINV-" + suffix, poId, supplierId, status, total);
        return id;
    }

    private UUID seedCustomerInvoice(String status, BigDecimal total, UUID customerId, UUID salesOrderId) {
        UUID id = UUID.randomUUID();
        JDBC.update("""
            INSERT INTO finance.customer_invoice_header (
                customer_invoice_header_id, invoice_number, sales_order_header_id,
                customer_id, customer_name, status, total_amount, version
            ) VALUES (?, ?, ?, ?, 'Customer IT', ?, ?, 1)
            """,
            id, "CINV-" + id.toString().substring(0, 8), salesOrderId, customerId, status, total);
        return id;
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM finance.outbox_message WHERE aggregate_id = ?", Long.class, aggregateId);
    }

    private BigDecimal amountAllocated(UUID paymentId) {
        return JDBC.queryForObject(
            "SELECT amount_allocated FROM finance.payment WHERE payment_id = ?", BigDecimal.class, paymentId);
    }

    private BigDecimal supplierInvoicePaidAmount(UUID invoiceId) {
        return JDBC.queryForObject(
            "SELECT paid_amount FROM finance.supplier_invoice_header WHERE supplier_invoice_header_id = ?",
            BigDecimal.class, invoiceId);
    }

    private String supplierInvoiceStatus(UUID invoiceId) {
        return JDBC.queryForObject(
            "SELECT status FROM finance.supplier_invoice_header WHERE supplier_invoice_header_id = ?",
            String.class, invoiceId);
    }

    private BigDecimal customerInvoicePaidAmount(UUID invoiceId) {
        return JDBC.queryForObject(
            "SELECT paid_amount FROM finance.customer_invoice_header WHERE customer_invoice_header_id = ?",
            BigDecimal.class, invoiceId);
    }

    private String customerInvoiceStatus(UUID invoiceId) {
        return JDBC.queryForObject(
            "SELECT status FROM finance.customer_invoice_header WHERE customer_invoice_header_id = ?",
            String.class, invoiceId);
    }
}
