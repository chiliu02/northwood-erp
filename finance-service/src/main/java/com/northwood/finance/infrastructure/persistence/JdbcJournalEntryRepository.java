package com.northwood.finance.infrastructure.persistence;

import com.northwood.finance.domain.JournalEntry;
import com.northwood.finance.domain.JournalEntryId;
import com.northwood.finance.domain.JournalEntryLine;
import com.northwood.finance.domain.JournalEntryRepository;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcJournalEntryRepository implements JournalEntryRepository {

    private static final RowMapper<JournalEntry> HEADER_MAPPER = (rs, n) -> {
        Date postingDate = rs.getDate("posting_date");
        Timestamp captured = rs.getTimestamp("exchange_rate_captured_at");
        return JournalEntry.reconstitute(
            JournalEntryId.of(rs.getObject("journal_entry_header_id", UUID.class)),
            rs.getString("journal_number"),
            postingDate.toLocalDate(),
            JournalEntry.SourceModule.fromDb(rs.getString("source_module")),
            rs.getString("source_document_type"),
            rs.getObject("source_document_id", UUID.class),
            rs.getString("description"),
            JournalEntry.Status.fromDb(rs.getString("status")),
            rs.getString("currency_code"),
            rs.getBigDecimal("exchange_rate"),
            captured == null ? null : captured.toInstant(),
            List.of(),
            rs.getLong("version")
        );
    };

    private static final RowMapper<JournalEntryLine> LINE_MAPPER = (rs, n) -> new JournalEntryLine(
        rs.getObject("journal_entry_line_id", UUID.class),
        rs.getInt("line_number"),
        rs.getObject("gl_account_id", UUID.class),
        rs.getString("account_code"),
        rs.getString("account_name"),
        rs.getBigDecimal("debit_amount"),
        rs.getBigDecimal("credit_amount"),
        rs.getString("description"),
        rs.getDate("posting_date").toLocalDate()
    );

    private final JdbcTemplate jdbc;
    private final CurrentUserAccessor currentUser;

    public JdbcJournalEntryRepository(JdbcTemplate jdbc, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<JournalEntry> findById(JournalEntryId id) {
        List<JournalEntry> matches = jdbc.query("""
            SELECT journal_entry_header_id, journal_number, posting_date,
                   source_module, source_document_type, source_document_id,
                   description, status,
                   currency_code, exchange_rate, exchange_rate_captured_at,
                   version
            FROM finance.journal_entry_header
            WHERE journal_entry_header_id = ?
            """, HEADER_MAPPER, id.value());
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        JournalEntry stub = matches.get(0);
        List<JournalEntryLine> lines = jdbc.query("""
            SELECT journal_entry_line_id, line_number, gl_account_id,
                   account_code, account_name,
                   debit_amount, credit_amount, description, posting_date
            FROM finance.journal_entry_line
            WHERE journal_entry_header_id = ?
            ORDER BY line_number
            """, LINE_MAPPER, id.value());
        return Optional.of(JournalEntry.reconstitute(
            stub.id(), stub.journalNumber(), stub.postingDate(),
            stub.sourceModule(), stub.sourceDocumentType(), stub.sourceDocumentId(),
            stub.description(), stub.status(),
            stub.currencyCode(), stub.exchangeRate(), stub.exchangeRateCapturedAt(),
            lines, stub.version()
        ));
    }

    @Override
    public void save(JournalEntry entry) {
        if (entry.version() != 0L) {
            throw new IllegalStateException("JournalEntry update path not supported in phase 5b");
        }
        String actor = currentUser.currentUsername().orElse(null);
        // The DB-level guard `guard_journal_line_immutability` rejects line
        // INSERTs when the parent header is already in status 'posted'. So we
        // insert the header at 'draft' first, then the lines, then UPDATE the
        // header to 'posted'. The balance-enforcement trigger fires at COMMIT
        // and rejects unbalanced postings — so a typo'd debit/credit pair in
        // the service layer surfaces as an error at txn close.
        jdbc.update("""
            INSERT INTO finance.journal_entry_header (
                journal_entry_header_id, journal_number, posting_date,
                source_module, source_document_type, source_document_id,
                description, status,
                currency_code, exchange_rate, exchange_rate_captured_at,
                version,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?, ?, ?)
            """,
            entry.id().value(), entry.journalNumber(), Date.valueOf(entry.postingDate()),
            entry.sourceModule().dbValue(), entry.sourceDocumentType(), entry.sourceDocumentId(),
            entry.description(),
            entry.currencyCode(), entry.exchangeRate(),
            entry.exchangeRateCapturedAt() == null ? Timestamp.from(Instant.now()) : Timestamp.from(entry.exchangeRateCapturedAt()),
            1L,
            actor, actor
        );
        for (JournalEntryLine l : entry.lines()) {
            jdbc.update("""
                INSERT INTO finance.journal_entry_line (
                    journal_entry_line_id, journal_entry_header_id, line_number,
                    gl_account_id, account_code, account_name,
                    debit_amount, credit_amount, description, posting_date
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                l.id(), entry.id().value(), l.lineNumber(),
                l.glAccountId(), l.accountCode(), l.accountName(),
                l.debitAmount(), l.creditAmount(),
                l.description(), Date.valueOf(l.postingDate())
            );
        }
        if (entry.status() == JournalEntry.Status.POSTED) {
            jdbc.update("""
                UPDATE finance.journal_entry_header
                SET status = 'posted', posted_at = now(), version = version + 1,
                    last_modified_by = ?
                WHERE journal_entry_header_id = ?
                """,
                actor,
                entry.id().value()
            );
        }
    }

    @Override
    public List<JournalEntryId> findPostedIdsBySource(String sourceDocumentType, UUID sourceDocumentId) {
        return jdbc.query(
            """
            SELECT journal_entry_header_id
            FROM finance.journal_entry_header
            WHERE source_document_type = ?
              AND source_document_id = ?
              AND status = 'posted'
              AND source_document_type <> 'journal_reversal'
            ORDER BY posted_at
            """,
            (rs, n) -> JournalEntryId.of(rs.getObject("journal_entry_header_id", UUID.class)),
            sourceDocumentType, sourceDocumentId
        );
    }

    @Override
    public void markReversed(JournalEntryId originalId) {
        // The DB-level guard_journal_immutability trigger allows posted →
        // reversed. Any other transition out of posted (or any change while
        // already reversed) raises an exception in PL/pgSQL.
        String actor = currentUser.currentUsername().orElse(null);
        int rows = jdbc.update("""
            UPDATE finance.journal_entry_header
            SET status = 'reversed', reversed_at = now(), version = version + 1,
                last_modified_by = ?
            WHERE journal_entry_header_id = ? AND status = 'posted'
            """,
            actor,
            originalId.value()
        );
        if (rows == 0) {
            throw new IllegalStateException(
                "Cannot mark journal_entry " + originalId.value()
                    + " as reversed (not posted, or already reversed)"
            );
        }
    }

}
