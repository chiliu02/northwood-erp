package com.northwood.inventory.api;

import com.northwood.inventory.application.ShipmentService;
import com.northwood.inventory.application.ShipmentService.ShipmentLineProductMismatchException;
import com.northwood.inventory.application.dto.PostShipmentCommand;
import com.northwood.inventory.application.dto.ShipmentView;
import com.northwood.shared.api.security.RequireWarehouseClerk;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShipmentService service;

    public ShipmentController(ShipmentService service) {
        this.service = service;
    }

    @PostMapping
    @RequireWarehouseClerk
    public ResponseEntity<ShipmentView> post(@Valid @RequestBody PostShipmentCommand command) {
        ShipmentView view = service.post(command);
        return ResponseEntity
            .created(URI.create("/api/shipments/" + view.id()))
            .body(view);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShipmentView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /** Headers only (no lines) — list-view endpoint, most recent first. */
    @GetMapping
    public List<ShipmentView> list() {
        return service.findAllHeaders();
    }

    @ExceptionHandler(ShipmentLineProductMismatchException.class)
    public ResponseEntity<String> handleLineProductMismatch(ShipmentLineProductMismatchException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
