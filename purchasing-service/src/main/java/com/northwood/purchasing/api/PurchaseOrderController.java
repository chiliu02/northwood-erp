package com.northwood.purchasing.api;

import com.northwood.purchasing.api.dto.ApprovePurchaseOrderRequest;
import com.northwood.purchasing.api.dto.RejectPurchaseOrderRequest;
import com.northwood.purchasing.application.PurchaseOrderService;
import com.northwood.purchasing.application.PurchaseOrderService.PoNotApprovableException;
import com.northwood.purchasing.application.dto.PurchaseOrderView;
import com.northwood.shared.api.security.RequirePurchasingManager;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read + approve endpoints for purchase orders. POs are created via the
 * requisition-conversion path ({@code PurchaseRequisitionService}); this
 * controller handles the approval transition for draft POs (manual flow).
 */
@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService service;

    public PurchaseOrderController(PurchaseOrderService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<PurchaseOrderView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Approve a draft PO. Flips status to {@code 'sent'}, emits
     * {@code purchasing.PurchaseOrderApproved}, and advances the P2P saga
     * from {@code started → purchase_order_approved}.
     */
    @PostMapping("/{id}/approve")
    @RequirePurchasingManager
    public ResponseEntity<PurchaseOrderView> approve(
        @PathVariable UUID id,
        @Valid @RequestBody ApprovePurchaseOrderRequest request
    ) {
        // 404 for an unknown PO, then fail-fast on an unapprovable one (zero /
        // inconsistent header totals) BEFORE the write transaction is opened —
        // assertApprovable is read-only and throws PoNotApprovableException (409)
        // so an invalid request never reaches the mutating service.approve().
        // The domain re-checks inside approve() as the in-transaction backstop.
        if (service.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        service.assertApprovable(id);
        service.approve(id, request.approver(), request.reason());
        PurchaseOrderView body = service.findById(id).orElseThrow();
        return ResponseEntity.ok(body);
    }

    /**
     * Reject (cancel) a draft PO — the manager's "bin this draft" counterpart to
     * approve, for an erroneous draft (wrong supplier, zero-priced lines that
     * can't be approved). Flips status {@code draft → cancelled}, emits
     * {@code purchasing.PurchaseOrderCancelled}, and terminates the P2P saga at
     * {@code cancelled}. 404 for an unknown PO; 409 if it isn't a draft.
     */
    @PostMapping("/{id}/reject")
    @RequirePurchasingManager
    public ResponseEntity<PurchaseOrderView> reject(
        @PathVariable UUID id,
        @Valid @RequestBody RejectPurchaseOrderRequest request
    ) {
        if (service.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        service.assertRejectable(id);
        service.reject(id, request.rejectedBy(), request.reason());
        PurchaseOrderView body = service.findById(id).orElseThrow();
        return ResponseEntity.ok(body);
    }

}
