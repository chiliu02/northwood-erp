package com.northwood.purchasing.api;

import com.northwood.purchasing.api.dto.ChangeSupplierStatusRequest;
import com.northwood.purchasing.api.dto.RegisterSupplierRequest;
import com.northwood.purchasing.api.dto.UpdateSupplierDetailsRequest;
import com.northwood.purchasing.application.SupplierService;
import com.northwood.purchasing.application.dto.SupplierView;
import com.northwood.shared.api.security.RequirePurchasingManager;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Supplier master API: read the list / detail, plus the manager-gated edit
 * commands — onboard, edit details, change status (active/inactive/blocked).
 * Backed by the {@link com.northwood.purchasing.domain.Supplier} aggregate.
 */
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @GetMapping
    public List<SupplierView> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SupplierView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /** Onboard a new supplier (lands {@code active}). 409 on a duplicate code. */
    @PostMapping
    @RequirePurchasingManager
    public ResponseEntity<SupplierView> register(@Valid @RequestBody RegisterSupplierRequest request) {
        SupplierView created = service.onboard(
            request.supplierCode(), request.name(), request.email(), request.phone(), request.address());
        return ResponseEntity
            .created(URI.create("/api/suppliers/" + created.supplierId()))
            .body(created);
    }

    /** Edit a supplier's details (name / email / phone / address). */
    @PutMapping("/{id}")
    @RequirePurchasingManager
    public ResponseEntity<SupplierView> updateDetails(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateSupplierDetailsRequest request
    ) {
        return ResponseEntity.ok(service.updateDetails(
            id, request.name(), request.email(), request.phone(), request.address()));
    }

    /** Change a supplier's status (active / inactive / blocked). */
    @PatchMapping("/{id}/status")
    @RequirePurchasingManager
    public ResponseEntity<SupplierView> changeStatus(
        @PathVariable UUID id,
        @Valid @RequestBody ChangeSupplierStatusRequest request
    ) {
        return ResponseEntity.ok(service.changeStatus(id, request.status(), request.reason()));
    }
}
