package com.northwood.inventory.api;

import com.northwood.inventory.application.GoodsReceiptService;
import com.northwood.inventory.application.GoodsReceiptService.GoodsReceiptLineProductMismatchException;
import com.northwood.inventory.application.dto.GoodsReceiptView;
import com.northwood.inventory.application.dto.PostGoodsReceiptCommand;
import com.northwood.shared.api.security.RequireWarehouseClerk;
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
@RequestMapping("/api/goods-receipts")
public class GoodsReceiptController {

    private final GoodsReceiptService service;

    public GoodsReceiptController(GoodsReceiptService service) {
        this.service = service;
    }

    @PostMapping
    @RequireWarehouseClerk
    public ResponseEntity<GoodsReceiptView> post(@Valid @RequestBody PostGoodsReceiptCommand command) {
        GoodsReceiptView view = service.post(command);
        return ResponseEntity
            .created(URI.create("/api/goods-receipts/" + view.id()))
            .body(view);
    }

    @GetMapping("/{id}")
    public ResponseEntity<GoodsReceiptView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /** Headers only (no lines) — list-view endpoint, most recent first. */
    @GetMapping
    public List<GoodsReceiptView> list() {
        return service.findAllHeaders();
    }

}
