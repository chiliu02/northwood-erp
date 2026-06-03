package com.northwood.finance.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.finance.domain.SupplierInvoice;
import com.northwood.finance.domain.SupplierInvoiceId;
import com.northwood.finance.domain.SupplierInvoiceLine;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * Tier-2 real-Postgres test for {@link JdbcSupplierInvoiceRepository} —
 * a <em>mutable</em> aggregate (insert + the {@code manualApprove}/{@code manualReject}
 * status-update path). Covers the behaviour only a real DB exhibits:
 *
 * <ul>
 *   <li>{@code record(MATCHED)} → {@code findById} round-trip incl.
 *       status + match_status enum {@code dbValue()}/{@code fromDb()} + the
 *       {@code SupplierInvoiceApproved} outbox row, then surfacing via
 *       {@code findByStatus} / {@code findPaymentSnapshot};</li>
 *   <li>{@code manualApprove} flipping {@code three_way_match_failed → approved}
 *       through the update path (version bump + {@code approved_at}) + outbox;</li>
 *   <li>{@code manualReject} flipping to {@code cancelled} + the
 *       {@code SupplierInvoiceRejected} outbox row;</li>
 *   <li>the missing-row update raising {@code IllegalStateException} via
 *       {@code Assert.state} — <em>not</em> {@code OptimisticLockingFailureException}
 *       (this aggregate's update has no {@code WHERE version = ?} guard).</li>
 * </ul>
 */
class JdbcSupplierInvoiceRepositoryIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcSupplierInvoiceRepository REPO;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = finance, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcSupplierInvoiceRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
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
        JDBC.execute("TRUNCATE finance.supplier_invoice_line, finance.supplier_invoice_header, "
            + "finance.outbox_message CASCADE");
    }

    @Test
    void record_matched_round_trips_and_emits_approved_outbox_and_surfaces_in_lookups() {
        UUID supplierId = UUID.randomUUID();
        UUID poId = UUID.randomUUID();
        SupplierInvoice si = recordedInvoice("SINV-MATCH-1", supplierId, poId, SupplierInvoice.MatchStatus.MATCHED);
        save(si);

        SupplierInvoice r = REPO.findById(si.id()).orElseThrow();
        assertThat(r.internalInvoiceNumber()).isEqualTo("SINV-MATCH-1");
        assertThat(r.purchaseOrderHeaderId()).isEqualTo(poId);
        assertThat(r.supplierId()).isEqualTo(supplierId);
        assertThat(r.status()).isEqualTo(SupplierInvoice.Status.APPROVED);
        assertThat(r.matchStatus()).isEqualTo(SupplierInvoice.MatchStatus.MATCHED);
        assertThat(r.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(r.version()).isEqualTo(1L);
        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).lineTotal()).isEqualByComparingTo("100.00");

        assertThat(countOutbox(si.id().value())).isEqualTo(1L); // SupplierInvoiceApproved
        assertThat(REPO.findByStatus(SupplierInvoice.Status.APPROVED))
            .extracting(s -> s.id().value()).contains(si.id().value());
        assertThat(REPO.findPaymentSnapshot(si.id().value())).isPresent()
            .get().satisfies(snap -> {
                assertThat(snap.totalAmount()).isEqualByComparingTo("100.00");
                assertThat(snap.paidAmount()).isEqualByComparingTo("0");
                assertThat(snap.status()).isEqualTo(SupplierInvoice.Status.APPROVED);
            });
    }

    @Test
    void manualApprove_failed_invoice_via_update_path_persists_approved_and_emits_outbox() {
        SupplierInvoice si = recordedInvoice("SINV-MA-1", UUID.randomUUID(), UUID.randomUUID(),
            SupplierInvoice.MatchStatus.FAILED);
        save(si); // status three_way_match_failed, no event
        assertThat(countOutbox(si.id().value())).isZero();

        SupplierInvoice loaded = REPO.findById(si.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(SupplierInvoice.Status.THREE_WAY_MATCH_FAILED);
        loaded.manualApprove("variance within tolerance");
        save(loaded);

        SupplierInvoice r = REPO.findById(si.id()).orElseThrow();
        assertThat(r.status()).isEqualTo(SupplierInvoice.Status.APPROVED);
        assertThat(r.matchStatus()).isEqualTo(SupplierInvoice.MatchStatus.MATCHED);
        assertThat(r.version()).isEqualTo(2L); // insert(v1) → update(v2)
        assertThat(approvedAtIsNull(si.id().value())).isFalse();
        assertThat(countOutbox(si.id().value())).isEqualTo(1L); // SupplierInvoiceApproved from manualApprove
    }

    @Test
    void manualReject_failed_invoice_flips_cancelled_and_emits_rejected_outbox() {
        SupplierInvoice si = recordedInvoice("SINV-MR-1", UUID.randomUUID(), UUID.randomUUID(),
            SupplierInvoice.MatchStatus.VARIANCE);
        save(si); // status three_way_match_failed

        SupplierInvoice loaded = REPO.findById(si.id()).orElseThrow();
        loaded.manualReject("supplier sent the wrong invoice");
        save(loaded);

        SupplierInvoice r = REPO.findById(si.id()).orElseThrow();
        assertThat(r.status()).isEqualTo(SupplierInvoice.Status.CANCELLED);
        assertThat(r.version()).isEqualTo(2L);
        assertThat(countOutbox(si.id().value())).isEqualTo(1L); // SupplierInvoiceRejected
    }

    @Test
    void update_of_missing_row_raises_illegal_state_not_optimistic_lock() {
        // A version>0 aggregate that was never inserted: the update path's
        // WHERE supplier_invoice_header_id=? matches 0 rows. The repo guards
        // that with Assert.state, so it surfaces as IllegalStateException — the
        // aggregate carries no optimistic-lock WHERE version=? clause.
        // Consistent line (5 × 20 = 100) so manualApprove's assertApprovable passes
        // and we actually reach the repo update (the path under test), rather than
        // tripping the header/line consistency guard first.
        SupplierInvoiceLine line = new SupplierInvoiceLine(
            UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "RAW-IT-1", "Raw 1", new BigDecimal("5"), new BigDecimal("20.000000"),
            BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"));
        SupplierInvoice stale = SupplierInvoice.reconstitute(
            SupplierInvoiceId.newId(), "SINV-MISSING", "SUP-MISSING",
            UUID.randomUUID(), "PO-MISSING", null, null, UUID.randomUUID(), "SUP-IT", "Supplier IT",
            "AUD", new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
            SupplierInvoice.Status.THREE_WAY_MATCH_FAILED, SupplierInvoice.MatchStatus.FAILED,
            List.of(line), 1L);
        stale.manualApprove("ok");

        assertThatThrownBy(() -> save(stale))
            .isInstanceOf(IllegalStateException.class)
            .isNotInstanceOf(OptimisticLockingFailureException.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static SupplierInvoice recordedInvoice(
            String internalNumber, UUID supplierId, UUID poId, SupplierInvoice.MatchStatus matchOutcome) {
        SupplierInvoiceLine line = new SupplierInvoiceLine(
            UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "RAW-IT-1", "Raw 1", new BigDecimal("5"), new BigDecimal("20.000000"),
            BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"));
        return SupplierInvoice.record(
            internalNumber, "SUP-" + internalNumber, poId, "PO-IT", UUID.randomUUID(), "GR-IT",
            supplierId, "SUP-IT", "Supplier IT", "AUD", List.of(line), matchOutcome);
    }

    private void save(SupplierInvoice si) {
        TX.executeWithoutResult(s -> REPO.save(si));
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM finance.outbox_message WHERE aggregate_id = ?", Long.class, aggregateId);
    }

    private boolean approvedAtIsNull(UUID invoiceId) {
        return Boolean.TRUE.equals(JDBC.queryForObject(
            "SELECT approved_at IS NULL FROM finance.supplier_invoice_header WHERE supplier_invoice_header_id = ?",
            Boolean.class, invoiceId));
    }
}
