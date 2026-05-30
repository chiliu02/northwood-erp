package com.northwood.sales.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.sales.domain.PaymentTerms;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderLine;
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
 * §2.25 Tier 2: real-Postgres test for {@link JdbcSalesOrderRepository}
 * (header + lines). Seeded via {@code reconstitute(SUBMITTED, version=0)}.
 * Covers: insert→findById round-trip of header + line incl. enum
 * dbValue()/fromDb() + the {@code customer_id} FK; {@code cancel()} persisted via
 * the update path (status + cancelled_at) + the {@code SalesOrderCancellationRequested}
 * outbox row; and the optimistic-lock conflict on a stale version.
 */
class JdbcSalesOrderRepositoryIT {

    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcSalesOrderRepository REPO;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = sales, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcSalesOrderRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
        JDBC.update(
            "INSERT INTO sales.customer (customer_id, customer_code, name) VALUES (?, 'CUST-SO-IT', 'Customer IT')",
            CUSTOMER_ID);
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
        JDBC.execute("TRUNCATE sales.sales_order_line, "
            + "sales.sales_order_header, sales.outbox_message CASCADE");
    }

    @Test
    void save_insert_then_findById_round_trips_header_and_line() {
        UUID productId = UUID.randomUUID();
        SalesOrder order = submittedOrder("SO-RT-001", productId);
        save(order);

        SalesOrder r = REPO.findById(order.id()).orElseThrow();
        assertThat(r.orderNumber()).isEqualTo("SO-RT-001");
        assertThat(r.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(r.status()).isEqualTo(SalesOrder.Status.SUBMITTED);
        assertThat(r.currencyCode()).isEqualTo("AUD");
        assertThat(r.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(r.version()).isEqualTo(1L);
        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).productId()).isEqualTo(productId);
        assertThat(r.lines().get(0).orderedQuantity()).isEqualByComparingTo("2");
        assertThat(r.lines().get(0).lineStatus()).isEqualTo(SalesOrder.LineStatus.OPEN);
    }

    @Test
    void cancel_via_update_path_persists_cancelled_status_and_emits_outbox() {
        SalesOrder order = submittedOrder("SO-CAN-001", UUID.randomUUID());
        save(order);

        SalesOrder loaded = REPO.findById(order.id()).orElseThrow();
        loaded.cancel("customer changed their mind");
        save(loaded);

        SalesOrder r = REPO.findById(order.id()).orElseThrow();
        assertThat(r.status()).isEqualTo(SalesOrder.Status.CANCELLED);
        assertThat(r.version()).isEqualTo(2L);
        assertThat(dbCancelledAtIsNull(order.id().value())).isFalse();
        assertThat(countOutbox(order.id().value())).isEqualTo(1L); // SalesOrderCancellationRequested
    }

    @Test
    void stale_version_update_raises_optimistic_lock_failure() {
        SalesOrder order = submittedOrder("SO-LOCK-001", UUID.randomUUID());
        save(order);

        SalesOrder loadedA = REPO.findById(order.id()).orElseThrow();
        SalesOrder loadedB = REPO.findById(order.id()).orElseThrow();

        loadedB.cancel("first");
        save(loadedB); // 1 → 2

        loadedA.cancel("stale");
        assertThatThrownBy(() -> save(loadedA))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static SalesOrder submittedOrder(String orderNumber, UUID productId) {
        SalesOrderLine line = new SalesOrderLine(
            UUID.randomUUID(), 1, productId, "FG-IT-1", "Finished 1",
            new BigDecimal("2"), new BigDecimal("50.000000"), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, SalesOrder.LineStatus.OPEN);
        return SalesOrder.reconstitute(
            SalesOrderId.newId(), orderNumber, CUSTOMER_ID, "CUST-SO-IT", "Customer IT",
            LocalDate.now(), null, SalesOrder.Status.SUBMITTED, "AUD", BigDecimal.ONE,
            PaymentTerms.ON_SHIPMENT, null,
            new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
            null, 0L, List.of(line));
    }

    private void save(SalesOrder order) {
        TX.executeWithoutResult(s -> REPO.save(order));
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM sales.outbox_message WHERE aggregate_id = ?", Long.class, aggregateId);
    }

    private boolean dbCancelledAtIsNull(UUID orderId) {
        return Boolean.TRUE.equals(JDBC.queryForObject(
            "SELECT cancelled_at IS NULL FROM sales.sales_order_header WHERE sales_order_header_id = ?",
            Boolean.class, orderId));
    }
}
