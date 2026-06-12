package com.northwood.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.inventory.domain.GoodsReceipt;
import com.northwood.inventory.domain.GoodsReceiptId;
import com.northwood.inventory.domain.GoodsReceiptLine;
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
 * Real-Postgres test for {@link JdbcGoodsReceiptRepository}
 * (post-only header+line aggregate). Covers: {@code post}→{@code findById}
 * round-trip of header + lines incl. the {@code status} {@code code()} +
 * the {@code warehouse_id} FK; the {@code GoodsReceived} outbox row; and the
 * post-only guard (an attempted update raises {@code IllegalStateException}).
 */
class JdbcGoodsReceiptRepositoryIT {

    private static final UUID WAREHOUSE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcGoodsReceiptRepository REPO;

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
        REPO = new JdbcGoodsReceiptRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
        JDBC.update(
            "INSERT INTO inventory.warehouse (warehouse_id, warehouse_code, name) VALUES (?, 'WH-IT', 'Warehouse IT')",
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
        JDBC.execute("TRUNCATE inventory.goods_receipt_line, "
            + "inventory.goods_receipt_header, inventory.outbox_message CASCADE");
    }

    @Test
    void save_post_then_findById_round_trips_header_and_lines() {
        UUID poHeaderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        GoodsReceiptLine line = new GoodsReceiptLine(
            UUID.randomUUID(), UUID.randomUUID(), productId, "RM-IT-1", "Raw 1",
            new BigDecimal("10"), new BigDecimal("2.500000"), new BigDecimal("25.00"));
        GoodsReceipt gr = GoodsReceipt.post(
            "GR-RT-001", poHeaderId, "PO-RT-001", supplierId, "Supplier IT", WAREHOUSE_ID, "WH-IT", List.of(line));
        save(gr);

        GoodsReceipt r = REPO.findById(gr.id()).orElseThrow();
        assertThat(r.goodsReceiptNumber()).isEqualTo("GR-RT-001");
        assertThat(r.purchaseOrderHeaderId()).isEqualTo(poHeaderId);
        assertThat(r.purchaseOrderNumber()).isEqualTo("PO-RT-001");
        assertThat(r.supplierId()).isEqualTo(supplierId);
        assertThat(r.warehouseId()).isEqualTo(WAREHOUSE_ID);
        assertThat(r.status()).isEqualTo(GoodsReceipt.Status.POSTED);
        assertThat(r.version()).isEqualTo(1L);
        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).productId()).isEqualTo(productId);
        assertThat(r.lines().get(0).receivedQuantity()).isEqualByComparingTo("10");
        assertThat(r.lines().get(0).lineCost()).isEqualByComparingTo("25.00");
        assertThat(countOutbox(gr.id().value())).isEqualTo(1L); // GoodsReceived
    }

    @Test
    void update_path_is_rejected_for_post_only_aggregate() {
        GoodsReceipt persisted = GoodsReceipt.reconstitute(
            GoodsReceiptId.newId(), "GR-UPD-001", UUID.randomUUID(), "PO-UPD-001", UUID.randomUUID(), "Sup",
            WAREHOUSE_ID, "WH-IT", GoodsReceipt.Status.POSTED, List.of(), 1L);
        assertThatThrownBy(() -> save(persisted))
            .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void save(GoodsReceipt gr) {
        TX.executeWithoutResult(s -> REPO.save(gr));
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM inventory.outbox_message WHERE aggregate_id = ?",
            Long.class, aggregateId);
    }
}
