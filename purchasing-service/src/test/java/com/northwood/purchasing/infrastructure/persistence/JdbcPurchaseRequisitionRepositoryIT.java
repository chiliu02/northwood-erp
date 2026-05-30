package com.northwood.purchasing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.purchasing.domain.PurchaseRequisition;
import com.northwood.purchasing.domain.PurchaseRequisitionId;
import com.northwood.purchasing.domain.PurchaseRequisitionLine;
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
 * §2.25 Tier 2: real-Postgres test for {@link JdbcPurchaseRequisitionRepository}
 * (header + lines). Covers: insert→findById round-trip of header + line incl.
 * the {@code source_type} CHECK + enum dbValue()/fromDb() and the
 * {@code PurchaseRequisitionCreated} outbox row; {@code markConverted} persisted
 * via the update path (with the {@code converted_at} CASE); and the optimistic-
 * lock conflict on a stale version.
 */
class JdbcPurchaseRequisitionRepositoryIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcPurchaseRequisitionRepository REPO;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = purchasing, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcPurchaseRequisitionRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
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
        JDBC.execute("TRUNCATE purchasing.purchase_requisition_line, "
            + "purchasing.purchase_requisition_header, purchasing.outbox_message CASCADE");
    }

    @Test
    void save_insert_then_findById_round_trips_header_and_line_and_emits_outbox() {
        UUID workOrderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        PurchaseRequisitionLine line = new PurchaseRequisitionLine(
            UUID.randomUUID(), 1, productId, "RM-IT-1", "Raw Material 1",
            new BigDecimal("5"), null, null, null, PurchaseRequisition.LineStatus.OPEN);

        PurchaseRequisition pr = PurchaseRequisition.create(
            "PR-RT-001", PurchaseRequisition.SourceType.WORK_ORDER_SHORTAGE,
            workOrderId, null, "planner", List.of(line));
        save(pr);

        PurchaseRequisition r = REPO.findById(pr.id()).orElseThrow();
        assertThat(r.requisitionNumber()).isEqualTo("PR-RT-001");
        assertThat(r.sourceType()).isEqualTo(PurchaseRequisition.SourceType.WORK_ORDER_SHORTAGE);
        assertThat(r.sourceWorkOrderId()).isEqualTo(workOrderId);
        assertThat(r.sourceProductId()).isNull();
        assertThat(r.status()).isEqualTo(pr.status());
        assertThat(r.version()).isEqualTo(1L);
        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).productId()).isEqualTo(productId);
        assertThat(r.lines().get(0).requestedQuantity()).isEqualByComparingTo("5");
        assertThat(r.lines().get(0).status()).isEqualTo(PurchaseRequisition.LineStatus.OPEN);
        assertThat(countOutbox(pr.id().value())).isEqualTo(1L);
    }

    @Test
    void markConverted_via_update_path_persists_converted_status() {
        PurchaseRequisition pr = approvedRequisition("PR-CONV-001");
        save(pr);

        PurchaseRequisition loaded = REPO.findById(pr.id()).orElseThrow();
        loaded.markConverted();
        save(loaded);

        assertThat(dbStatus(pr.id().value())).isEqualTo("converted");
        assertThat(REPO.findById(pr.id()).orElseThrow().status())
            .isEqualTo(PurchaseRequisition.Status.CONVERTED);
    }

    @Test
    void stale_version_update_raises_optimistic_lock_failure() {
        PurchaseRequisition pr = approvedRequisition("PR-LOCK-001");
        save(pr);

        PurchaseRequisition loadedA = REPO.findById(pr.id()).orElseThrow();
        PurchaseRequisition loadedB = REPO.findById(pr.id()).orElseThrow();

        loadedB.markConverted();
        save(loadedB); // 1 → 2

        loadedA.markConverted();
        assertThatThrownBy(() -> save(loadedA))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** A header-only requisition reconstituted straight into APPROVED (version 0 → insert path). */
    private static PurchaseRequisition approvedRequisition(String number) {
        return PurchaseRequisition.reconstitute(
            PurchaseRequisitionId.newId(), number,
            PurchaseRequisition.SourceType.MANUAL, null, null, null,
            PurchaseRequisition.Status.APPROVED, "planner", List.of(), 0L);
    }

    private void save(PurchaseRequisition pr) {
        TX.executeWithoutResult(s -> REPO.save(pr));
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM purchasing.outbox_message WHERE aggregate_id = ?",
            Long.class, aggregateId);
    }

    private String dbStatus(UUID requisitionId) {
        return JDBC.queryForObject(
            "SELECT status FROM purchasing.purchase_requisition_header WHERE purchase_requisition_header_id = ?",
            String.class, requisitionId);
    }
}
