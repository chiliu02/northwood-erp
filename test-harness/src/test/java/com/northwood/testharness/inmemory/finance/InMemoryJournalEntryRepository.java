package com.northwood.testharness.inmemory.finance;

import com.northwood.finance.domain.JournalEntry;
import com.northwood.finance.domain.JournalEntryId;
import com.northwood.finance.domain.JournalEntryRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link JournalEntryRepository}. The harness doesn't enforce
 * the {@code finance.enforce_journal_balance} deferred trigger; tests that
 * care about GL balance assert via the journal entries collected here.
 */
public final class InMemoryJournalEntryRepository implements JournalEntryRepository {

    private final Map<UUID, JournalEntry> store = new HashMap<>();
    private final Map<UUID, String> statusOverrides = new HashMap<>();

    @Override
    public Optional<JournalEntry> findById(JournalEntryId id) {
        return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public void save(JournalEntry entry) {
        store.put(entry.id().value(), entry);
    }

    @Override
    public void markReversed(JournalEntryId originalId) {
        statusOverrides.put(originalId.value(), "reversed");
    }

    @Override
    public List<JournalEntryId> findPostedIdsBySource(String sourceDocumentType, UUID sourceDocumentId) {
        // The harness doesn't model source_document_type/source_document_id on
        // the in-memory journal — return empty so reverseBySourceDocument is a
        // no-op in tests that don't exercise reversal.
        return List.of();
    }

    /** Test-side: enumerate all journal entries written. */
    public List<JournalEntry> all() {
        return new ArrayList<>(store.values());
    }
}
