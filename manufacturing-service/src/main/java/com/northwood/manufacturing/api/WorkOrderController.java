package com.northwood.manufacturing.api;

import com.northwood.manufacturing.api.dto.CompleteOperationRequest;
import com.northwood.manufacturing.api.dto.SetPriorityRequest;
import com.northwood.manufacturing.api.dto.SkipOperationRequest;
import com.northwood.manufacturing.application.WorkOrderOperationService;
import com.northwood.manufacturing.application.WorkOrderOperationService.WorkOrderNotFoundException;
import com.northwood.manufacturing.application.WorkOrderPrioritisationService;
import com.northwood.manufacturing.application.dto.CompleteOperationCommand;
import com.northwood.manufacturing.application.dto.WorkOrderView;
import com.northwood.shared.api.security.RequireProductionPlanner;
import com.northwood.shared.api.security.RequireProductionSupervisor;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

    private final WorkOrderOperationService service;
    private final WorkOrderPrioritisationService prioritisation;

    public WorkOrderController(
        WorkOrderOperationService service,
        WorkOrderPrioritisationService prioritisation
    ) {
        this.service = service;
        this.prioritisation = prioritisation;
    }

    /**
     * Read endpoint for the WorkOrder aggregate. Returns header +
     * materials + operations so the ERP UI can render the operations
     * list with each op's status (planned / in_progress / completed /
     * skipped) and offer the right complete / skip buttons. Reporting's
     * production planning board carries the high-level status only —
     * the operator-facing per-op state lives on the aggregate itself.
     */
    @GetMapping("/{id}")
    public ResponseEntity<WorkOrderView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/operations/{sequence}/complete")
    @RequireProductionPlanner
    public ResponseEntity<Void> completeOperation(
        @PathVariable UUID id,
        @PathVariable int sequence,
        @Valid @RequestBody CompleteOperationRequest request
    ) {
        service.completeOperation(new CompleteOperationCommand(
            id,
            sequence,
            request.actualMinutes()
        ));
        return ResponseEntity.noContent().build();
    }

    /**
     * Skip an operation. From the WO state machine's perspective skipped
     * counts the same as completed — earlier ops still gate later ones, and a
     * skipped last op closes the WO. No {@code OperationCompleted} event fires
     * for skipped ops; the WO-level {@code WorkOrderManufacturingCompleted}
     * still fires when the whole WO is done.
     */
    @PostMapping("/{id}/operations/{sequence}/skip")
    @RequireProductionSupervisor
    public ResponseEntity<Void> skipOperation(
        @PathVariable UUID id,
        @PathVariable int sequence,
        @Valid @RequestBody SkipOperationRequest request
    ) {
        service.skipOperation(id, sequence, request.reason());
        return ResponseEntity.noContent().build();
    }

    /**
     * Re-prioritise a work order. Pure CQRS — emits
     * {@code manufacturing.WorkOrderPriorityChanged}; the production
     * planning board projection picks it up and updates {@code priority}.
     * The WO aggregate itself doesn't track priority today (no
     * manufacturing decision flow consumes it).
     */
    @PostMapping("/{id}/priority")
    @RequireProductionPlanner
    public ResponseEntity<Void> setPriority(
        @PathVariable UUID id,
        @Valid @RequestBody SetPriorityRequest request
    ) {
        prioritisation.setPriority(id, request.priority(), request.reason());
        return ResponseEntity.noContent().build();
    }


}
