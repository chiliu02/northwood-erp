package com.northwood.inventory.api;

import com.northwood.inventory.application.StockAdjustmentService;
import com.northwood.inventory.application.dto.AdjustStockCommand;
import com.northwood.inventory.application.dto.StockAdjustmentView;
import com.northwood.inventory.application.dto.StockBalanceView;
import com.northwood.shared.api.security.RequireWarehouseManager;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual stock-adjustment API. Posting requires the {@code warehouse_manager}
 * role. The {@code /balance} read backs the screen's before→after preview
 * (on-hand / reserved / available for a product).
 */
@RestController
@RequestMapping("/api/stock-adjustments")
public class StockAdjustmentController {

    private final StockAdjustmentService service;

    public StockAdjustmentController(StockAdjustmentService service) {
        this.service = service;
    }

    @PostMapping
    @RequireWarehouseManager
    public ResponseEntity<StockAdjustmentView> adjust(@Valid @RequestBody AdjustStockCommand command) {
        StockAdjustmentView view = service.adjust(command);
        return ResponseEntity
            .created(URI.create("/api/stock-adjustments/" + view.id()))
            .body(view);
    }

    /** Current balance for a product (warehouse defaults to MAIN) — drives the adjustment-screen preview. */
    @GetMapping("/balance")
    public StockBalanceView balance(
        @RequestParam UUID productId,
        @RequestParam(required = false) String warehouseCode
    ) {
        return service.findBalance(productId, warehouseCode);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StockAdjustmentView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<StockAdjustmentView> list() {
        return service.findAll();
    }
}
