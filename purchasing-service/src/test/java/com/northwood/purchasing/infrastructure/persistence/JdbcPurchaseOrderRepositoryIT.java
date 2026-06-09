package com.northwood.purchasing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.purchasing.domain.PurchaseOrder;
import com.northwood.purchasing.domain.PurchaseOrderId;
import com.northwood.purchasing.domain.PurchaseOrderLine;
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
 * Real-Postgres test for {@link JdbcPurchaseOrderRepository}
 * (header + lines). Seeded via {@code reconstitute(DRAFT, version=0)} (avoids the
 * {@code fromRequisition} factory's {@code Supplier} dependency). Covers:
 * insert→findById round-trip of header + line incl. enum dbValue()/fromDb();
 * {@code approve()} persisted via the update path + the {@code PurchaseOrderApproved}
 * outbox row; and the optimistic-lock conflict on a stale version.
 */
class JdbcPurchaseOrderRepositoryIT {

    private static final UUID SUPPLIER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcPurchaseOrderRepository REPO;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = purchasing, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcPurchaseOrderRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
        JDBC.update(
            "INSERT INTO purchasing.supplier (supplier_id, supplier_code, name) VALUES (?, 'SUP-PO-IT', 'Supplier IT')",
            SUPPLIER_ID);
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
        JDBC.execute("TRUNCATE purchasing.purchase_order_line, "
            + "purchasing.purchase_order_header, purchasing.outbox_message CASCADE");
    }

    @Test
    void save_insert_then_findById_round_trips_header_and_line() {
        UUID productId = UUID.randomUUID();
        PurchaseOrder po = draftPo("PO-RT-001", productId);
        save(po);

        PurchaseOrder r = REPO.findById(po.id()).orElseThrow();
        assertThat(r.purchaseOrderNumber()).isEqualTo("PO-RT-001");
        assertThat(r.supplierId()).isEqualTo(SUPPLIER_ID);
        assertThat(r.currencyCode()).isEqualTo("AUD");
        assertThat(r.totalAmount()).isEqualByComparingTo("25.00");
        assertThat(r.status()).isEqualTo(PurchaseOrder.Status.DRAFT);
        assertThat(r.version()).isEqualTo(1L);
        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).productId()).isEqualTo(productId);
        assertThat(r.lines().get(0).orderedQuantity()).isEqualByComparingTo("10");
        assertThat(r.lines().get(0).status()).isEqualTo(PurchaseOrder.LineStatus.OPEN);
    }

    @Test
    void approve_via_update_path_persists_sent_and_emits_outbox() {
        PurchaseOrder po = draftPo("PO-APR-001", UUID.randomUUID());
        save(po);

        PurchaseOrder loaded = REPO.findById(po.id()).orElseThrow();
        loaded.approve("buyer", "ok to send");
        save(loaded);

        PurchaseOrder r = REPO.findById(po.id()).orElseThrow();
        assertThat(r.status()).isEqualTo(PurchaseOrder.Status.SENT);
        assertThat(r.version()).isEqualTo(2L);
        assertThat(countOutbox(po.id().value())).isEqualTo(1L); // PurchaseOrderApproved
    }

    @Test
    void stale_version_update_raises_optimistic_lock_failure() {
        PurchaseOrder po = draftPo("PO-LOCK-001", UUID.randomUUID());
        save(po);

        PurchaseOrder loadedA = REPO.findById(po.id()).orElseThrow();
        PurchaseOrder loadedB = REPO.findById(po.id()).orElseThrow();

        loadedB.approve("buyer", "first");
        save(loadedB); // 1 → 2

        loadedA.approve("buyer", "stale");
        assertThatThrownBy(() -> save(loadedA))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static PurchaseOrder draftPo(String poNumber, UUID productId) {
        PurchaseOrderLine line = new PurchaseOrderLine(
            UUID.randomUUID(), 1, null, productId, "RM-IT-1", "Raw 1",
            new BigDecimal("10"), new BigDecimal("2.500000"),
            BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("25.00"),
            PurchaseOrder.LineStatus.OPEN);
        return PurchaseOrder.reconstitute(
            PurchaseOrderId.newId(), poNumber, SUPPLIER_ID, "SUP-PO-IT", "Supplier IT",
            null, "AUD", new BigDecimal("25.00"), BigDecimal.ZERO, new BigDecimal("25.00"),
            PurchaseOrder.Status.DRAFT, List.of(line), 0L);
    }

    private void save(PurchaseOrder po) {
        TX.executeWithoutResult(s -> REPO.save(po));
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM purchasing.outbox_message WHERE aggregate_id = ?", Long.class, aggregateId);
    }
}
