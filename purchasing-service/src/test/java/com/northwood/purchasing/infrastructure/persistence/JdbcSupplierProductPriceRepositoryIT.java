package com.northwood.purchasing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.purchasing.domain.SupplierProductPrice;
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
 * §2.25 Tier 2: real-Postgres test for {@link JdbcSupplierProductPriceRepository}
 * (single-row aggregate). Covers: insert→{@code findByKey} round-trip incl. the
 * {@code version DEFAULT 1} on insert; {@code updatePrice} via the update path,
 * version bump, and the {@code SupplierProductPriceChanged} outbox row;
 * optimistic-lock conflict; and {@code listForSupplier}.
 */
class JdbcSupplierProductPriceRepositoryIT {

    private static final UUID SUPPLIER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcSupplierProductPriceRepository REPO;

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
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcSupplierProductPriceRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
        // supplier_id is a NOT NULL FK to purchasing.supplier — seed one row.
        JDBC.update(
            "INSERT INTO purchasing.supplier (supplier_id, supplier_code, name) VALUES (?, 'SUP-IT', 'Supplier IT')",
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
        JDBC.execute("TRUNCATE purchasing.supplier_product_price, purchasing.outbox_message CASCADE");
    }

    @Test
    void save_insert_then_findByKey_round_trips() {
        UUID productId = UUID.randomUUID();
        save(SupplierProductPrice.register(SUPPLIER_ID, productId, "AUD", new BigDecimal("12.500000")));

        SupplierProductPrice r = REPO.findByKey(SUPPLIER_ID, productId, "AUD").orElseThrow();
        assertThat(r.supplierId()).isEqualTo(SUPPLIER_ID);
        assertThat(r.productId()).isEqualTo(productId);
        assertThat(r.currencyCode()).isEqualTo("AUD");
        assertThat(r.unitPrice()).isEqualByComparingTo("12.50");
        assertThat(r.version()).isEqualTo(1L); // schema DEFAULT 1 on insert
        assertThat(REPO.findByKey(SUPPLIER_ID, UUID.randomUUID(), "AUD")).isEmpty();
    }

    @Test
    void updatePrice_bumps_version_and_emits_changed_event() {
        UUID productId = UUID.randomUUID();
        save(SupplierProductPrice.register(SUPPLIER_ID, productId, "AUD", new BigDecimal("12.500000")));
        long changedBefore = countOutboxOfType("SupplierProductPriceChanged");

        SupplierProductPrice loaded = REPO.findByKey(SUPPLIER_ID, productId, "AUD").orElseThrow();
        loaded.updatePrice(new BigDecimal("15.000000"));
        save(loaded);

        SupplierProductPrice r = REPO.findByKey(SUPPLIER_ID, productId, "AUD").orElseThrow();
        assertThat(r.unitPrice()).isEqualByComparingTo("15.00");
        assertThat(r.version()).isEqualTo(2L);
        // updatePrice emits exactly one SupplierProductPriceChanged (delta is robust
        // to whether register() also emits one).
        assertThat(countOutboxOfType("SupplierProductPriceChanged") - changedBefore).isEqualTo(1L);
    }

    @Test
    void stale_version_update_raises_optimistic_lock_failure() {
        UUID productId = UUID.randomUUID();
        save(SupplierProductPrice.register(SUPPLIER_ID, productId, "AUD", new BigDecimal("10.000000")));

        SupplierProductPrice loadedA = REPO.findByKey(SUPPLIER_ID, productId, "AUD").orElseThrow();
        SupplierProductPrice loadedB = REPO.findByKey(SUPPLIER_ID, productId, "AUD").orElseThrow();

        loadedB.updatePrice(new BigDecimal("11.000000"));
        save(loadedB); // 1 → 2

        loadedA.updatePrice(new BigDecimal("12.000000"));
        assertThatThrownBy(() -> save(loadedA))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void listForSupplier_returns_all_prices_for_supplier() {
        save(SupplierProductPrice.register(SUPPLIER_ID, UUID.randomUUID(), "AUD", new BigDecimal("1.000000")));
        save(SupplierProductPrice.register(SUPPLIER_ID, UUID.randomUUID(), "AUD", new BigDecimal("2.000000")));

        assertThat(REPO.listForSupplier(SUPPLIER_ID)).hasSize(2);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void save(SupplierProductPrice price) {
        TX.executeWithoutResult(s -> REPO.save(price));
    }

    private long countOutboxOfType(String eventTypeFragment) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM purchasing.outbox_message WHERE event_type LIKE ?",
            Long.class, "%" + eventTypeFragment + "%");
    }
}
