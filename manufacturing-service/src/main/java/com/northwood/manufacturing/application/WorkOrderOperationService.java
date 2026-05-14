package com.northwood.manufacturing.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.application.dto.CompleteOperationCommand;
import com.northwood.manufacturing.application.dto.WorkOrderView;
import com.northwood.manufacturing.application.saga.MakeToOrderSagaManager;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderOperation;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.manufacturing.domain.WorkOrderRepository.CompletedChild;
import com.northwood.manufacturing.domain.events.SubAssembliesConsumed;
import com.northwood.manufacturing.domain.events.SubAssembliesConsumed.ConsumedItem;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for advancing operations on an existing work order.
 * Loads the work order, applies {@link WorkOrder#completeOperation}, and saves
 * — the aggregate emits {@code OperationCompleted} (and, on the last operation
 * + no pending children, {@code WorkOrderManufacturingCompleted}) to the
 * outbox in the same transaction.
 *
 * <p>Sub-assembly gating: a parent work order with sub-assembly children
 * cannot complete until every child has completed too. The aggregate's
 * {@code completeOperation(seq, mins, noPendingChildren)} respects that flag,
 * and this service computes it by counting unfinished children. When a
 * <i>child</i> finishes, the cascade walks up the parent chain via
 * {@link WorkOrder#onChildCompleted(boolean)} so a parent that was waiting on
 * its last child can finish in the same transaction.
 *
 * <p>Sagas: each work order in the tree owns its own
 * {@code make_to_order_saga} row. When any WO in the tree completes, its
 * saga is advanced to {@code completed} here (no self-consuming inbox loop —
 * the saga is in manufacturing's bounded context).
 *
 * <p>§3.3 sub-assembly WIP consume: when a parent WO completes, this service
 * also emits {@code manufacturing.SubAssembliesConsumed} listing each
 * immediate child's product + completedQuantity. Inventory consumes that
 * event to decrement {@code wip_balance.on_hand_quantity} — pairing with the
 * WIP bumps that fire on each child WO's {@code WorkOrderManufacturingCompleted}.
 */
@Service
public class WorkOrderOperationService {

    public static class WorkOrderNotFoundException extends RuntimeException {
        public WorkOrderNotFoundException(String workOrderId) {
            super("No work order with id=" + workOrderId);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(WorkOrderOperationService.class);

    private final WorkOrderRepository workOrders;
    private final MakeToOrderSagaManager sagaManager;
    private final OutboxPort outbox;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public WorkOrderOperationService(
        WorkOrderRepository workOrders,
        MakeToOrderSagaManager sagaManager,
        OutboxPort outbox,
        ObjectMapper json,
        CurrentUserAccessor currentUser
    ) {
        this.workOrders = workOrders;
        this.sagaManager = sagaManager;
        this.outbox = outbox;
        this.json = json;
        this.currentUser = currentUser;
    }

    /**
     * Read endpoint for the WorkOrder aggregate. Lives on the operation service
     * (rather than a dedicated read service) because operation completion is
     * the primary command-side caller; co-locating the read keeps the
     * controller dependency surface small. If a separate read-only need
     * emerges, split into a dedicated query service.
     */
    @Transactional(readOnly = true)
    public Optional<WorkOrderView> findById(UUID workOrderId) {
        return workOrders.findById(WorkOrderId.of(workOrderId)).map(WorkOrderView::from);
    }

    @Transactional
    public void completeOperation(CompleteOperationCommand command) {
        WorkOrderId workOrderId = WorkOrderId.of(command.workOrderId());
        WorkOrder workOrder = workOrders.findById(workOrderId)
            .orElseThrow(() -> new WorkOrderNotFoundException(command.workOrderId().toString()));

        boolean noPendingChildren = workOrders.countUnfinishedChildren(workOrder.id().value()) == 0;
        workOrder.completeOperation(command.operationSequence(), command.actualMinutes(), noPendingChildren);
        workOrders.save(workOrder);

        log.info("completed operation {} on work_order {} (status={}, actual_minutes={})",
            command.operationSequence(), workOrder.id().value(), workOrder.status(), command.actualMinutes());

        if (WorkOrder.COMPLETED.equals(workOrder.status())) {
            onWorkOrderCompleted(workOrder);
            if (workOrder.parentWorkOrderId() != null) {
                cascadeToParent(workOrder.parentWorkOrderId(), workOrder.id().value());
            }
        }
    }

    /**
     * §3.5: skip an operation by sequence. Same WO-completion cascade as
     * {@link #completeOperation} — if this is the last op (and no children
     * pending), the WO transitions to {@code completed} and the saga / parent
     * cascade fires identically.
     */
    @Transactional
    public void skipOperation(UUID workOrderId, int operationSequence, String reason) {
        WorkOrder workOrder = workOrders.findById(WorkOrderId.of(workOrderId))
            .orElseThrow(() -> new WorkOrderNotFoundException(workOrderId.toString()));

        boolean noPendingChildren = workOrders.countUnfinishedChildren(workOrder.id().value()) == 0;
        workOrder.skipOperation(operationSequence, reason, noPendingChildren);
        workOrders.save(workOrder);

        log.info("skipped operation {} on work_order {} (status={}, reason={})",
            operationSequence, workOrder.id().value(), workOrder.status(), reason);

        if (WorkOrder.COMPLETED.equals(workOrder.status())) {
            onWorkOrderCompleted(workOrder);
            if (workOrder.parentWorkOrderId() != null) {
                cascadeToParent(workOrder.parentWorkOrderId(), workOrder.id().value());
            }
        }
    }

    private void cascadeToParent(UUID parentId, UUID justCompletedChildId) {
        WorkOrder parent = workOrders.findById(WorkOrderId.of(parentId))
            .orElseThrow(() -> new IllegalStateException(
                "Parent work_order " + parentId + " not found while cascading completion from child " + justCompletedChildId
            ));

        boolean siblingsAllDone = workOrders.countUnfinishedChildrenExcluding(parentId, justCompletedChildId) == 0;
        parent.onChildCompleted(siblingsAllDone);

        if (!WorkOrder.COMPLETED.equals(parent.status())) {
            log.debug("parent work_order {} held: ops_done={}, sibling_pending_count={}",
                parentId, parent.operations().stream().allMatch(o -> WorkOrderOperation.COMPLETED.equals(o.status())),
                workOrders.countUnfinishedChildrenExcluding(parentId, justCompletedChildId));
            return;
        }

        workOrders.save(parent);
        log.info("parent work_order {} completed (cascaded from child {})", parentId, justCompletedChildId);

        onWorkOrderCompleted(parent);

        if (parent.parentWorkOrderId() != null) {
            cascadeToParent(parent.parentWorkOrderId(), parentId);
        }
    }

    /**
     * Post-completion hook. Advances the WO's own saga and (§3.3) emits
     * {@code SubAssembliesConsumed} if this WO is a parent that just
     * consumed its sub-assembly children's outputs.
     */
    private void onWorkOrderCompleted(WorkOrder workOrder) {
        advanceSagaToCompleted(workOrder);
        emitSubAssembliesConsumedIfParent(workOrder);
    }

    /**
     * Emit {@code manufacturing.SubAssembliesConsumed} listing each immediate
     * sub-assembly child + its completed quantity, so inventory's
     * {@code WipBalanceWriter} can decrement WIP for each child product.
     *
     * <p><b>Silent-fallback contract.</b>
     * {@code CompletedChild.completedQuantity} is read from
     * {@code manufacturing.work_order.completed_quantity} via
     * {@code findCompletedChildren} — only rows with {@code status = 'completed'}
     * are returned, and a completed WO must have a non-null quantity. So
     * encountering a null is a data-corruption signal: the {@code completed}
     * status was set without persisting the quantity. Rather than throwing
     * (which would block the parent WO's completion + freeze the saga), the
     * null is treated as zero, the {@code qty.signum() <= 0} check skips that
     * child silently, and a WARN log fires naming the parent + child so the
     * divergence is visible. Per the silent-fallback rule, the consumer
     * (inventory's {@code SubAssembliesConsumedHandler}) trusts the emitted
     * quantities verbatim — a missing child means WIP for that child stays
     * elevated until manual reconciliation.
     */
    private void emitSubAssembliesConsumedIfParent(WorkOrder workOrder) {
        List<CompletedChild> children = workOrders.findCompletedChildren(workOrder.id().value());
        if (children.isEmpty()) {
            return;  // not a parent (no sub-assembly children) — nothing to consume.
        }

        List<ConsumedItem> items = new ArrayList<>();
        int nullQtyChildren = 0;
        for (CompletedChild c : children) {
            BigDecimal qty = c.completedQuantity();
            if (qty == null) {
                nullQtyChildren++;
                continue;
            }
            if (qty.signum() <= 0) continue;
            items.add(new ConsumedItem(c.workOrderId(), c.finishedProductId(), qty));
        }
        if (nullQtyChildren > 0) {
            log.warn(
                "emitSubAssembliesConsumedIfParent parent_work_order={} encountered {} child(ren) with null completed_quantity "
                    + "despite status='completed' (data-corruption signal); those children will not decrement WIP",
                workOrder.id().value(), nullQtyChildren
            );
        }
        if (items.isEmpty()) {
            return;
        }

        SubAssembliesConsumed event = new SubAssembliesConsumed(
            UUID.randomUUID(),
            workOrder.id().value(),
            items,
            Instant.now()
        );
        try {
            outbox.appendPending(OutboxRow.pending(
                event.eventId(),
                WorkOrder.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                null, null, null,
                currentUser.currentUsername().orElse(null)
            ));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialise " + SubAssembliesConsumed.EVENT_TYPE, e);
        }
        log.info("emitted {} for parent work_order={} ({} child WO(s))",
            SubAssembliesConsumed.EVENT_TYPE, workOrder.id().value(), items.size());
    }

    private void advanceSagaToCompleted(WorkOrder workOrder) {
        sagaManager.applyManufacturingCompleted(workOrder.id().value());
    }

}
