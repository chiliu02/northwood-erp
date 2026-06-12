package com.northwood.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.inventory.domain.Shipment;
import com.northwood.inventory.domain.ShipmentId;
import com.northwood.inventory.domain.ShipmentLine;
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
 * Real-Postgres test for {@link JdbcShipmentRepository}
 * (post-only header+line aggregate, twin of GoodsReceipt). Covers:
 * {@code post}→{@code findById} round-trip incl. {@code status} {@code code()}
 * + the {@code warehouse_id} FK; the {@code ShipmentPosted} outbox row; and the
 * post-only guard.
 */
class JdbcShipmentRepositoryIT {

    private static final UUID WAREHOUSE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcShipmentRepository REPO;

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
        REPO = new JdbcShipmentRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
        JDBC.update(
            "INSERT INTO inventory.warehouse (warehouse_id, warehouse_code, name) VALUES (?, 'WH-IT-S', 'Warehouse IT')",
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
        JDBC.execute("TRUNCATE inventory.shipment_line, "
            + "inventory.shipment_header, inventory.outbox_message CASCADE");
    }

    @Test
    void save_post_then_findById_round_trips_header_and_lines() {
        UUID salesOrderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        ShipmentLine line = new ShipmentLine(
            UUID.randomUUID(), UUID.randomUUID(), productId, "FG-IT-1", "Finished 1",
            new BigDecimal("3"), new BigDecimal("40.000000"), new BigDecimal("120.00"));
        Shipment shipment = Shipment.post(
            "SH-RT-001", salesOrderId, "SO-RT-001", customerId, "Customer IT", WAREHOUSE_ID, "WH-IT-S", List.of(line));
        save(shipment);

        Shipment r = REPO.findById(shipment.id()).orElseThrow();
        assertThat(r.shipmentNumber()).isEqualTo("SH-RT-001");
        assertThat(r.salesOrderHeaderId()).isEqualTo(salesOrderId);
        assertThat(r.salesOrderNumber()).isEqualTo("SO-RT-001");
        assertThat(r.customerId()).isEqualTo(customerId);
        assertThat(r.warehouseId()).isEqualTo(WAREHOUSE_ID);
        assertThat(r.status()).isEqualTo(Shipment.Status.POSTED);
        assertThat(r.version()).isEqualTo(1L);
        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).productId()).isEqualTo(productId);
        assertThat(r.lines().get(0).shippedQuantity()).isEqualByComparingTo("3");
        assertThat(r.lines().get(0).lineCost()).isEqualByComparingTo("120.00");
        assertThat(countOutbox(shipment.id().value())).isEqualTo(1L); // ShipmentPosted
    }

    @Test
    void update_path_is_rejected_for_post_only_aggregate() {
        Shipment persisted = Shipment.reconstitute(
            ShipmentId.newId(), "SH-UPD-001", UUID.randomUUID(), "SO-UPD-001", UUID.randomUUID(), "Cust",
            WAREHOUSE_ID, "WH-IT-S", Shipment.Status.POSTED, List.of(), 1L);
        assertThatThrownBy(() -> save(persisted))
            .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void save(Shipment shipment) {
        TX.executeWithoutResult(s -> REPO.save(shipment));
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM inventory.outbox_message WHERE aggregate_id = ?",
            Long.class, aggregateId);
    }
}
