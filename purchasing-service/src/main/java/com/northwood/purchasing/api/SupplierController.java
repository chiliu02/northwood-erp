package com.northwood.purchasing.api;

import com.northwood.purchasing.application.SupplierService;
import com.northwood.purchasing.application.dto.SupplierView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API on the Supplier read model. Edit commands (onboard, update,
 * status changes) land in a future slice when supplier onboarding becomes a
 * user story; for now the showcase reads the baseline-seeded suppliers.
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
}
