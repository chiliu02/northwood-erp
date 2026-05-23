package com.northwood.sales.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.sales.application.CustomerService.DuplicateCustomerCodeException;
import com.northwood.sales.domain.Customer;
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
 * §2.25 Tier 2: real-Postgres test for {@link JdbcCustomerRepository}, covering
 * the branches a mocked unit test can't: insert→findById/findByCode round-trip
 * incl. the {@code status} {@code dbValue()}/{@code fromDb()} mapping + the
 * outbox row drained on save; a status-changing mutator persisted via the
 * update path; optimistic-lock conflict; and the {@code UNIQUE(customer_code)}
 * violation translated to {@link DuplicateCustomerCodeException}.
 */
class JdbcCustomerRepositoryIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcCustomerRepository REPO;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = sales, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcCustomerRepository(JDBC, new ObjectMapper(), new CurrentUserAccessor());
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
        JDBC.execute("TRUNCATE sales.customer, sales.outbox_message CASCADE");
    }

    @Test
    void save_insert_then_findById_round_trips_and_emits_outbox_row() {
        Customer c = newCustomer("CUST-RT-001");
        save(c);

        Customer r = REPO.findById(c.id()).orElseThrow();
        assertThat(r.customerCode()).isEqualTo("CUST-RT-001");
        assertThat(r.name()).isEqualTo("Acme Pty Ltd");
        assertThat(r.email()).isEqualTo("ap@acme.example");
        assertThat(r.phone()).isEqualTo("+61 2 0000 0000");
        assertThat(r.billingAddress()).isEqualTo("1 Bill St");
        assertThat(r.shippingAddress()).isEqualTo("2 Ship Rd");
        assertThat(r.status()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(r.version()).isEqualTo(1L);
        assertThat(countOutbox(c.id().value())).isEqualTo(1L);
    }

    @Test
    void findByCode_locates_the_customer() {
        Customer c = newCustomer("CUST-CODE-001");
        save(c);
        assertThat(REPO.findByCode("CUST-CODE-001")).isPresent()
            .get().extracting(x -> x.id().value()).isEqualTo(c.id().value());
        assertThat(REPO.findByCode("NOPE")).isEmpty();
    }

    @Test
    void update_persists_status_change_via_dbValue() {
        Customer c = newCustomer("CUST-DEAC-001");
        save(c);

        Customer loaded = REPO.findById(c.id()).orElseThrow();
        loaded.deactivate("no longer trading");
        save(loaded);

        Customer reloaded = REPO.findById(c.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(loaded.status());
        assertThat(dbStatus(c.id().value())).isEqualTo(loaded.status().dbValue());
    }

    @Test
    void stale_version_update_raises_optimistic_lock_failure() {
        Customer c = newCustomer("CUST-LOCK-001");
        save(c);

        Customer loadedA = REPO.findById(c.id()).orElseThrow();
        Customer loadedB = REPO.findById(c.id()).orElseThrow();

        loadedB.changeName("Acme Renamed");
        save(loadedB); // 1 → 2

        loadedA.changeName("Acme Stale");
        assertThatThrownBy(() -> save(loadedA))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void duplicate_customer_code_raises_DuplicateCustomerCodeException() {
        save(newCustomer("CUST-DUP-001"));
        assertThatThrownBy(() -> save(newCustomer("CUST-DUP-001")))
            .isInstanceOf(DuplicateCustomerCodeException.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Customer newCustomer(String code) {
        return Customer.register(
            code, "Acme Pty Ltd",
            "ap@acme.example", "+61 2 0000 0000",
            "1 Bill St", "2 Ship Rd"
        );
    }

    private void save(Customer c) {
        TX.executeWithoutResult(s -> REPO.save(c));
    }

    private long countOutbox(java.util.UUID aggregateId) {
        return JDBC.queryForObject(
            "SELECT COUNT(*) FROM sales.outbox_message WHERE aggregate_id = ?",
            Long.class, aggregateId);
    }

    private String dbStatus(java.util.UUID customerId) {
        return JDBC.queryForObject(
            "SELECT status FROM sales.customer WHERE customer_id = ?",
            String.class, customerId);
    }
}
