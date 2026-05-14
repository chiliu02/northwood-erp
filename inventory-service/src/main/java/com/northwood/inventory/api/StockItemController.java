package com.northwood.inventory.api;

import com.northwood.inventory.application.StockItemService;
import com.northwood.inventory.application.dto.StockItemView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API. Reorder policy is owned by product-service (Shape A);
 * inventory exposes its projected view only — no write endpoints for facets
 * that originate elsewhere.
 */
@RestController
@RequestMapping("/api/stock-items")
public class StockItemController {

    private final StockItemService service;

    public StockItemController(StockItemService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<StockItemView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/by-product/{productId}")
    public ResponseEntity<StockItemView> getByProductId(@PathVariable UUID productId) {
        return service.findByProductId(productId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /** All projected stock items, ordered by SKU. Used by the demo UI list view. */
    @GetMapping
    public List<StockItemView> list() {
        return service.findAll();
    }
}
