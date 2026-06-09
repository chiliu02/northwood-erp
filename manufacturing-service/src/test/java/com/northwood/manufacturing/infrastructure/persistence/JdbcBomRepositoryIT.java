package com.northwood.manufacturing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.manufacturing.domain.Bom;
import com.northwood.manufacturing.domain.BomId;
import com.northwood.manufacturing.domain.BomLine;
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
import org.springframework.dao.DataIntegrityViolationException;
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
 * Real-Postgres test for {@link JdbcBomRepository} — a mutable
 * header + lines aggregate with the diff-based line persistence (DELETE removed
 * + INSERT added) and a {@code row_version} optimistic lock distinct from the
 * string {@code version} label. Covers the behaviour only a real DB exhibits:
 *
 * <ul>
 *   <li>{@code draft} + {@code addLine} → {@code findById} round-trip of header
 *       + lines incl. the {@code component_kind} {@code dbValue()}/{@code fromDb()}
 *       round-trip (RAW + SUB_ASSEMBLY) and the string {@code version} vs.
 *       numeric {@code row_version};</li>
 *   <li>{@code activate} flipping {@code draft → active} through the update path
 *       (row_version bump) + the {@code BomActivated} outbox row;</li>
 *   <li>the diff persistence: {@code removeLine} → DELETE + {@code addLine} →
 *       INSERT in one {@code save};</li>
 *   <li>the {@code row_version} optimistic lock → {@code OptimisticLockingFailureException};</li>
 *   <li>the partial unique index {@code uq_bom_active_per_product} rejecting a
 *       second active BOM for the same finished product →
 *       {@code DataIntegrityViolationException}.</li>
 * </ul>
 */
class JdbcBomRepositoryIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcBomRepository REPO;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = manufacturing, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcBomRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
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
        JDBC.execute("TRUNCATE manufacturing.bom_line, manufacturing.bom_header, "
            + "manufacturing.outbox_message CASCADE");
    }

    @Test
    void draft_with_lines_then_findById_round_trips_header_and_lines() {
        UUID productId = UUID.randomUUID();
        UUID rawId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        Bom bom = Bom.draft(productId, "FG-IT-1", "Finished 1", "1");
        bom.addLine(new BomLine.Spec(rawId, "RAW-1", "Raw 1", Bom.ComponentKind.RAW,
            new BigDecimal("2.000000"), new BigDecimal("5.0000")));
        bom.addLine(new BomLine.Spec(subId, "SUB-1", "Sub 1", Bom.ComponentKind.SUB_ASSEMBLY,
            new BigDecimal("1.000000"), BigDecimal.ZERO));
        save(bom);

        Bom r = REPO.findById(bom.id()).orElseThrow();
        assertThat(r.finishedProductId()).isEqualTo(productId);
        assertThat(r.finishedProductSku()).isEqualTo("FG-IT-1");
        assertThat(r.version()).isEqualTo("1");
        assertThat(r.status()).isEqualTo(Bom.Status.DRAFT);
        assertThat(r.aggregateVersion()).isEqualTo(1L); // persisted at row_version 1
        assertThat(r.lines()).hasSize(2);
        assertThat(r.lines().get(0).componentKind()).isEqualTo(Bom.ComponentKind.RAW);
        assertThat(r.lines().get(0).quantityPerFinishedUnit()).isEqualByComparingTo("2");
        assertThat(r.lines().get(0).scrapFactorPercent()).isEqualByComparingTo("5");
        assertThat(r.lines().get(1).componentKind()).isEqualTo(Bom.ComponentKind.SUB_ASSEMBLY);
        assertThat(r.lines().get(1).componentProductId()).isEqualTo(subId);
    }

    @Test
    void activate_via_update_path_persists_active_and_emits_outbox() {
        Bom bom = draftWithOneLine(UUID.randomUUID(), "1");
        save(bom);

        Bom loaded = REPO.findById(bom.id()).orElseThrow();
        loaded.activate();
        save(loaded);

        Bom r = REPO.findById(bom.id()).orElseThrow();
        assertThat(r.status()).isEqualTo(Bom.Status.ACTIVE);
        assertThat(r.aggregateVersion()).isEqualTo(2L); // insert(rv1) → update(rv2)
        assertThat(countOutbox(bom.id().value())).isEqualTo(1L); // BomActivated
    }

    @Test
    void removeLine_and_addLine_persist_via_delete_and_insert() {
        UUID productId = UUID.randomUUID();
        UUID keptId = UUID.randomUUID();
        UUID droppedId = UUID.randomUUID();
        Bom bom = Bom.draft(productId, "FG-IT-2", "Finished 2", "1");
        bom.addLine(new BomLine.Spec(droppedId, "RAW-D", "Dropped", Bom.ComponentKind.RAW,
            new BigDecimal("1.000000"), BigDecimal.ZERO));
        bom.addLine(new BomLine.Spec(keptId, "RAW-K", "Kept", Bom.ComponentKind.RAW,
            new BigDecimal("3.000000"), BigDecimal.ZERO));
        save(bom);

        Bom loaded = REPO.findById(bom.id()).orElseThrow();
        BomLine dropped = loaded.lines().stream()
            .filter(l -> l.componentProductId().equals(droppedId)).findFirst().orElseThrow();
        loaded.removeLine(dropped.id());
        UUID addedId = UUID.randomUUID();
        loaded.addLine(new BomLine.Spec(addedId, "RAW-A", "Added", Bom.ComponentKind.RAW,
            new BigDecimal("4.000000"), BigDecimal.ZERO));
        save(loaded);

        Bom r = REPO.findById(bom.id()).orElseThrow();
        assertThat(r.componentProductIds()).containsExactlyInAnyOrder(keptId, addedId);
        assertThat(r.componentProductIds()).doesNotContain(droppedId);
    }

    @Test
    void stale_row_version_update_raises_optimistic_lock_failure() {
        Bom bom = draftWithOneLine(UUID.randomUUID(), "1");
        save(bom);

        Bom loadedA = REPO.findById(bom.id()).orElseThrow();
        Bom loadedB = REPO.findById(bom.id()).orElseThrow();

        loadedB.activate();
        save(loadedB); // row_version 1 → 2

        loadedA.activate();
        assertThatThrownBy(() -> save(loadedA))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void second_active_bom_for_same_product_violates_unique_index() {
        UUID productId = UUID.randomUUID();

        Bom first = draftWithOneLine(productId, "1");
        save(first);
        Bom firstLoaded = REPO.findById(first.id()).orElseThrow();
        firstLoaded.activate();
        save(firstLoaded); // one active BOM for productId

        // A second BOM for the same finished product activates fine in memory,
        // but the partial unique index uq_bom_active_per_product rejects the
        // second active row at the DB.
        Bom second = draftWithOneLine(productId, "2");
        save(second); // insert as draft — allowed (index only covers active rows)
        Bom secondLoaded = REPO.findById(second.id()).orElseThrow();
        secondLoaded.activate();
        assertThatThrownBy(() -> save(secondLoaded))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Bom draftWithOneLine(UUID productId, String version) {
        Bom bom = Bom.draft(productId, "FG-" + version, "Finished " + version, version);
        bom.addLine(new BomLine.Spec(UUID.randomUUID(), "RAW-1", "Raw 1", Bom.ComponentKind.RAW,
            new BigDecimal("2.000000"), BigDecimal.ZERO));
        return bom;
    }

    private void save(Bom bom) {
        TX.executeWithoutResult(s -> REPO.save(bom));
    }

    private long countOutbox(UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM manufacturing.outbox_message WHERE aggregate_id = ?", Long.class, aggregateId);
    }
}
