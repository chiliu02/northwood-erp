package com.northwood.finance.infrastructure.persistence;

import com.northwood.finance.application.JournalEntrySummaryQueryPort;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcJournalEntrySummaryQueryPort implements JournalEntrySummaryQueryPort {

    private final JdbcTemplate jdbc;

    public JdbcJournalEntrySummaryQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<JournalEntrySummary> findRecent(int limit, Optional<String> sourceDocumentType) {
        String filter = sourceDocumentType.filter(s -> !s.isBlank()).orElse(null);
        if (filter == null) {
            return jdbc.query(
                """
                SELECT h.journal_entry_header_id,
                       h.journal_number,
                       h.posting_date,
                       h.source_module,
                       h.source_document_type,
                       h.source_document_id,
                       h.description,
                       h.status,
                       h.currency_code,
                       h.posted_at,
                       COALESCE(SUM(l.debit_amount), 0) AS total_amount,
                       COUNT(l.journal_entry_line_id)   AS line_count
                FROM finance.journal_entry_header h
                LEFT JOIN finance.journal_entry_line l
                       ON l.journal_entry_header_id = h.journal_entry_header_id
                GROUP BY h.journal_entry_header_id
                ORDER BY COALESCE(h.posted_at, h.created_at) DESC
                LIMIT ?
                """,
                MAPPER, limit
            );
        }
        return jdbc.query(
            """
            SELECT h.journal_entry_header_id,
                   h.journal_number,
                   h.posting_date,
                   h.source_module,
                   h.source_document_type,
                   h.source_document_id,
                   h.description,
                   h.status,
                   h.currency_code,
                   h.posted_at,
                   COALESCE(SUM(l.debit_amount), 0) AS total_amount,
                   COUNT(l.journal_entry_line_id)   AS line_count
            FROM finance.journal_entry_header h
            LEFT JOIN finance.journal_entry_line l
                   ON l.journal_entry_header_id = h.journal_entry_header_id
            WHERE h.source_document_type = ?
            GROUP BY h.journal_entry_header_id
            ORDER BY COALESCE(h.posted_at, h.created_at) DESC
            LIMIT ?
            """,
            MAPPER, filter, limit
        );
    }

    private static final RowMapper<JournalEntrySummary> MAPPER = (rs, n) -> {
        Timestamp postedAt = rs.getTimestamp("posted_at");
        return new JournalEntrySummary(
            rs.getObject("journal_entry_header_id", UUID.class),
            rs.getString("journal_number"),
            rs.getDate("posting_date").toLocalDate(),
            rs.getString("source_module"),
            rs.getString("source_document_type"),
            rs.getObject("source_document_id", UUID.class),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("currency_code"),
            rs.getBigDecimal("total_amount"),
            rs.getInt("line_count"),
            postedAt == null ? null : postedAt.toInstant()
        );
    };
}
