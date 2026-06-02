package com.northwood.finance.api;

import com.northwood.finance.api.dto.ManualReviewRequest;
import com.northwood.finance.application.SupplierInvoiceService;
import com.northwood.finance.application.dto.RecordSupplierInvoiceCommand;
import com.northwood.finance.application.dto.SupplierInvoiceView;
import com.northwood.shared.api.security.RequireAccountant;
import com.northwood.shared.api.security.RequireFinanceManager;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/supplier-invoices")
public class SupplierInvoiceController {

    private final SupplierInvoiceService service;

    public SupplierInvoiceController(SupplierInvoiceService service) {
        this.service = service;
    }

    @PostMapping
    @RequireAccountant
    public ResponseEntity<SupplierInvoiceView> record(
        @Valid @RequestBody RecordSupplierInvoiceCommand command
    ) {
        SupplierInvoiceView view = service.recordInvoice(command);
        return ResponseEntity
            .created(URI.create("/api/supplier-invoices/" + view.id()))
            .body(view);
    }

    /**
     * All supplier invoices, newest first. Operational AP-payment picker
     * uses this; the form filters client-side to status='approved'.
     */
    @GetMapping
    public List<SupplierInvoiceView> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SupplierInvoiceView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * List invoices currently parked at {@code three_way_match_failed} —
     * the manual-review queue. UI fetches this to show a pending list of
     * invoices needing reviewer action.
     */
    @GetMapping("/pending-review")
    public List<SupplierInvoiceView> pendingReview() {
        return service.findPendingReview();
    }

    /**
     * Manually approve an invoice that's parked at three_way_match_failed.
     * Reviewer override — emits {@code SupplierInvoiceApproved} so the
     * P2P saga advances unchanged, runs the GL posting, bumps the PO line
     * facts. Same downstream effect as the auto-approve path; just gated
     * on a human decision instead of the match algorithm.
     */
    @PostMapping("/{id}/manual-approve")
    @RequireFinanceManager
    public ResponseEntity<SupplierInvoiceView> manualApprove(
        @PathVariable UUID id,
        @Valid @RequestBody ManualReviewRequest request
    ) {
        // 404 for an unknown invoice, then fail fast on an inconsistent / zero
        // one BEFORE the write transaction + GL posting — assertApprovable is
        // read-only and throws if the header totals have drifted from the lines.
        if (service.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        service.assertApprovable(id);
        service.manualApprove(id, request.reviewer(), request.reason());
        SupplierInvoiceView body = service.findById(id).orElseThrow();
        return ResponseEntity.ok(body);
    }

    /**
     * Manually reject an invoice parked at three_way_match_failed. Status
     * flips to {@code 'cancelled'}; no event, no GL movement, no
     * projection update. Terminal.
     */
    @PostMapping("/{id}/reject")
    @RequireFinanceManager
    public ResponseEntity<SupplierInvoiceView> reject(
        @PathVariable UUID id,
        @Valid @RequestBody ManualReviewRequest request
    ) {
        service.manualReject(id, request.reviewer(), request.reason());
        SupplierInvoiceView body = service.findById(id).orElseThrow();
        return ResponseEntity.ok(body);
    }

}
