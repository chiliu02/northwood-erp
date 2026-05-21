package com.northwood.finance.domain;

import com.northwood.shared.domain.Currencies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JournalEntryTest {

    private static final UUID DOC = UUID.randomUUID();
    private static final UUID GL_DEBIT = UUID.fromString("00000000-0000-0000-0000-000000005000");
    private static final UUID GL_CREDIT = UUID.fromString("00000000-0000-0000-0000-000000002100");

    private static List<JournalEntryLine> balancedPair(BigDecimal amount) {
        return List.of(
            JournalEntryLine.debit(10, GL_DEBIT, "5000", "COGS",
                amount, "Cost", LocalDate.of(2026, 6, 1)),
            JournalEntryLine.credit(20, GL_CREDIT, "2100", "AP",
                amount, "Payable", LocalDate.of(2026, 6, 1))
        );
    }

    private static JournalEntry posted(BigDecimal amount) {
        return JournalEntry.post(
            "JE-001", LocalDate.of(2026, 6, 1),
            JournalEntry.SourceModule.FINANCE, JournalEntry.SourceDocumentType.SUPPLIER_INVOICE, DOC, "test",
            Currencies.AUD, BigDecimal.ONE,
            balancedPair(amount)
        );
    }

    @Nested
    class Post {
        @Test void requires_at_least_two_lines() {
            assertThatThrownBy(() -> JournalEntry.post(
                "JE", LocalDate.now(), JournalEntry.SourceModule.FINANCE, JournalEntry.SourceDocumentType.SUPPLIER_INVOICE, DOC, "x",
                Currencies.AUD, BigDecimal.ONE,
                List.of(JournalEntryLine.debit(10, GL_DEBIT, "5000", "x", BigDecimal.TEN, "d", LocalDate.now()))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void requires_balanced_debits_and_credits() {
            List<JournalEntryLine> unbalanced = List.of(
                JournalEntryLine.debit(10, GL_DEBIT, "5000", "x",
                    new BigDecimal("100"), "d", LocalDate.now()),
                JournalEntryLine.credit(20, GL_CREDIT, "2100", "x",
                    new BigDecimal("90"), "c", LocalDate.now())
            );
            assertThatThrownBy(() -> JournalEntry.post(
                "JE", LocalDate.now(), JournalEntry.SourceModule.FINANCE, JournalEntry.SourceDocumentType.SUPPLIER_INVOICE, DOC, "x",
                Currencies.AUD, BigDecimal.ONE, unbalanced
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void posts_at_status_posted() {
            JournalEntry je = posted(new BigDecimal("100.00"));
            assertThat(je.status()).isEqualTo(JournalEntry.Status.POSTED);
        }

        @Test void rejects_null_source_document_id() {
            assertThatThrownBy(() -> JournalEntry.post(
                "JE", LocalDate.now(), JournalEntry.SourceModule.FINANCE, JournalEntry.SourceDocumentType.SUPPLIER_INVOICE, null, "x",
                Currencies.AUD, BigDecimal.ONE, balancedPair(BigDecimal.TEN)
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ReverseOf {
        @Test void rejects_non_posted_original() {
            JournalEntry draft = JournalEntry.reconstitute(
                JournalEntryId.newId(), "JE-X", LocalDate.now(),
                JournalEntry.SourceModule.FINANCE, JournalEntry.SourceDocumentType.SUPPLIER_INVOICE, DOC, "x", JournalEntry.Status.DRAFT,
                Currencies.AUD, BigDecimal.ONE, java.time.Instant.now(),
                balancedPair(BigDecimal.TEN), 0L
            );
            assertThatThrownBy(() -> JournalEntry.reverseOf(draft, "test", null))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void produces_balanced_reversal_with_swapped_amounts() {
            JournalEntry original = posted(new BigDecimal("100.00"));
            JournalEntry reversal = JournalEntry.reverseOf(original, "supplier dispute", null);
            assertThat(reversal.lines()).hasSize(2);
            JournalEntryLine reversalDr = reversal.lines().get(0);
            JournalEntryLine reversalCr = reversal.lines().get(1);
            // Original line[0] was debit 100 → reversal[0] is credit 100 (amounts swapped).
            assertThat(reversalDr.creditAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(reversalDr.debitAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            // Original line[1] was credit 100 → reversal[1] is debit 100.
            assertThat(reversalCr.debitAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(reversalCr.creditAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test void links_to_original_via_source_document_id() {
            JournalEntry original = posted(new BigDecimal("100.00"));
            JournalEntry reversal = JournalEntry.reverseOf(original, "test", null);
            assertThat(reversal.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.JOURNAL_REVERSAL);
            assertThat(reversal.sourceDocumentId()).isEqualTo(original.id().value());
        }

        @Test void reversal_is_itself_posted() {
            JournalEntry original = posted(new BigDecimal("100.00"));
            JournalEntry reversal = JournalEntry.reverseOf(original, "test", null);
            assertThat(reversal.status()).isEqualTo(JournalEntry.Status.POSTED);
        }

        @Test void uses_provided_posting_date() {
            JournalEntry original = posted(new BigDecimal("100.00"));
            LocalDate when = LocalDate.of(2026, 12, 31);
            JournalEntry reversal = JournalEntry.reverseOf(original, "year-end", when);
            assertThat(reversal.postingDate()).isEqualTo(when);
        }

        @Test void description_mentions_original_journal_number() {
            JournalEntry original = posted(new BigDecimal("100.00"));
            JournalEntry reversal = JournalEntry.reverseOf(original, "duplicate posting", null);
            assertThat(reversal.description())
                .contains("Reversal of")
                .contains("JE-001")
                .contains("duplicate posting");
        }

        @Test void reversal_currency_matches_original() {
            JournalEntry original = posted(new BigDecimal("100.00"));
            JournalEntry reversal = JournalEntry.reverseOf(original, null, null);
            assertThat(reversal.currencyCode()).isEqualTo(original.currencyCode());
        }
    }
}
