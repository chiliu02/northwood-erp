package com.northwood.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.inventory.application.dto.StockBalanceView;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-Postgres test for the stock-balance writer. The schema
 * has three CHECK constraints on {@code inventory.stock_balance}:
 *
 * <ul>
 *   <li>{@code on_hand_quantity >= 0}</li>
 *   <li>{@code reserved_quantity >= 0}</li>
 *   <li>{@code on_hand_quantity >= reserved_quantity}</li>
 * </ul>
 *
 * <p>The in-memory test harness doesn't enforce these. This test asserts the
 * writer's SQL plays well with them — specifically that decrementing below
 * zero raises a CHECK violation (which {@code ShipmentService.post}'s
 * defence-in-depth validation is supposed to prevent), and that
 * {@code tryReserveOnHand} returns {@code false} rather than throwing when
 * insufficient stock is available.
 */
class JdbcStockBalanceWriterIT {

    private static final UUID SEED_WAREHOUSE_ID =
        UUID.fromString("00000000-0000-7000-8000-000000000020");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcStockBalanceWriter WRITER;
    private static JdbcStockBalanceLookup LOOKUP;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        loadBaseline();
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = inventory, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        WRITER = new JdbcStockBalanceWriter(JDBC);
        LOOKUP = new JdbcStockBalanceLookup(JDBC);
    }

    private static void loadBaseline() {
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp_seed.sql"));
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

    private UUID productId;

    @BeforeEach
    void freshProduct() {
        // Fresh UUID per test — stock_balance UNIQUE on (warehouse_id, product_id)
        // means each test gets its own row without cleanup.
        productId = UUID.randomUUID();
    }

    private record Balance(BigDecimal onHand, BigDecimal reserved, long version) {}

    private Balance read() {
        return JDBC.queryForObject("""
            SELECT on_hand_quantity, reserved_quantity, version
              FROM inventory.stock_balance
             WHERE warehouse_id = ? AND product_id = ?
            """,
            (rs, n) -> new Balance(
                rs.getBigDecimal("on_hand_quantity"),
                rs.getBigDecimal("reserved_quantity"),
                rs.getLong("version")
            ),
            SEED_WAREHOUSE_ID, productId
        );
    }

    @Test
    void bump_creates_row_on_first_call() {
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("10")));

        Balance b = read();
        assertThat(b.onHand()).isEqualByComparingTo("10");
        assertThat(b.reserved()).isEqualByComparingTo("0");
    }

    @Test
    void bump_is_additive_on_existing_row() {
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("10")));
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("5")));

        Balance b = read();
        assertThat(b.onHand()).isEqualByComparingTo("15");
    }

    /**
     * Concurrency regression for the multi-partition read-modify-write hazard
     * (docs/messaging.md → <em>Hazards when scaling past 1 partition</em>, item 3):
     * many {@code bump}s first-touch the <em>same</em> {@code (warehouse, product)}
     * row at once — the case multi-partition consumption makes real (e.g. two
     * goods-receipts for a brand-new product on different partition threads). The
     * former {@code UPDATE}-then-{@code INSERT}-on-zero-rows seeded the row in a
     * second statement, so two threads could both see zero rows and both
     * {@code INSERT}, the loser hitting {@code UNIQUE (warehouse_id, product_id)} →
     * {@code DataIntegrityViolationException} → DLT. The single-statement
     * {@code ON CONFLICT … DO UPDATE} upsert collapses seed + add atomically, so
     * every concurrent first-touch converges: no exception, one row, sum preserved.
     */
    @Test
    void bump_is_race_safe_on_concurrent_first_touch() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("10")));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }
            start.countDown();   // release all threads to contend on the same fresh row
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors)
            .withFailMessage("concurrent first-touch bump must not raise (the upsert collapses the seed race): %s", errors)
            .isEmpty();
        assertThat(read().onHand())
            .withFailMessage("every concurrent bump must be applied exactly once")
            .isEqualByComparingTo(new BigDecimal(threads * 10));
        Integer rowCount = JDBC.queryForObject(
            "SELECT COUNT(*) FROM inventory.stock_balance WHERE warehouse_id = ? AND product_id = ?",
            Integer.class, SEED_WAREHOUSE_ID, productId);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void tryReserveOnHand_succeeds_when_sufficient_available() {
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("10")));

        Boolean ok = TX.execute(s ->
            WRITER.tryReserveOnHand(SEED_WAREHOUSE_ID, productId, new BigDecimal("4")));

        assertThat(ok).isTrue();
        Balance b = read();
        assertThat(b.onHand()).isEqualByComparingTo("10");
        assertThat(b.reserved()).isEqualByComparingTo("4");
    }

    @Test
    void tryReserveOnHand_returns_false_when_insufficient_available() {
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("3")));

        Boolean ok = TX.execute(s ->
            WRITER.tryReserveOnHand(SEED_WAREHOUSE_ID, productId, new BigDecimal("5")));

        // Must return false rather than throw — the saga relies on the boolean
        // outcome to decide partial/failed branches.
        assertThat(ok).isFalse();
        Balance b = read();
        assertThat(b.reserved()).isEqualByComparingTo("0");
    }

    @Test
    void decrementOnHandAndReleaseReserved_subtracts_both_columns() {
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("10")));
        TX.executeWithoutResult(s -> WRITER.tryReserveOnHand(SEED_WAREHOUSE_ID, productId, new BigDecimal("4")));
        TX.executeWithoutResult(s -> WRITER.decrementOnHandAndReleaseReserved(
            SEED_WAREHOUSE_ID, productId, new BigDecimal("4")));

        Balance b = read();
        assertThat(b.onHand()).isEqualByComparingTo("6");
        assertThat(b.reserved()).isEqualByComparingTo("0");
    }

    @Test
    void decrementOnHandAndReleaseReserved_with_more_shipped_than_reserved_clamps_reserved_at_zero() {
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("10")));
        TX.executeWithoutResult(s -> WRITER.tryReserveOnHand(SEED_WAREHOUSE_ID, productId, new BigDecimal("3")));
        // Shipping 5 with 3 reserved — reserved capped at 0 via LEAST(...) in SQL.
        TX.executeWithoutResult(s -> WRITER.decrementOnHandAndReleaseReserved(
            SEED_WAREHOUSE_ID, productId, new BigDecimal("5")));

        Balance b = read();
        assertThat(b.onHand()).isEqualByComparingTo("5");
        assertThat(b.reserved()).isEqualByComparingTo("0");
    }

    @Test
    void decrementOnHandAndReleaseReserved_below_zero_violates_schema_CHECK() {
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("2")));

        // Trying to ship more than on_hand violates CHECK (on_hand_quantity >= 0).
        // This is the failure mode that the shipment-line product-validation
        // defends against — if a buggy client mismatches productId, the shipment
        // hits this CHECK eventually.
        assertThatThrownBy(() ->
            TX.executeWithoutResult(s -> WRITER.decrementOnHandAndReleaseReserved(
                SEED_WAREHOUSE_ID, productId, new BigDecimal("5")))
        )
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("violates check constraint");
    }

    @Test
    void releaseReserved_subtracts_reserved_without_touching_on_hand() {
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("10")));
        TX.executeWithoutResult(s -> WRITER.tryReserveOnHand(SEED_WAREHOUSE_ID, productId, new BigDecimal("4")));
        TX.executeWithoutResult(s -> WRITER.releaseReserved(SEED_WAREHOUSE_ID, productId, new BigDecimal("4")));

        Balance b = read();
        assertThat(b.onHand()).isEqualByComparingTo("10");
        assertThat(b.reserved()).isEqualByComparingTo("0");
    }

    @Test
    void version_bumps_on_each_write() {
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("10")));
        long v1 = read().version();
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("5")));
        long v2 = read().version();

        assertThat(v2).isEqualTo(v1 + 1);
    }

    // ------------------------------------------------------------------
    // Stock-adjustment additions: decrementOnHand + findBalance
    // ------------------------------------------------------------------

    @Test
    void decrementOnHand_subtracts_on_hand_and_leaves_reserved_untouched() {
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("10")));
        TX.executeWithoutResult(s -> WRITER.tryReserveOnHand(SEED_WAREHOUSE_ID, productId, new BigDecimal("4")));

        Boolean ok = TX.execute(s -> WRITER.decrementOnHand(SEED_WAREHOUSE_ID, productId, new BigDecimal("3")));

        assertThat(ok).isTrue();
        Balance b = read();
        assertThat(b.onHand()).isEqualByComparingTo("7");
        assertThat(b.reserved()).isEqualByComparingTo("4"); // reserved NOT released by an adjustment
    }

    @Test
    void decrementOnHand_returns_false_when_it_would_breach_reserved() {
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("10")));
        TX.executeWithoutResult(s -> WRITER.tryReserveOnHand(SEED_WAREHOUSE_ID, productId, new BigDecimal("4")));

        // 10 - 7 = 3 < reserved 4 → guard fails, no row updated, no CHECK violation.
        Boolean ok = TX.execute(s -> WRITER.decrementOnHand(SEED_WAREHOUSE_ID, productId, new BigDecimal("7")));

        assertThat(ok).isFalse();
        Balance b = read();
        assertThat(b.onHand()).isEqualByComparingTo("10"); // unchanged
        assertThat(b.reserved()).isEqualByComparingTo("4");
    }

    @Test
    void decrementOnHand_returns_false_when_row_missing() {
        Boolean ok = TX.execute(s -> WRITER.decrementOnHand(SEED_WAREHOUSE_ID, productId, new BigDecimal("1")));
        assertThat(ok).isFalse();
    }

    @Test
    void findBalance_returns_on_hand_reserved_available_triple() {
        TX.executeWithoutResult(s -> WRITER.bump(SEED_WAREHOUSE_ID, productId, new BigDecimal("10")));
        TX.executeWithoutResult(s -> WRITER.tryReserveOnHand(SEED_WAREHOUSE_ID, productId, new BigDecimal("3")));

        StockBalanceView v = LOOKUP.findBalance(SEED_WAREHOUSE_ID, productId).orElseThrow();
        assertThat(v.onHand()).isEqualByComparingTo("10");
        assertThat(v.reserved()).isEqualByComparingTo("3");
        assertThat(v.available()).isEqualByComparingTo("7");
    }

    @Test
    void findBalance_is_empty_when_no_row() {
        Optional<StockBalanceView> v = LOOKUP.findBalance(SEED_WAREHOUSE_ID, productId);
        assertThat(v).isEmpty();
    }
}
