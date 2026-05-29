package com.northwood.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.inventory.domain.StockReservation;
import com.northwood.inventory.domain.StockReservationLine;
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
 * §2.25 Tier 2: real-Postgres test for {@link JdbcStockReservationRepository}
 * (insert-only, saga-driven; no full-aggregate read). Covers: {@code save}
 * persisting header + lines + the {@code StockReserved}/{@code
 * RawMaterialsReserved} outbox row; the sales-order vs work-order XOR source +
 * the operational lookups ({@code findActiveHeaderId*}, {@code findReservedLines},
 * {@code findWarehouseIdForHeader}); {@code markReleased}; and
 * {@code deleteHeaderAndLines}.
 */
class JdbcStockReservationRepositoryIT {

    private static final UUID WAREHOUSE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcStockReservationRepository REPO;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = inventory, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcStockReservationRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
        JDBC.update(
            "INSERT INTO inventory.warehouse (warehouse_id, warehouse_code, name) VALUES (?, 'WH-IT-R', 'Warehouse IT')",
            WAREHOUSE_ID);
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
        JDBC.execute("TRUNCATE inventory.stock_reservation_line, "
            + "inventory.stock_reservation_header, inventory.outbox_message CASCADE");
    }

    @Test
    void save_forSalesOrder_persists_header_lines_lookups_and_outbox() {
        UUID salesOrderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        StockReservation reservation = StockReservation.forSalesOrder(
            salesOrderId, WAREHOUSE_ID, List.of(fullyReservedLine(productId, "10")));
        save(reservation);

        // Status is RESERVED (one fully-reserved line) → in the "active" set.
        assertThat(REPO.findActiveHeaderIdForSalesOrder(salesOrderId)).contains(reservation.id().value());
        assertThat(REPO.findWarehouseIdForHeader(reservation.id().value())).contains(WAREHOUSE_ID);
        assertThat(dbHeaderStatus(reservation.id().value())).isEqualTo(reservation.status().dbValue());

        var reserved = REPO.findReservedLines(reservation.id().value());
        assertThat(reserved).hasSize(1);
        assertThat(reserved.get(0).productId()).isEqualTo(productId);
        assertThat(reserved.get(0).reservedQuantity()).isEqualByComparingTo("10");

        assertThat(countOutbox(reservation.id().value())).isEqualTo(1L); // StockReserved
    }

    @Test
    void save_forWorkOrder_sets_work_order_source_only() {
        UUID workOrderId = UUID.randomUUID();
        StockReservation reservation = StockReservation.forWorkOrder(
            workOrderId, WAREHOUSE_ID, List.of(fullyReservedLine(UUID.randomUUID(), "4")));
        save(reservation);

        assertThat(REPO.findAnyHeaderIdForWorkOrder(workOrderId)).contains(reservation.id().value());
        // XOR source: work_order set, sales_order null.
        assertThat(dbColumnIsNull(reservation.id().value(), "sales_order_header_id")).isTrue();
        assertThat(dbColumnIsNull(reservation.id().value(), "work_order_id")).isFalse();
        assertThat(countOutbox(reservation.id().value())).isEqualTo(1L); // RawMaterialsReserved
    }

    @Test
    void markReleased_flips_status_and_drops_from_active_set() {
        UUID salesOrderId = UUID.randomUUID();
        StockReservation reservation = StockReservation.forSalesOrder(
            salesOrderId, WAREHOUSE_ID, List.of(fullyReservedLine(UUID.randomUUID(), "1")));
        save(reservation);

        REPO.markReleased(reservation.id().value());

        assertThat(dbHeaderStatus(reservation.id().value())).isEqualTo("released");
        assertThat(REPO.findActiveHeaderIdForSalesOrder(salesOrderId)).isEmpty();
    }

    @Test
    void deleteHeaderAndLines_removes_all_rows() {
        UUID salesOrderId = UUID.randomUUID();
        StockReservation reservation = StockReservation.forSalesOrder(
            salesOrderId, WAREHOUSE_ID, List.of(fullyReservedLine(UUID.randomUUID(), "2")));
        save(reservation);

        REPO.deleteHeaderAndLines(reservation.id().value());

        assertThat(dbHeaderCount(reservation.id().value())).isZero();
        assertThat(dbLineCount(reservation.id().value())).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static StockReservationLine fullyReservedLine(UUID productId, String qty) {
        BigDecimal q = new BigDecimal(qty);
        return new StockReservationLine(
            UUID.randomUUID(), productId, "SKU-" + qty, "Product " + qty,
            q, q, BigDecimal.ZERO, StockReservation.Status.RESERVED);
    }

    private void save(StockReservation reservation) {
        TX.executeWithoutResult(s -> REPO.save(reservation));
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM inventory.outbox_message WHERE aggregate_id = ?", Long.class, aggregateId);
    }

    private String dbHeaderStatus(UUID headerId) {
        return JDBC.queryForObject(
            "SELECT status FROM inventory.stock_reservation_header WHERE stock_reservation_header_id = ?",
            String.class, headerId);
    }

    private boolean dbColumnIsNull(UUID headerId, String column) {
        return Boolean.TRUE.equals(JDBC.queryForObject(
            "SELECT " + column + " IS NULL FROM inventory.stock_reservation_header "
            + "WHERE stock_reservation_header_id = ?",
            Boolean.class, headerId));
    }

    private long dbHeaderCount(UUID headerId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM inventory.stock_reservation_header WHERE stock_reservation_header_id = ?",
            Long.class, headerId);
    }

    private long dbLineCount(UUID headerId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM inventory.stock_reservation_line WHERE stock_reservation_header_id = ?",
            Long.class, headerId);
    }
}
