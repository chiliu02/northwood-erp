package com.northwood.purchasing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.purchasing.application.dto.SupplierPriceListView;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-Postgres test for {@link JdbcSupplierProductPriceQueryPort}'s enriched
 * {@code findAll} (supplier_product_price ⋈ supplier ⋈ product_card), plus the
 * {@link JdbcProductCreatedProjection} upsert that feeds the product-name
 * columns it reads.
 */
class JdbcSupplierProductPriceQueryPortIT {

    private static final UUID SUPPLIER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SUPPLIER_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID PRODUCT_BOARD = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID PRODUCT_LEG = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID PRODUCT_NO_CARD = UUID.fromString("00000000-0000-0000-0000-0000000000b3");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcSupplierProductPriceQueryPort PORT;
    private static JdbcProductCreatedProjection CARD_PROJECTION;

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
        PORT = new JdbcSupplierProductPriceQueryPort(JDBC);
        CARD_PROJECTION = new JdbcProductCreatedProjection(JDBC);
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
        JDBC.execute("TRUNCATE purchasing.supplier_product_price, purchasing.product_card, purchasing.supplier CASCADE");
        JDBC.update("INSERT INTO purchasing.supplier (supplier_id, supplier_code, name) VALUES (?, 'SUP-A', 'Acme Timber')", SUPPLIER_A);
        JDBC.update("INSERT INTO purchasing.supplier (supplier_id, supplier_code, name) VALUES (?, 'SUP-B', 'Bolt Co')", SUPPLIER_B);
    }

    private void seedPrice(UUID supplierId, UUID productId, String price) {
        JDBC.update("""
            INSERT INTO purchasing.supplier_product_price
                (supplier_product_price_id, supplier_id, product_id, currency_code, unit_price)
            VALUES (?, ?, ?, 'AUD', ?)
            """, UUID.randomUUID(), supplierId, productId, new BigDecimal(price));
    }

    @Test
    void findAll_joins_supplier_and_product_names_ordered_by_supplier_then_sku() {
        CARD_PROJECTION.applyCreated(PRODUCT_BOARD, "RM-BOARD-001", "Wooden Board");
        CARD_PROJECTION.applyCreated(PRODUCT_LEG, "RM-LEG-001", "Table Leg");
        seedPrice(SUPPLIER_B, PRODUCT_LEG, "25.00");       // Bolt Co — sorts after Acme
        seedPrice(SUPPLIER_A, PRODUCT_LEG, "24.00");       // Acme, RM-LEG
        seedPrice(SUPPLIER_A, PRODUCT_BOARD, "80.00");     // Acme, RM-BOARD (sku sorts before LEG)

        List<SupplierPriceListView> all = PORT.findAll();

        assertThat(all).hasSize(3);
        // ORDER BY supplier name, then sku: Acme/RM-BOARD, Acme/RM-LEG, Bolt/RM-LEG
        assertThat(all).extracting(SupplierPriceListView::supplierName, SupplierPriceListView::productSku)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("Acme Timber", "RM-BOARD-001"),
                org.assertj.core.groups.Tuple.tuple("Acme Timber", "RM-LEG-001"),
                org.assertj.core.groups.Tuple.tuple("Bolt Co", "RM-LEG-001"));
        SupplierPriceListView board = all.get(0);
        assertThat(board.supplierCode()).isEqualTo("SUP-A");
        assertThat(board.productName()).isEqualTo("Wooden Board");
        assertThat(board.unitPrice()).isEqualByComparingTo("80.00");
        assertThat(board.currencyCode()).isEqualTo("AUD");
    }

    @Test
    void price_for_product_without_a_card_still_lists_with_null_name() {
        seedPrice(SUPPLIER_A, PRODUCT_NO_CARD, "9.99");

        List<SupplierPriceListView> all = PORT.findAll();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).productId()).isEqualTo(PRODUCT_NO_CARD);
        assertThat(all.get(0).productSku()).isNull();
        assertThat(all.get(0).productName()).isNull();
        assertThat(all.get(0).supplierName()).isEqualTo("Acme Timber");
    }

    @Test
    void product_created_projection_upserts_and_coexists_with_discontinued() {
        // discontinued-first: a row stamped by ProductDiscontinued, then Created fills the name
        JDBC.update("INSERT INTO purchasing.product_card (product_id, discontinued_at) VALUES (?, ?)",
            PRODUCT_BOARD, java.sql.Timestamp.from(Instant.parse("2026-06-01T00:00:00Z")));
        CARD_PROJECTION.applyCreated(PRODUCT_BOARD, "RM-BOARD-001", "Wooden Board");
        // re-apply with a corrected name updates in place (upsert)
        CARD_PROJECTION.applyCreated(PRODUCT_BOARD, "RM-BOARD-001", "Wooden Board v2");

        var row = JDBC.queryForMap(
            "SELECT product_sku, product_name, discontinued_at FROM purchasing.product_card WHERE product_id = ?",
            PRODUCT_BOARD);
        assertThat(row.get("product_sku")).isEqualTo("RM-BOARD-001");
        assertThat(row.get("product_name")).isEqualTo("Wooden Board v2");
        assertThat(row.get("discontinued_at")).isNotNull(); // discontinued stamp preserved through the name upsert
    }
}
