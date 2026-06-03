package com.northwood.purchasing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.purchasing.domain.Supplier;
import com.northwood.purchasing.domain.SupplierId;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-Postgres test for {@link JdbcSupplierRepository} — the Supplier
 * aggregate's load/save: register→insert round-trip, changeStatus update with
 * version bump + a SupplierStatusChanged outbox row, existsByCode, defaultSupplier.
 */
class JdbcSupplierRepositoryIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcSupplierRepository REPO;

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
        REPO = new JdbcSupplierRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
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
        JDBC.execute("TRUNCATE purchasing.supplier, purchasing.outbox_message CASCADE");
    }

    @Test
    void register_then_find_round_trip() {
        Supplier s = Supplier.register("SUP-IT1", "IT Supplier", "it@x.example", "+61 2", "Sydney");
        REPO.save(s);

        Supplier loaded = REPO.findByCode("SUP-IT1").orElseThrow();
        assertThat(loaded.name()).isEqualTo("IT Supplier");
        assertThat(loaded.email()).isEqualTo("it@x.example");
        assertThat(loaded.status()).isEqualTo(Supplier.Status.ACTIVE);
        assertThat(REPO.findById(loaded.id())).isPresent();
        assertThat(REPO.existsByCode("SUP-IT1")).isTrue();
        assertThat(REPO.existsByCode("NOPE")).isFalse();

        // SupplierRegistered landed in the outbox.
        Integer outbox = JDBC.queryForObject(
            "SELECT COUNT(*) FROM purchasing.outbox_message WHERE event_type = 'purchasing.SupplierRegistered'",
            Integer.class);
        assertThat(outbox).isEqualTo(1);
    }

    @Test
    void change_status_updates_with_version_bump_and_outbox() {
        Supplier s = Supplier.register("SUP-IT2", "IT Supplier 2", null, null, null);
        REPO.save(s);
        Supplier loaded = REPO.findByCode("SUP-IT2").orElseThrow();

        loaded.changeStatus(Supplier.Status.BLOCKED, "fraud");
        REPO.save(loaded);

        Supplier after = REPO.findByCode("SUP-IT2").orElseThrow();
        assertThat(after.status()).isEqualTo(Supplier.Status.BLOCKED);
        assertThat(after.version()).isEqualTo(2L); // inserted at 1, +1 on the status update
        Integer statusEvents = JDBC.queryForObject(
            "SELECT COUNT(*) FROM purchasing.outbox_message WHERE event_type = 'purchasing.SupplierStatusChanged'",
            Integer.class);
        assertThat(statusEvents).isEqualTo(1);
    }

    @Test
    void default_supplier_picks_first_active() {
        Supplier blocked = Supplier.register("SUP-AAA", "Blocked First", null, null, null);
        REPO.save(blocked);
        Supplier loadedBlocked = REPO.findByCode("SUP-AAA").orElseThrow();
        loadedBlocked.changeStatus(Supplier.Status.BLOCKED, "x");
        REPO.save(loadedBlocked);
        REPO.save(Supplier.register("SUP-BBB", "Active Second", null, null, null));

        // SUP-AAA sorts first by code but is blocked → default is SUP-BBB.
        assertThat(REPO.defaultSupplier().supplierCode()).isEqualTo("SUP-BBB");
    }
}
