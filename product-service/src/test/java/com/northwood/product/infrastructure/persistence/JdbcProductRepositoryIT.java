package com.northwood.product.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.product.domain.ApprovedVendor;
import com.northwood.product.domain.Product;
import com.northwood.product.domain.ProductType;
import com.northwood.product.domain.ValuationClass;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.Sku;
import com.northwood.shared.domain.Money;
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
 * Real-Postgres test for {@link JdbcProductRepository}. Covers the
 * branches a mocked-{@code JdbcTemplate} unit test cannot reach:
 *
 * <ul>
 *   <li>{@code save}(insert) → {@code findById} round-trip incl. enum
 *       {@code dbValue()}/{@code fromDb()}, {@code Money}, and the
 *       {@code ProductCreated} outbox row drained on save;</li>
 *   <li>the {@code update} path writing {@code status} via {@code dbValue()}
 *       (regression guard for the {@code .name().toLowerCase()} fix);</li>
 *   <li>optimistic locking — a stale {@code version} write hits zero rows and
 *       raises {@link OptimisticLockingFailureException};</li>
 *   <li>{@code DataIntegrityViolationException} on the {@code UNIQUE(sku)}
 *       constraint translated to {@link JdbcProductRepository.DuplicateSkuException};</li>
 *   <li>the {@code approved_vendor} child collection delete+reinsert + ordered
 *       ({@code is_preferred DESC}) reload;</li>
 *   <li>nullable {@code valuation_class} reconstitute (null and set).</li>
 * </ul>
 */
class JdbcProductRepositoryIT {

    private static final UUID UOM_EACH = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcProductRepository REPO;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = product, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcProductRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
        // base_uom_id is a NOT NULL FK to product.unit_of_measure — seed one row.
        JDBC.update(
            "INSERT INTO product.unit_of_measure (uom_id, code, name) VALUES (?, 'EA-IT', 'Each (IT)')",
            UOM_EACH);
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
        JDBC.execute("TRUNCATE product.approved_vendor, product.product, product.outbox_message CASCADE");
    }

    @Test
    void save_insert_then_findById_round_trips_and_emits_outbox_row() {
        Product p = newProduct("FG-RT-001");
        save(p);

        Product r = REPO.findById(p.id()).orElseThrow();
        assertThat(r.sku().value()).isEqualTo("FG-RT-001");
        assertThat(r.name()).isEqualTo("Widget");
        assertThat(r.description()).isEqualTo("desc");
        assertThat(r.productType()).isEqualTo(ProductType.FINISHED_GOOD);
        assertThat(r.baseUomId()).isEqualTo(UOM_EACH);
        assertThat(r.salesPrice().amount()).isEqualByComparingTo("100.00");
        assertThat(r.standardCost().amount()).isEqualByComparingTo("60.00");
        assertThat(r.valuationClass()).isNull();
        assertThat(r.status()).isEqualTo(Product.Status.ACTIVE);
        assertThat(r.version()).isEqualTo(1L);
        assertThat(r.approvedVendors()).isEmpty();
        // register() emitted ProductCreated; save() drained it to the outbox.
        assertThat(countOutbox(p.id().value())).isEqualTo(1L);
    }

    @Test
    void findBySku_locates_the_product() {
        Product p = newProduct("FG-SKU-001");
        save(p);
        assertThat(REPO.findBySku("FG-SKU-001")).isPresent()
            .get().extracting(x -> x.id().value()).isEqualTo(p.id().value());
        assertThat(REPO.findBySku("NOPE")).isEmpty();
    }

    @Test
    void update_persists_discontinued_status_via_dbValue() {
        Product p = newProduct("FG-DISC-001");
        save(p);

        Product loaded = REPO.findById(p.id()).orElseThrow();
        loaded.discontinue();
        save(loaded);

        assertThat(dbStatus(p.id().value())).isEqualTo("discontinued");
        assertThat(REPO.findById(p.id()).orElseThrow().status())
            .isEqualTo(Product.Status.DISCONTINUED);
    }

    @Test
    void stale_version_update_raises_optimistic_lock_failure() {
        Product p = newProduct("FG-LOCK-001");
        save(p);

        Product loadedA = REPO.findById(p.id()).orElseThrow(); // version 1
        Product loadedB = REPO.findById(p.id()).orElseThrow(); // version 1

        loadedB.changeSalesPrice(Money.of(new BigDecimal("150.00"), Currencies.BASE_CURRENCY));
        save(loadedB); // 1 → 2, succeeds

        loadedA.changeSalesPrice(Money.of(new BigDecimal("175.00"), Currencies.BASE_CURRENCY));
        assertThatThrownBy(() -> save(loadedA))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void duplicate_sku_insert_raises_DuplicateSkuException() {
        save(newProduct("FG-DUP-001"));
        assertThatThrownBy(() -> save(newProduct("FG-DUP-001")))
            .isInstanceOf(JdbcProductRepository.DuplicateSkuException.class);
    }

    @Test
    void approved_vendors_round_trip_ordered_preferred_first() {
        Product p = newProduct("FG-AV-001");
        save(p);

        UUID supA = UUID.randomUUID();
        UUID supB = UUID.randomUUID();
        Product loaded = REPO.findById(p.id()).orElseThrow();
        loaded.setApprovedVendors(List.of(
            new ApprovedVendor(supB, "S-B", "Supplier B", false),
            new ApprovedVendor(supA, "S-A", "Supplier A", true)
        ));
        save(loaded);

        Product reloaded = REPO.findById(p.id()).orElseThrow();
        assertThat(reloaded.approvedVendors()).hasSize(2);
        // loadApprovedVendors orders by is_preferred DESC, supplier_code.
        assertThat(reloaded.approvedVendors().get(0).preferred()).isTrue();
        assertThat(reloaded.approvedVendors().get(0).supplierId()).isEqualTo(supA);
        assertThat(reloaded.approvedVendors().get(1).preferred()).isFalse();
        assertThat(reloaded.approvedVendors().get(1).supplierId()).isEqualTo(supB);
    }

    @Test
    void valuation_class_round_trips_null_and_set_via_dbValue() {
        Product p = newProduct("FG-VC-001");
        save(p);
        assertThat(REPO.findById(p.id()).orElseThrow().valuationClass()).isNull();

        Product loaded = REPO.findById(p.id()).orElseThrow();
        loaded.changeValuationClass(ValuationClass.FINISHED_GOODS);
        save(loaded);

        assertThat(REPO.findById(p.id()).orElseThrow().valuationClass())
            .isEqualTo(ValuationClass.FINISHED_GOODS);
        assertThat(dbValuationClass(p.id().value())).isEqualTo("finished_goods");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Product newProduct(String sku) {
        return Product.register(
            new Sku(sku), "Widget", "desc", ProductType.FINISHED_GOOD, UOM_EACH,
            Money.of(new BigDecimal("100.00"), Currencies.BASE_CURRENCY),
            Money.of(new BigDecimal("60.00"), Currencies.BASE_CURRENCY)
        );
    }

    private void save(Product p) {
        TX.executeWithoutResult(s -> REPO.save(p));
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM product.outbox_message WHERE aggregate_id = ?",
            Long.class, aggregateId);
    }

    private String dbStatus(UUID productId) {
        return JDBC.queryForObject(
            "SELECT status FROM product.product WHERE product_id = ?",
            String.class, productId);
    }

    private String dbValuationClass(UUID productId) {
        return JDBC.queryForObject(
            "SELECT valuation_class FROM product.product WHERE product_id = ?",
            String.class, productId);
    }
}
