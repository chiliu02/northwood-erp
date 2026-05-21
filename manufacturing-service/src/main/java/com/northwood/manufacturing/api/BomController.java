package com.northwood.manufacturing.api;

import com.northwood.manufacturing.api.dto.AddBomLineRequest;
import com.northwood.manufacturing.api.dto.AddBomLineResponse;
import com.northwood.manufacturing.api.dto.CreateBomDraftRequest;
import com.northwood.manufacturing.api.dto.CreateBomDraftResponse;
import com.northwood.manufacturing.application.BomService;
import com.northwood.manufacturing.application.BomService.AddLineCommand;
import com.northwood.manufacturing.application.BomService.BomCycleException;
import com.northwood.manufacturing.application.BomService.BomLineNotFoundException;
import com.northwood.manufacturing.application.BomService.BomNotEditableException;
import com.northwood.manufacturing.application.BomService.BomNotFoundException;
import com.northwood.manufacturing.application.BomService.CreateBomDraftCommand;
import com.northwood.manufacturing.application.BomViewService;
import com.northwood.manufacturing.application.dto.BomFlatComponentView;
import com.northwood.manufacturing.application.dto.BomTreeView;
import com.northwood.shared.api.security.RequireProductionPlanner;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/boms")
public class BomController {

    private final BomService service;
    private final BomViewService viewService;

    public BomController(BomService service, BomViewService viewService) {
        this.service = service;
        this.viewService = viewService;
    }

    /**
     * Read-only recursive BOM tree for a finished or semi-finished product.
     * Unrestricted beyond the global authentication filter — any signed-in
     * persona can view BOMs (Tom needs to see them to size requisitions,
     * Linda to plan, Olivia for cost context, etc.).
     */
    @GetMapping("/by-product/{finishedProductId}")
    public ResponseEntity<BomTreeView> getTreeByProduct(@PathVariable UUID finishedProductId) {
        return viewService.findActiveTreeByProductId(finishedProductId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Flat list of every component in the BOM hierarchy, each carrying its
     * cumulative per-finished-unit quantity (multiplied through every
     * ancestor's quantity × scrap-factor). Returns an empty list (HTTP 200)
     * when the product has no active BOM. Same authorization posture as the
     * tree endpoint above.
     */
    @GetMapping("/by-product/{finishedProductId}/flat")
    public ResponseEntity<List<BomFlatComponentView>> getFlatByProduct(@PathVariable UUID finishedProductId) {
        return ResponseEntity.ok(viewService.findFlatComponentsByProductId(finishedProductId));
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
        UUID bomHeaderId = service.createDraft(new CreateBomDraftCommand(
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
        UUID bomLineId = service.addLine(bomHeaderId, new AddLineCommand(
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
        service.removeLine(bomLineId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{bomHeaderId}/activate")
    @RequireProductionPlanner
    public ResponseEntity<Void> activate(@PathVariable UUID bomHeaderId) {
        service.activate(bomHeaderId);
        return ResponseEntity.noContent().build();
    }


}
