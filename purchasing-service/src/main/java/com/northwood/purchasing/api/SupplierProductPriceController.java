package com.northwood.purchasing.api;

import com.northwood.purchasing.api.dto.SetSupplierProductPriceRequest;
import com.northwood.purchasing.application.SupplierProductPriceService;
import com.northwood.purchasing.application.dto.PriceView;
import com.northwood.shared.api.security.RequirePurchasingManager;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authoring endpoints for the supplier price list. PUT replaces the
 * unit price for a (supplier, product, currency) tuple; emits
 * {@code purchasing.SupplierProductPriceChanged} via the outbox.
 */
@RestController
@RequestMapping("/api/supplier-product-prices")
public class SupplierProductPriceController {

    private final SupplierProductPriceService service;

    public SupplierProductPriceController(SupplierProductPriceService service) {
        this.service = service;
    }

    @PutMapping
    @RequirePurchasingManager
    public ResponseEntity<Void> setPrice(@Valid @RequestBody SetSupplierProductPriceRequest request) {
        UUID id = service.setPrice(
            request.supplierId(),
            request.productId(),
            request.currencyCode(),
            request.unitPrice()
        );
        return ResponseEntity
            .created(URI.create("/api/supplier-product-prices/" + id))
            .build();
    }

    @GetMapping("/by-supplier/{supplierId}")
    public List<PriceView> listForSupplier(@PathVariable UUID supplierId) {
        return service.listForSupplier(supplierId);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<String> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
