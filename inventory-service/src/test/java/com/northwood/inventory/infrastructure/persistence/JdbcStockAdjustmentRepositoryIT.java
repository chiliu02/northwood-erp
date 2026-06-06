package com.northwood.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.inventory.domain.StockAdjustment;
import com.northwood.inventory.domain.StockAdjustmentId;
import com.northwood.inventory.domain.StockMovementDirection;
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
 * Real-Postgres test for {@link JdbcStockAdjustmentRepository}
 * (post-only header aggregate). Covers the {@code save}→{@code findById}
 * round-trip incl. the {@code direction}/{@code status} {@code dbValue()}, the
 * {@code StockAdjusted} outbox row, and the post-only guard.
 */
class JdbcStockAdjustmentRepositoryIT {

    private static final UUID WAREHOUSE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcStockAdjustmentRepository REPO;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = inventory, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcStockAdjustmentRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
        JDBC.update(
            "INSERT INTO inventory.warehouse (warehouse_id, warehouse_code, name) VALUES (?, 'WH-ADJ', 'Warehouse Adj IT')",
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
        JDBC.execute("TRUNCATE inventory.stock_adjustment, inventory.outbox_message CASCADE");
    }

    @Test
    void save_then_findById_round_trips_and_writes_one_outbox_row() {
        UUID productId = UUID.randomUUID();
        StockAdjustment adj = StockAdjustment.post(
            "ADJ-RT-001", WAREHOUSE_ID, "WH-ADJ", productId, "SKU-IT", "Widget IT",
            StockMovementDirection.OUT, new BigDecimal("7"), "damage write-off");
        TX.executeWithoutResult(s -> REPO.save(adj));

        StockAdjustment r = REPO.findById(adj.id()).orElseThrow();
        assertThat(r.adjustmentNumber()).isEqualTo("ADJ-RT-001");
        assertThat(r.warehouseId()).isEqualTo(WAREHOUSE_ID);
        assertThat(r.productId()).isEqualTo(productId);
        assertThat(r.direction()).isEqualTo(StockMovementDirection.OUT);
        assertThat(r.quantity()).isEqualByComparingTo("7");
        assertThat(r.reason()).isEqualTo("damage write-off");
        assertThat(r.status()).isEqualTo(StockAdjustment.Status.POSTED);
        assertThat(r.version()).isEqualTo(1L);
        assertThat(countOutbox(adj.id().value())).isEqualTo(1L); // StockAdjusted
    }

    @Test
    void update_path_is_rejected_for_post_only_aggregate() {
        StockAdjustment persisted = StockAdjustment.reconstitute(
            StockAdjustmentId.newId(), "ADJ-UPD-001", WAREHOUSE_ID, "WH-ADJ",
            UUID.randomUUID(), "SKU", "W",
            StockMovementDirection.IN, new BigDecimal("3"), "count", StockAdjustment.Status.POSTED, 1L);
        assertThatThrownBy(() -> TX.executeWithoutResult(s -> REPO.save(persisted)))
            .isInstanceOf(IllegalStateException.class);
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM inventory.outbox_message WHERE aggregate_id = ?",
            Long.class, aggregateId);
    }
}
