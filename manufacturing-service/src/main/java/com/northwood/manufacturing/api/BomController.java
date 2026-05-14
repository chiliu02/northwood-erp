package com.northwood.manufacturing.api;

import com.northwood.manufacturing.api.dto.AddBomLineRequest;
import com.northwood.manufacturing.api.dto.AddBomLineResponse;
import com.northwood.manufacturing.api.dto.CreateBomDraftRequest;
import com.northwood.manufacturing.api.dto.CreateBomDraftResponse;
import com.northwood.manufacturing.application.BomEditService;
import com.northwood.manufacturing.application.BomEditService.AddLineCommand;
import com.northwood.manufacturing.application.BomEditService.BomCycleException;
import com.northwood.manufacturing.application.BomEditService.BomLineNotFoundException;
import com.northwood.manufacturing.application.BomEditService.BomNotEditableException;
import com.northwood.manufacturing.application.BomEditService.BomNotFoundException;
import com.northwood.manufacturing.application.BomEditService.CreateBomDraftCommand;
import com.northwood.manufacturing.application.BomTreeService;
import com.northwood.manufacturing.application.dto.BomTreeView;
import com.northwood.shared.api.security.RequireProductionPlanner;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/boms")
public class BomController {

    private final BomEditService editService;
    private final BomTreeService treeService;

    public BomController(BomEditService editService, BomTreeService treeService) {
        this.editService = editService;
        this.treeService = treeService;
    }

    /**
     * Read-only recursive BOM tree for a finished or semi-finished product.
     * Unrestricted beyond the global authentication filter — any signed-in
     * persona can view BOMs (Tom needs to see them to size requisitions,
     * Linda to plan, Olivia for cost context, etc.).
     */
    @GetMapping("/by-product/{finishedProductId}")
    public ResponseEntity<BomTreeView> getTreeByProduct(@PathVariable UUID finishedProductId) {
        return treeService.findActiveTreeByProductId(finishedProductId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Create a new BOM draft for a finished product. Subsequent calls to
     * {@code POST /api/boms/{bomHeaderId}/lines} populate it; finally
     * {@code POST /api/boms/{bomHeaderId}/activate} flips it to active
     * (subject to cycle detection and the partial-unique-active index).
     */
    @PostMapping
    @RequireProductionPlanner
    public ResponseEntity<CreateBomDraftResponse> createDraft(
        @Valid @RequestBody CreateBomDraftRequest request
    ) {
        UUID bomHeaderId = editService.createDraft(new CreateBomDraftCommand(
            request.finishedProductId(),
            request.finishedProductSku(),
            request.finishedProductName(),
            request.version()
        ));
        return ResponseEntity.status(201).body(new CreateBomDraftResponse(bomHeaderId));
    }

    @PostMapping("/{bomHeaderId}/lines")
    @RequireProductionPlanner
    public ResponseEntity<AddBomLineResponse> addLine(
        @PathVariable UUID bomHeaderId,
        @Valid @RequestBody AddBomLineRequest request
    ) {
        UUID bomLineId = editService.addLine(bomHeaderId, new AddLineCommand(
            request.componentProductId(),
            request.componentSku(),
            request.componentName(),
            request.componentKind(),
            request.quantityPerFinishedUnit(),
            request.scrapFactorPercent()
        ));
        return ResponseEntity.status(201).body(new AddBomLineResponse(bomLineId));
    }

    @DeleteMapping("/lines/{bomLineId}")
    @RequireProductionPlanner
    public ResponseEntity<Void> removeLine(@PathVariable UUID bomLineId) {
        editService.removeLine(bomLineId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{bomHeaderId}/activate")
    @RequireProductionPlanner
    public ResponseEntity<Void> activate(@PathVariable UUID bomHeaderId) {
        editService.activate(bomHeaderId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({BomNotFoundException.class, BomLineNotFoundException.class})
    public ResponseEntity<String> handleNotFound(RuntimeException e) {
        return ResponseEntity.status(404).body(e.getMessage());
    }

    @ExceptionHandler({BomCycleException.class, BomNotEditableException.class})
    public ResponseEntity<String> handleConflict(RuntimeException e) {
        return ResponseEntity.status(409).body(e.getMessage());
    }
}
