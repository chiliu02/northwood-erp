package com.northwood.finance.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository {

    Optional<JournalEntry> findById(JournalEntryId id);

    void save(JournalEntry entry);

    /**
     * Mark a posted entry as reversed. The schema's
     * {@code guard_journal_immutability} trigger allows
     * {@code posted → reversed} but rejects every other transition out of
     * posted. This method must be called only on a reversal slice that has
     * already saved the negating entry — the original transitions to
     * 'reversed' as the final step.
     */
    void markReversed(JournalEntryId originalId);

    /**
     * Return ids of every {@code posted} journal entry that originated from
     * the given source document. Used by bulk reversal: cancelling a
     * customer-invoice or supplier-payment cascades reversals of every GL
     * entry posted from that source.
     *
     * <p>Excludes entries already in {@code 'reversed'} status (would be a
     * no-op to reverse again), and excludes entries whose
     * {@code source_document_type = 'journal_reversal'} — those are
     * themselves reversal entries and the schema rejects reversal-of-reversal.
     */
    List<JournalEntryId> findPostedIdsBySource(String sourceDocumentType, UUID sourceDocumentId);
}
