package com.northwood.reporting.api;

import com.northwood.reporting.application.dto.PurchaseOrderTrackingView;
import com.northwood.reporting.application.PurchaseOrderTrackingQueryPort;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderTrackingController {

    private final PurchaseOrderTrackingQueryPort port;

    public PurchaseOrderTrackingController(PurchaseOrderTrackingQueryPort port) {
        this.port = port;
    }

    @GetMapping("/{id}/tracking")
    public ResponseEntity<PurchaseOrderTrackingView> get(@PathVariable UUID id) {
        return port.findByPurchaseOrderId(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** All tracked POs, newest activity first. */
    @GetMapping
    public List<PurchaseOrderTrackingView> list() {
        return port.findAll();
    }
}
