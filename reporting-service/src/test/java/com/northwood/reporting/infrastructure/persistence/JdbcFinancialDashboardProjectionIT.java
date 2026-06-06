package com.northwood.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-Postgres test for {@link JdbcFinancialDashboardProjection}'s flow-column
 * writes — focuses on {@code recordCostOfGoodsSold} (the shipment-driven COGS
 * feed that replaced the supplier-invoice proxy) and its interaction with
 * {@code recordCustomerInvoice} through the {@code gross_profit} recompute.
 *
 * <p>The per-day row is pre-seeded (as the balance worker does in production)
 * because {@code upsertMoney} applies its delta on the ON&nbsp;CONFLICT path.
 */
class JdbcFinancialDashboardProjectionIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcFinancialDashboardProjection PROJECTION;

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
        PROJECTION = new JdbcFinancialDashboardProjection(JDBC);
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
    void clear() {
        JDBC.execute("TRUNCATE reporting.financial_dashboard_daily CASCADE");
    }

    @Test
    void records_cogs_accumulates_and_recomputes_gross_profit() {
        Instant occurredAt = Instant.now();
        LocalDate date = occurredAt.atZone(ZoneId.systemDefault()).toLocalDate();
        seedZeroRow(date, "AUD");

        PROJECTION.recordCostOfGoodsSold(new java.math.BigDecimal("320.00"), "AUD", occurredAt);
        assertColumns(date, "0.00", "320.00", "-320.00");

        PROJECTION.recordCustomerInvoice(new java.math.BigDecimal("650.00"), "AUD", occurredAt);
        assertColumns(date, "650.00", "320.00", "330.00");

        // a second shipment's COGS accumulates onto the same day
        PROJECTION.recordCostOfGoodsSold(new java.math.BigDecimal("120.00"), "AUD", occurredAt);
        assertColumns(date, "650.00", "440.00", "210.00");
    }

    @Test
    void zero_cogs_is_a_no_op() {
        Instant occurredAt = Instant.now();
        LocalDate date = occurredAt.atZone(ZoneId.systemDefault()).toLocalDate();
        seedZeroRow(date, "AUD");

        PROJECTION.recordCostOfGoodsSold(java.math.BigDecimal.ZERO, "AUD", occurredAt);

        assertColumns(date, "0.00", "0.00", "0.00");
    }

    private void seedZeroRow(LocalDate date, String currency) {
        JDBC.update("""
            INSERT INTO reporting.financial_dashboard_daily (dashboard_date, currency_code)
            VALUES (?, ?)
            """, java.sql.Date.valueOf(date), currency);
    }

    private void assertColumns(LocalDate date, String revenue, String cogs, String gross) {
        var row = JDBC.queryForMap("""
            SELECT sales_revenue, cost_of_goods_sold, gross_profit
            FROM reporting.financial_dashboard_daily
            WHERE dashboard_date = ? AND currency_code = 'AUD'
            """, java.sql.Date.valueOf(date));
        assertThat((java.math.BigDecimal) row.get("sales_revenue")).isEqualByComparingTo(revenue);
        assertThat((java.math.BigDecimal) row.get("cost_of_goods_sold")).isEqualByComparingTo(cogs);
        assertThat((java.math.BigDecimal) row.get("gross_profit")).isEqualByComparingTo(gross);
    }
}
