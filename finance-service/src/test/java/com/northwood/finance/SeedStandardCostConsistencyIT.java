package com.northwood.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Cross-schema guard: for every active manufactured BOM in
 * the seed, the product's <b>standard cost</b> must equal its rolled-up
 * material + conversion cost — the invariant that makes WIP net to zero per
 * work order. This is the check the original material-only seed (FG-TABLE 320
 * vs material rollup 197) would have failed; the unit/harness tests can't catch
 * it because they feed costs, this reads the real seed.
 *
 * <p>Recomputes independently of the rollup engine: leaves are raw/purchased
 * material costs ({@code finance.product_card.standard_cost}), a manufactured
 * component contributes its own recomputed standard cost (recursive, so
 * sub-assembly conversion folds into the parent), and own conversion =
 * Σ active-routing op {@code (setup+run) × (labour+overhead rate)}. Runs as the
 * {@code postgres} superuser so it can read across the per-service schemas.
 */
class SeedStandardCostConsistencyIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static JdbcTemplate JDBC;

    @BeforeAll
    static void bootAndSeed() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp_seed.sql"));
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        JDBC = new JdbcTemplate(ds);
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

    @Test
    void every_active_manufactured_bom_standard_cost_equals_material_plus_conversion_rollup() {
        Map<UUID, BigDecimal> memo = new HashMap<>();
        List<String> mismatches = new ArrayList<>();

        for (Map<String, Object> row : JDBC.queryForList(
            "SELECT finished_product_id, finished_product_sku FROM manufacturing.bom_header WHERE status = 'active'")) {
            UUID fgId = (UUID) row.get("finished_product_id");
            String sku = (String) row.get("finished_product_sku");

            BigDecimal expected = recompute(fgId, memo);
            BigDecimal seeded = standardCost(fgId);

            if (seeded == null) {
                mismatches.add(sku + ": no finance.product_card.standard_cost seeded (expected " + expected + ")");
            } else if (expected.compareTo(seeded) != 0) {
                mismatches.add(sku + ": seeded standard_cost=" + seeded
                    + " but material+conversion rollup=" + expected.stripTrailingZeros().toPlainString());
            }
        }

        assertThat(mismatches)
            .as("manufactured SKUs whose seeded standard cost diverges from the material+conversion rollup")
            .isEmpty();
    }

    /** Recursive standard cost: own conversion + Σ component (raw → material cost; sub-assembly → its rollup). */
    private BigDecimal recompute(UUID productId, Map<UUID, BigDecimal> memo) {
        if (memo.containsKey(productId)) {
            return memo.get(productId);
        }
        UUID activeBom = activeBomHeaderId(productId);
        if (activeBom == null) {
            // Leaf (raw / purchased): its standard cost is its material cost.
            BigDecimal leaf = nz(standardCost(productId));
            memo.put(productId, leaf);
            return leaf;
        }
        BigDecimal material = BigDecimal.ZERO;
        for (Map<String, Object> line : JDBC.queryForList(
            "SELECT component_product_id, component_kind, quantity_per_finished_unit, scrap_factor_percent "
                + "FROM manufacturing.bom_line WHERE bom_header_id = ?", activeBom)) {
            UUID componentId = (UUID) line.get("component_product_id");
            BigDecimal qty = (BigDecimal) line.get("quantity_per_finished_unit");
            BigDecimal scrapPct = (BigDecimal) line.get("scrap_factor_percent");
            BigDecimal scrapMultiplier = BigDecimal.ONE.add(
                nz(scrapPct).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            BigDecimal componentCost = recompute(componentId, memo); // raw leaf or sub-assembly rollup
            material = material.add(nz(qty).multiply(scrapMultiplier).multiply(componentCost));
        }
        BigDecimal standard = ownConversion(productId).add(material).setScale(6, RoundingMode.HALF_UP);
        memo.put(productId, standard);
        return standard;
    }

    private BigDecimal ownConversion(UUID productId) {
        UUID routingHeader = JDBC.query(
            "SELECT routing_header_id FROM manufacturing.routing_header "
                + "WHERE finished_product_id = ? AND status = 'active'",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null, productId);
        if (routingHeader == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> op : JDBC.queryForList(
            "SELECT o.planned_setup_minutes, o.planned_run_minutes, "
                + "w.labour_rate_per_minute, w.overhead_rate_per_minute "
                + "FROM manufacturing.routing_operation o "
                + "JOIN manufacturing.work_center w ON w.work_center_id = o.work_center_id "
                + "WHERE o.routing_header_id = ?", routingHeader)) {
            BigDecimal minutes = nz((BigDecimal) op.get("planned_setup_minutes"))
                .add(nz((BigDecimal) op.get("planned_run_minutes")));
            BigDecimal rate = nz((BigDecimal) op.get("labour_rate_per_minute"))
                .add(nz((BigDecimal) op.get("overhead_rate_per_minute")));
            total = total.add(minutes.multiply(rate));
        }
        return total;
    }

    private UUID activeBomHeaderId(UUID productId) {
        return JDBC.query(
            "SELECT bom_header_id FROM manufacturing.bom_header "
                + "WHERE finished_product_id = ? AND status = 'active'",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null, productId);
    }

    private BigDecimal standardCost(UUID productId) {
        return JDBC.query(
            "SELECT standard_cost FROM finance.product_card WHERE product_id = ?",
            rs -> rs.next() ? rs.getBigDecimal(1) : null, productId);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
