package com.northwood.manufacturing.api;

import com.northwood.manufacturing.api.dto.ProductMaterialsCostResponse;
import com.northwood.manufacturing.application.inbox.ProductMaterialsCostProjection;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read endpoint for the manufacturing-owned materialsCost.
 * The ERP UI's product detail page fetches this alongside the rest of the
 * product master from product-service; the BFF stitches the two together.
 *
 * <p>Returns 404 when the product has never been rolled up. The UI treats
 * 404 the same way it treats {@code reason="inputs_missing"}: render "—".
 */
@RestController
@RequestMapping("/api/products")
public class ProductMaterialsCostController {

    private final ProductMaterialsCostProjection materialsCosts;

    public ProductMaterialsCostController(ProductMaterialsCostProjection materialsCosts) {
        this.materialsCosts = materialsCosts;
    }

    @GetMapping("/{productId}/materials-cost")
    public ResponseEntity<ProductMaterialsCostResponse> get(@PathVariable UUID productId) {
        return materialsCosts.findByProductId(productId)
            .map(c -> new ProductMaterialsCostResponse(
                c.productId(),
                c.materialsCost(),
                c.currencyCode(),
                c.reason(),
                c.capturedAt()
            ))
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
