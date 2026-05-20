package com.northwood.finance.api;

import com.northwood.finance.api.dto.ReverseBySourceRequest;
import com.northwood.finance.api.dto.ReverseBySourceResponse;
import com.northwood.finance.api.dto.ReverseJournalEntryRequest;
import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.application.JournalEntrySummaryQueryPort.JournalEntrySummary;
import com.northwood.finance.application.dto.JournalEntryView;
import com.northwood.shared.api.security.RequireFinanceManager;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read + reversal endpoints for journal entries. Originating GL postings
 * are not exposed via this controller — they're side effects of the AP/AR
 * service flows (supplier-invoice approval, payments, customer-invoice
 * creation). Reversal is the only mutation a finance operator initiates
 * directly on the GL.
 */
@RestController
@RequestMapping("/api/journal-entries")
public class JournalEntryController {

    private final JournalEntryService service;

    public JournalEntryController(JournalEntryService service) {
        this.service = service;
    }

    /**
     * Recent journal entries, newest first. Lightweight summaries (header
     * fields + per-row debit-side total + line count) — drill into
     * {@code GET /{id}} for the full lines.
     */
    @GetMapping
    public List<JournalEntrySummary> list(
        @RequestParam(defaultValue = "100") int limit,
        @RequestParam(required = false) String sourceDocumentType
    ) {
        return service.findRecent(limit, Optional.ofNullable(sourceDocumentType));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JournalEntryView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Reverse a posted journal entry. Returns the new reversal entry; the
     * original entry's status moves to {@code 'reversed'} in the same
     * transaction.
     */
    @PostMapping("/{id}/reverse")
    @RequireFinanceManager
    public ResponseEntity<JournalEntryView> reverse(
        @PathVariable UUID id,
        @Valid @RequestBody ReverseJournalEntryRequest request
    ) {
        UUID reversalId = service.reverseEntry(
            id,
            request.reason(),
            request.postingDate()
        );
        JournalEntryView body = service.findById(reversalId).orElseThrow();
        return ResponseEntity
            .created(URI.create("/api/journal-entries/" + reversalId))
            .body(body);
    }

    /**
     * §3.7 Bulk reversal: reverse every posted journal entry whose source
     * document matches. Useful for cancellation cascades — cancelling a
     * customer-invoice reverses the AR/Revenue entry, cancelling a
     * supplier-payment reverses the AP/Bank entry, etc. Idempotent
     * (already-reversed entries are silently skipped via the underlying
     * filter on {@code status='posted'}).
     */
    @PostMapping("/reverse-by-source")
    @RequireFinanceManager
    public ResponseEntity<ReverseBySourceResponse> reverseBySource(
        @Valid @RequestBody ReverseBySourceRequest request
    ) {
        List<UUID> reversalIds = service.reverseBySourceDocument(
            request.sourceDocumentType(),
            request.sourceDocumentId(),
            request.reason(),
            request.postingDate()
        );
        return ResponseEntity.ok(new ReverseBySourceResponse(
            reversalIds.size(),
            reversalIds
        ));
    }

}
