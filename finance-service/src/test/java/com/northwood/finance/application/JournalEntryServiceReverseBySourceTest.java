package com.northwood.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.finance.domain.JournalEntry;
import com.northwood.finance.domain.JournalEntryId;
import com.northwood.finance.domain.JournalEntryLine;
import com.northwood.finance.domain.JournalEntryRepository;
import com.northwood.finance.application.GlAccountLookup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JournalEntryServiceReverseBySourceTest {

    private static final UUID GL = UUID.fromString("00000000-0000-0000-0000-000000005000");

    private JournalEntryRepository journals;
    private JournalEntrySummaryQueryPort summaries;
    private GlAccountLookup glAccounts;
    private ProductCardLookup productCards;
    private JournalEntryService service;

    @BeforeEach
    void setUp() {
        journals = Mockito.mock(JournalEntryRepository.class);
        summaries = Mockito.mock(JournalEntrySummaryQueryPort.class);
        glAccounts = Mockito.mock(GlAccountLookup.class);
        productCards = Mockito.mock(ProductCardLookup.class);
        service = new JournalEntryService(journals, summaries, glAccounts, productCards);
    }

    private JournalEntry postedEntry(JournalEntryId id, String sourceType, UUID sourceId, BigDecimal amount) {
        return JournalEntry.reconstitute(
            id,
            "JE-" + id.value().toString().substring(0, 4),
            LocalDate.of(2026, 6, 1),
            JournalEntry.SourceModule.FINANCE,
            sourceType,
            sourceId,
            "test",
            JournalEntry.Status.POSTED,
            "AUD",
            BigDecimal.ONE,
            java.time.Instant.now(),
            List.of(
                JournalEntryLine.debit(10, GL, "5000", "COGS",
                    amount, "Cost", LocalDate.of(2026, 6, 1)),
                JournalEntryLine.credit(20, GL, "2100", "AP",
                    amount, "Payable", LocalDate.of(2026, 6, 1))
            ),
            1L
        );
    }

    @Test void empty_source_returns_empty_list_and_makes_no_writes() {
        UUID source = UUID.randomUUID();
        when(journals.findPostedIdsBySource("customer_invoice", source))
            .thenReturn(List.of());

        List<UUID> result = service.reverseBySourceDocument(
            "customer_invoice", source, "test", LocalDate.of(2026, 6, 1)
        );

        assertThat(result).isEmpty();
        verify(journals, never()).save(any());
        verify(journals, never()).markReversed(any());
    }

    @Test void reverses_all_matching_posted_entries() {
        UUID source = UUID.randomUUID();
        JournalEntryId id1 = JournalEntryId.of(UUID.randomUUID());
        JournalEntryId id2 = JournalEntryId.of(UUID.randomUUID());
        when(journals.findPostedIdsBySource("customer_invoice", source))
            .thenReturn(List.of(id1, id2));
        when(journals.findById(id1)).thenReturn(Optional.of(
            postedEntry(id1, "customer_invoice", source, new BigDecimal("100"))
        ));
        when(journals.findById(id2)).thenReturn(Optional.of(
            postedEntry(id2, "customer_invoice", source, new BigDecimal("250"))
        ));

        List<UUID> result = service.reverseBySourceDocument(
            "customer_invoice", source, "cancel", LocalDate.of(2026, 6, 1)
        );

        assertThat(result).hasSize(2);  // two new reversal entries
        verify(journals, times(2)).save(any());           // each reversal saved
        verify(journals).markReversed(id1);
        verify(journals).markReversed(id2);
    }

    @Test void already_reversed_entries_are_filtered_out_by_repository() {
        // The repository's findPostedIdsBySource is responsible for filtering
        // status='reversed'; here we exercise the contract by returning only
        // the posted ones and asserting we don't try to reverse anything else.
        UUID source = UUID.randomUUID();
        JournalEntryId stillPosted = JournalEntryId.of(UUID.randomUUID());
        when(journals.findPostedIdsBySource("supplier_payment", source))
            .thenReturn(List.of(stillPosted));
        when(journals.findById(stillPosted)).thenReturn(Optional.of(
            postedEntry(stillPosted, "supplier_payment", source, new BigDecimal("500"))
        ));

        service.reverseBySourceDocument(
            "supplier_payment", source, "refund", LocalDate.of(2026, 6, 1)
        );

        verify(journals).markReversed(stillPosted);
        verify(journals, times(1)).save(any());
    }
}
