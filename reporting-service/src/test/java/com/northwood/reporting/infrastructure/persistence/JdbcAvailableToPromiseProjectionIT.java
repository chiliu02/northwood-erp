package com.northwood.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-Postgres test for {@link JdbcAvailableToPromiseProjection} (REQ-RPT-040): the ATP triple
 * (on-hand / reserved / available) plus the forward-looking adjustments (incoming-from-purchase on
 * PO lines, incoming-from-production on work orders) track the inventory + purchasing +
 * manufacturing events. {@code available = on_hand − reserved}; a shipment drops on-hand AND
 * reserved (committed stock leaving the building), so available is unchanged; incoming clears when
 * the goods receipt / WO completion lands.
 */
class JdbcAvailableToPromiseProjectionIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcAvailableToPromiseProjection PROJECTION;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = reporting, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PROJECTION = new JdbcAvailableToPromiseProjection(JDBC);
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
    void clearTable() {
        JDBC.execute("TRUNCATE reporting.available_to_promise_view CASCADE");
    }

    @Test
    void atp_triple_tracks_reserve_and_ship() {
        UUID p = UUID.randomUUID();
        PROJECTION.recordProductCreated(p, "FG-ATP-1", "ATP Widget", Instant.now());
        assertThat(status(p)).isEqualTo("out_of_stock");
        assertThat(qty(p, "available_quantity")).isEqualByComparingTo("0");

        // Goods receipt bumps on-hand → available rises, status flips to available.
        PROJECTION.recordReceivedLine(p, "FG-ATP-1", "ATP Widget", new BigDecimal("10"), Instant.now());
        assertThat(qty(p, "on_hand_quantity")).isEqualByComparingTo("10");
        assertThat(qty(p, "available_quantity")).isEqualByComparingTo("10");
        assertThat(status(p)).isEqualTo("available");

        // A sales reservation reduces available but not on-hand.
        PROJECTION.recordSalesReservation(p, new BigDecimal("3"), Instant.now());
        assertThat(qty(p, "reserved_for_sales")).isEqualByComparingTo("3");
        assertThat(qty(p, "available_quantity")).isEqualByComparingTo("7");

        // Shipment drops on-hand AND reserved; available is unchanged (committed stock left).
        PROJECTION.recordShippedLine(p, "FG-ATP-1", "ATP Widget", new BigDecimal("3"), Instant.now());
        assertThat(qty(p, "on_hand_quantity")).isEqualByComparingTo("7");
        assertThat(qty(p, "reserved_for_sales")).isEqualByComparingTo("0");
        assertThat(qty(p, "available_quantity")).isEqualByComparingTo("7");
    }

    @Test
    void incoming_from_purchase_rises_then_clears_on_receipt() {
        UUID p = UUID.randomUUID();
        PROJECTION.recordProductCreated(p, "RM-ATP-1", "ATP Raw", Instant.now());

        PROJECTION.recordPurchaseOrderLine(p, "RM-ATP-1", "ATP Raw", new BigDecimal("5"), Instant.now());
        assertThat(qty(p, "incoming_from_purchase")).isEqualByComparingTo("5");
        assertThat(status(p)).isEqualTo("incoming");

        PROJECTION.recordReceivedLine(p, "RM-ATP-1", "ATP Raw", new BigDecimal("5"), Instant.now());
        assertThat(qty(p, "on_hand_quantity")).isEqualByComparingTo("5");
        assertThat(qty(p, "incoming_from_purchase")).isEqualByComparingTo("0");
        assertThat(status(p)).isEqualTo("available");
    }

    @Test
    void incoming_from_production_rises_then_clears_on_completion() {
        UUID p = UUID.randomUUID();
        PROJECTION.recordProductCreated(p, "FG-ATP-2", "ATP Made", Instant.now());

        PROJECTION.recordWorkOrderPlanned(p, "FG-ATP-2", "ATP Made", new BigDecimal("8"), Instant.now());
        assertThat(qty(p, "incoming_from_production")).isEqualByComparingTo("8");
        assertThat(status(p)).isEqualTo("incoming");

        PROJECTION.recordWorkOrderCompleted(p, "FG-ATP-2", new BigDecimal("8"), new BigDecimal("8"), Instant.now());
        assertThat(qty(p, "on_hand_quantity")).isEqualByComparingTo("8");
        assertThat(qty(p, "incoming_from_production")).isEqualByComparingTo("0");
        assertThat(status(p)).isEqualTo("available");
    }

    private BigDecimal qty(UUID p, String column) {
        return JDBC.queryForObject(
            "SELECT " + column + " FROM reporting.available_to_promise_view WHERE product_id = ?",
            BigDecimal.class, p);
    }

    private String status(UUID p) {
        return JDBC.queryForObject(
            "SELECT stock_status FROM reporting.available_to_promise_view WHERE product_id = ?",
            String.class, p);
    }
}
