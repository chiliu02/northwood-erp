package com.northwood.purchasing.api;

import com.northwood.purchasing.application.PurchaseRequisitionService;
import com.northwood.purchasing.application.PurchaseRequisitionService.ProductDiscontinuedException;
import com.northwood.purchasing.application.dto.CreateRequisitionCommand;
import com.northwood.purchasing.application.dto.PurchaseRequisitionView;
import com.northwood.shared.api.security.RequirePurchasingClerk;
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
@RequestMapping("/api/purchase-requisitions")
public class PurchaseRequisitionController {

    private final PurchaseRequisitionService service;

    public PurchaseRequisitionController(PurchaseRequisitionService service) {
        this.service = service;
    }

    @PostMapping
    @RequirePurchasingClerk
    public ResponseEntity<PurchaseRequisitionView> create(
        @Valid @RequestBody CreateRequisitionCommand command
    ) {
        PurchaseRequisitionView view = service.createManual(command);
        return ResponseEntity
            .created(URI.create("/api/purchase-requisitions/" + view.id()))
            .body(view);
    }

    @GetMapping
    public List<PurchaseRequisitionView> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PurchaseRequisitionView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

}
