package com.northwood.manufacturing.application;

import com.northwood.manufacturing.application.BomLookup.ActiveBom;
import com.northwood.manufacturing.application.BomLookup.Component;
import com.northwood.manufacturing.application.dto.ReleaseCommand;
import com.northwood.manufacturing.domain.Bom;
import com.northwood.manufacturing.domain.Routing;
import com.northwood.manufacturing.domain.RoutingOperation;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderMaterial;
import com.northwood.manufacturing.domain.WorkOrderOperation;
import com.northwood.manufacturing.application.saga.MakeToOrderSagaManager;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.shared.application.exception.NotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release a work order against a sales-order line. Walks the active BOM:
 * {@code raw} components become {@code work_order_material} rows on this WO;
 * {@code sub_assembly} components recursively spawn child work orders (with
 * their own BOM + Routing snapshot) and a child make-to-order saga at
 * {@code work_order_created}, so each sub-assembly drives its own
 * raw-material-reservation flow exactly the same way the top-level WO does.
 *
 * <p>The recursion is synchronous and runs inside the application service's
 * transaction: parent and all descendants are created or none are, and all
 * their {@code WorkOrderCreated} outbox writes flush together. Child sagas
 * are inserted at {@code work_order_created} with {@code work_order_id}
 * pre-attached so the next worker tick can advance them.
 */
@Service
public class WorkOrderReleaseService {

    public static class BomNotFoundException extends NotFoundException {
        public static final String CODE = "ACTIVE_BOM_NOT_FOUND";
        private final UUID finishedProductId;
        public BomNotFoundException(UUID finishedProductId) {
            super("No active BOM for finished_product_id=" + finishedProductId);
            this.finishedProductId = finishedProductId;
        }
        public UUID finishedProductId() { return finishedProductId; }
        @Override public String code() { return CODE; }
        @Override public Map<String, Object> params() { return Map.of("finishedProductId", finishedProductId); }
    }

    public static class RoutingNotFoundException extends NotFoundException {
        public static final String CODE = "ACTIVE_ROUTING_NOT_FOUND";
        private final UUID finishedProductId;
        public RoutingNotFoundException(UUID finishedProductId) {
            super("No active routing for finished_product_id=" + finishedProductId);
            this.finishedProductId = finishedProductId;
        }
        public UUID finishedProductId() { return finishedProductId; }
        @Override public String code() { return CODE; }
        @Override public Map<String, Object> params() { return Map.of("finishedProductId", finishedProductId); }
    }

    private static final Logger log = LoggerFactory.getLogger(WorkOrderReleaseService.class);

    private final WorkOrderRepository workOrders;
    private final RoutingQueryPort routings;
    private final BomLookup boms;
    private final MakeToOrderSagaManager sagaManager;

    public WorkOrderReleaseService(
        WorkOrderRepository workOrders,
        RoutingQueryPort routings,
        BomLookup boms,
        MakeToOrderSagaManager sagaManager
    ) {
        this.workOrders = workOrders;
        this.routings = routings;
        this.boms = boms;
        this.sagaManager = sagaManager;
    }

    @Transactional
    public WorkOrder release(ReleaseCommand command) {
        return releaseInternal(command);
    }

    private WorkOrder releaseInternal(ReleaseCommand command) {
        ActiveBom bom = boms.findActiveByFinishedProductId(command.finishedProductId())
            .orElseThrow(() -> new BomNotFoundException(command.finishedProductId()));

        Routing routing = routings.findActiveByFinishedProductId(command.finishedProductId())
            .orElseThrow(() -> new RoutingNotFoundException(command.finishedProductId()));

        BigDecimal planned = command.plannedQuantity();

        List<WorkOrderMaterial> materials = new ArrayList<>();
        List<Component> subAssemblyComponents = new ArrayList<>();
        for (Component c : bom.components()) {
            if (c.componentKind() == Bom.ComponentKind.SUB_ASSEMBLY) {
                subAssemblyComponents.add(c);
                continue;
            }
            materials.add(buildMaterial(c, planned));
        }

        List<WorkOrderOperation> operations = new ArrayList<>();
        for (RoutingOperation op : routing.operations()) {
            operations.add(new WorkOrderOperation(
                UUID.randomUUID(),
                op.operationSequence(),
                op.operationCode(),
                op.description(),
                op.workCenterId(),
                op.plannedSetupMinutes(),
                op.plannedRunMinutes(),
                WorkOrder.OperationStatus.PLANNED
            ));
        }

        WorkOrder workOrder = WorkOrder.release(
            command.workOrderNumber(),
            command.salesOrderHeaderId(),
            command.salesOrderLineId(),
            command.parentWorkOrderId(),
            command.finishedProductId(),
            command.finishedProductSku(),
            command.finishedProductName(),
            bom.bomHeaderId(),
            planned,
            materials,
            operations
        );

        workOrders.save(workOrder);
        log.info(
            "released work_order {} for sales_order_line={} (parent={}, planned_qty={}, materials={}, ops={}, sub_assemblies={})",
            workOrder.id().value(), command.salesOrderLineId(), command.parentWorkOrderId(),
            planned, materials.size(), operations.size(), subAssemblyComponents.size()
        );

        for (Component sub : subAssemblyComponents) {
            BigDecimal childPlanned = scaledQuantity(sub, planned);
            ReleaseCommand childCommand = new ReleaseCommand(
                WorkOrder.NUMBER_PREFIX + UUID.randomUUID().toString().substring(0, WorkOrder.NUMBER_SUFFIX_LENGTH).toUpperCase(),
                command.salesOrderHeaderId(),
                command.salesOrderLineId(),
                workOrder.id().value(),
                sub.componentProductId(),
                sub.componentSku(),
                sub.componentName(),
                childPlanned
            );
            WorkOrder child = releaseInternal(childCommand);
            sagaManager.insertAttachedToWorkOrder(
                command.salesOrderHeaderId(),
                command.salesOrderLineId(),
                child.id().value(),
                "{}"
            );
            log.info(
                "spawned child work_order {} for sub_assembly {} under parent {} ({} units)",
                child.id().value(), sub.componentSku(), workOrder.id().value(), childPlanned
            );
        }

        return workOrder;
    }

    private WorkOrderMaterial buildMaterial(Component c, BigDecimal planned) {
        BigDecimal required = scaledQuantity(c, planned);
        return new WorkOrderMaterial(
            UUID.randomUUID(),
            c.componentProductId(),
            c.componentSku(),
            c.componentName(),
            required,
            BigDecimal.ZERO,
            WorkOrder.MaterialLineStatus.REQUIRED
        );
    }

    private BigDecimal scaledQuantity(Component c, BigDecimal planned) {
        BigDecimal scrapMultiplier = BigDecimal.ONE.add(
            c.scrapFactorPercent().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
        );
        return c.quantityPerFinishedUnit()
            .multiply(planned)
            .multiply(scrapMultiplier)
            .setScale(4, RoundingMode.HALF_UP);
    }

}
