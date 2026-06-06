package com.northwood.finance.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.finance.domain.JournalEntry;
import com.northwood.finance.domain.JournalEntryId;
import com.northwood.finance.domain.JournalEntryLine;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Tier-2 real-Postgres test for {@link JdbcJournalEntryRepository} — a
 * write-once aggregate with no outbox (ctor is {@code (JdbcTemplate,
 * CurrentUserAccessor)}). Covers the behaviour only a real DB exhibits:
 *
 * <ul>
 *   <li>the two-phase {@code save} (insert header {@code draft} → insert lines →
 *       UPDATE to {@code posted}) round-tripping via {@code findById};</li>
 *   <li>{@code markReversed} flipping {@code posted → reversed} and rejecting a
 *       second reversal (0-row update → {@code IllegalStateException});</li>
 *   <li>the deferred {@code enforce_journal_balance} trigger rejecting an
 *       unbalanced posting at COMMIT and rolling the whole entry back — the
 *       double-entry invariant enforced by the engine.</li>
 * </ul>
 */
class JdbcJournalEntryRepositoryIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static TransactionTemplate TX;
    private static JdbcJournalEntryRepository REPO;

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = finance, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        PlatformTransactionManager txm = new DataSourceTransactionManager(DATA_SOURCE);
        TX = new TransactionTemplate(txm);
        REPO = new JdbcJournalEntryRepository(JDBC, new CurrentUserAccessor());
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
        JDBC.execute("TRUNCATE finance.journal_entry_line, finance.journal_entry_header CASCADE");
    }

    @Test
    void save_posts_balanced_entry_two_phase_and_findById_round_trips() {
        JournalEntry entry = balancedEntry("JE-RT-001", new BigDecimal("100.00"));
        save(entry);

        JournalEntry r = REPO.findById(entry.id()).orElseThrow();
        assertThat(r.journalNumber()).isEqualTo("JE-RT-001");
        assertThat(r.sourceModule()).isEqualTo(JournalEntry.SourceModule.FINANCE);
        assertThat(r.status()).isEqualTo(JournalEntry.Status.POSTED);
        assertThat(r.version()).isEqualTo(2L); // insert(v1) + draft→posted update(v2)
        assertThat(r.lines()).hasSize(2);
        assertThat(r.lines().get(0).debitAmount()).isEqualByComparingTo("100.00");
        assertThat(r.lines().get(0).creditAmount()).isEqualByComparingTo("0");
        assertThat(r.lines().get(1).creditAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void markReversed_flips_posted_to_reversed_then_rejects_second_reversal() {
        JournalEntry entry = balancedEntry("JE-REV-001", new BigDecimal("250.00"));
        save(entry);

        TX.executeWithoutResult(s -> REPO.markReversed(entry.id()));
        assertThat(REPO.findById(entry.id()).orElseThrow().status())
            .isEqualTo(JournalEntry.Status.REVERSED);

        // Already reversed → the WHERE status='posted' update hits 0 rows.
        assertThatThrownBy(() -> TX.executeWithoutResult(s -> REPO.markReversed(entry.id())))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unbalanced_posting_is_rejected_at_commit_and_rolled_back() {
        UUID headerId = UUID.randomUUID();

        // Bypass the aggregate (which enforces balance in-memory) and hand-write an
        // unbalanced posted entry: debit 100, credit 50. The deferred
        // enforce_journal_balance trigger must reject it at COMMIT.
        assertThatThrownBy(() -> TX.executeWithoutResult(s -> insertUnbalancedPosted(headerId)))
            .isInstanceOf(Exception.class);

        // The whole transaction rolled back — nothing persisted.
        Long headers = JDBC.queryForObject(
            "SELECT COUNT(*) FROM finance.journal_entry_header WHERE journal_entry_header_id = ?",
            Long.class, headerId);
        assertThat(headers).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static JournalEntry balancedEntry(String journalNumber, BigDecimal amount) {
        LocalDate today = LocalDate.now();
        return JournalEntry.post(
            journalNumber, today,
            JournalEntry.SourceModule.FINANCE,
            JournalEntry.SourceDocumentType.GOODS_RECEIPT,
            UUID.randomUUID(), "IT balanced entry", "AUD", BigDecimal.ONE,
            List.of(
                JournalEntryLine.debit(1, UUID.randomUUID(), "1000", "Cash", amount, "dr", today),
                JournalEntryLine.credit(2, UUID.randomUUID(), "4000", "Revenue", amount, "cr", today)
            )
        );
    }

    private void save(JournalEntry entry) {
        TX.executeWithoutResult(s -> REPO.save(entry));
    }

    private void insertUnbalancedPosted(UUID headerId) {
        LocalDate today = LocalDate.now();
        JDBC.update("""
            INSERT INTO finance.journal_entry_header (
                journal_entry_header_id, journal_number, posting_date,
                source_module, source_document_type, source_document_id, status, version
            ) VALUES (?, ?, ?, 'finance', 'goods_receipt', ?, 'draft', 1)
            """,
            headerId, "JE-BAD-001", Date.valueOf(today), UUID.randomUUID());
        JDBC.update("""
            INSERT INTO finance.journal_entry_line (
                journal_entry_line_id, journal_entry_header_id, line_number,
                gl_account_id, account_code, account_name, debit_amount, credit_amount, posting_date
            ) VALUES (?, ?, 1, ?, '1000', 'Cash', 100.00, 0, ?)
            """,
            UUID.randomUUID(), headerId, UUID.randomUUID(), Date.valueOf(today));
        JDBC.update("""
            INSERT INTO finance.journal_entry_line (
                journal_entry_line_id, journal_entry_header_id, line_number,
                gl_account_id, account_code, account_name, debit_amount, credit_amount, posting_date
            ) VALUES (?, ?, 2, ?, '4000', 'Revenue', 0, 50.00, ?)
            """,
            UUID.randomUUID(), headerId, UUID.randomUUID(), Date.valueOf(today));
        JDBC.update(
            "UPDATE finance.journal_entry_header SET status = 'posted', posted_at = now(), version = 2 "
            + "WHERE journal_entry_header_id = ?",
            headerId);
    }
}
