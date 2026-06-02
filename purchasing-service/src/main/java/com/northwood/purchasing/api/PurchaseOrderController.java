package com.northwood.purchasing.api;

import com.northwood.purchasing.api.dto.ApprovePurchaseOrderRequest;
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
        service.approve(id, request.approver(), request.reason());
        PurchaseOrderView body = service.findById(id).orElseThrow();
        return ResponseEntity.ok(body);
    }


}
