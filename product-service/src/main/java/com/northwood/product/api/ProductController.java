package com.northwood.product.api;

import com.northwood.product.api.dto.ActivateBomRequest;
import com.northwood.product.api.dto.ChangeMakeVsBuyRequest;
import com.northwood.product.api.dto.ChangeReplenishmentStrategyRequest;
import com.northwood.product.api.dto.ChangeSalesPriceRequest;
import com.northwood.product.api.dto.ChangeStandardCostRequest;
import com.northwood.product.api.dto.CreateProductRequest;
import com.northwood.product.api.dto.SetApprovedVendorsRequest;
import com.northwood.product.api.dto.SetReorderPolicyRequest;
import com.northwood.product.api.dto.SetValuationClassRequest;
import com.northwood.product.application.ProductService;
import com.northwood.product.application.dto.ApprovedVendorCommand;
import com.northwood.product.application.dto.ProductView;
import com.northwood.shared.api.security.RequireCatalogManager;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    @RequireCatalogManager
    public ResponseEntity<ProductView> create(@Valid @RequestBody CreateProductRequest request) {
        ProductView view = service.createProduct(
            request.sku(),
            request.name(),
            request.description(),
            request.productType(),
            request.baseUomId(),
            request.salesPrice(),
            request.standardCost(),
            request.currencyCode()
        );
        return ResponseEntity
            .created(URI.create("/api/products/" + view.productId()))
            .body(view);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /** All products, ordered by SKU. Used by the demo UI catalog list. */
    @GetMapping
    public List<ProductView> list() {
        return service.findAll();
    }

    @PutMapping("/{id}/sales-price")
    @RequireCatalogManager
    public ResponseEntity<Void> changeSalesPrice(
        @PathVariable UUID id,
        @Valid @RequestBody ChangeSalesPriceRequest request
    ) {
        service.changeSalesPrice(id, request.salesPrice(), request.currencyCode());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/standard-cost")
    @RequireCatalogManager
    public ResponseEntity<Void> changeStandardCost(
        @PathVariable UUID id,
        @Valid @RequestBody ChangeStandardCostRequest request
    ) {
        service.changeStandardCost(id, request.standardCost(), request.currencyCode());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/reorder-policy")
    @RequireCatalogManager
    public ResponseEntity<Void> setReorderPolicy(
        @PathVariable UUID id,
        @Valid @RequestBody SetReorderPolicyRequest request
    ) {
        service.setReorderPolicy(id, request.reorderPoint(), request.reorderQuantity());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/make-vs-buy")
    @RequireCatalogManager
    public ResponseEntity<Void> changeMakeVsBuy(
        @PathVariable UUID id,
        @Valid @RequestBody ChangeMakeVsBuyRequest request
    ) {
        service.changeMakeVsBuy(id, request.isPurchased(), request.isManufactured());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/discontinue")
    @RequireCatalogManager
    public ResponseEntity<Void> discontinue(@PathVariable UUID id) {
        service.discontinue(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/replenishment-strategy")
    @RequireCatalogManager
    public ResponseEntity<Void> changeReplenishmentStrategy(
        @PathVariable UUID id,
        @Valid @RequestBody ChangeReplenishmentStrategyRequest request
    ) {
        service.changeReplenishmentStrategy(id, request.replenishmentStrategy());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/valuation-class")
    @RequireCatalogManager
    public ResponseEntity<Void> setValuationClass(
        @PathVariable UUID id,
        @Valid @RequestBody SetValuationClassRequest request
    ) {
        service.setValuationClass(id, request.valuationClass());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/active-bom")
    @RequireCatalogManager
    public ResponseEntity<Void> activateBom(
        @PathVariable UUID id,
        @Valid @RequestBody ActivateBomRequest request
    ) {
        service.activateBom(id, request.bomHeaderId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/approved-vendors")
    @RequireCatalogManager
    public ResponseEntity<Void> setApprovedVendors(
        @PathVariable UUID id,
        @Valid @RequestBody SetApprovedVendorsRequest request
    ) {
        service.setApprovedVendors(id, request.vendors().stream()
            .map(v -> new ApprovedVendorCommand(
                v.supplierId(), v.supplierCode(), v.supplierName(), v.preferred()))
            .toList());
        return ResponseEntity.noContent().build();
    }
}
