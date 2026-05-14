# MakeToOrderSaga — state transitions

State-transition diagram for `com.northwood.manufacturing.domain.saga.MakeToOrderSaga`. Edge labels are `<class>.<method>, <current_step>`. One saga row per work order.

Post §2.9 Slice B (2026-05-10): every transition lives on `MakeToOrderSagaManager` (interface in `manufacturing.application.saga`, JDBC impl in `manufacturing.infrastructure.saga.JdbcMakeToOrderSagaManager`). The worker shell + 2 inbox handlers + `WorkOrderOperationService` + `WorkOrderCancellationService` + `WorkOrderReleaseService` are thin callers that delegate here.

## Happy path (top-level work order)

```
                  ManufacturingRequestedHandler.handle (per accepted line)
                  → MakeToOrderSagaManager.insertStarted
                                     ↓
                                 [started]
                                     ↓
              MakeToOrderSagaWorker.releaseWorkOrder (worker advance),
              wait_for_raw_material_reservation
                                     ↓
                          [work_order_created]
                                     ↓
              MakeToOrderSagaWorker.requestRawMaterialReservation (worker advance),
              wait_for_raw_materials_reserved
                                     ↓
                  [raw_material_reservation_requested]
                                     ↓
              MakeToOrderSagaManager.applyRawMaterialsReserved (status=reserved),
              raw_materials_reserved
                                     ↓
                       [raw_materials_reserved]
                                     ↓
              WorkOrderOperationService.completeOperation (last op finishes WO)
              → MakeToOrderSagaManager.applyManufacturingCompleted,
              production_completed
                                     ↓
                              [completed]   ← terminal
```

The final hop is driven inline by `WorkOrderOperationService.completeOperation` — public REST handler calls private `advanceSagaToCompleted(workOrder)` which calls `manager.applyManufacturingCompleted(workOrderId)`. For a sub-assembly parent, completion of the last child WO triggers the same cascade up via `WorkOrder.onChildCompleted(true)`.

## Sub-assembly entry path

Sub-assembly child WOs are created by `WorkOrderReleaseService` while releasing the parent and skip `started` entirely:

```
                  WorkOrderReleaseService.release (recursive child release)
                  → MakeToOrderSagaManager.insertAttachedToWorkOrder
                                     ↓
                          [work_order_created]
                                     ↓
              MakeToOrderSagaWorker.requestRawMaterialReservation (worker advance),
              wait_for_raw_materials_reserved
                                     ↓
                  [raw_material_reservation_requested]
                                     ↓
                                    ...
```

`MakeToOrderSagaManager.insertAttachedToWorkOrder` initialises the saga at `work_order_created` with `workOrderId` already attached, so the worker's next tick runs `requestRawMaterialReservation` directly.

## Side rail 1 — raw-material shortage and recovery

```
                  [raw_material_reservation_requested]
                                     ↓
              MakeToOrderSagaManager.applyRawMaterialsReserved (status≠reserved),
              raw_material_shortage
              (handler then emits manufacturing.RawMaterialShortageDetected)
                                     ↓
                       [raw_material_shortage]
                                     ↓
              GoodsReceivedHandler.handle
              → MakeToOrderSagaManager.unparkOrNarrowShortage (shortage covered),
              retry_raw_material_reservation
                                     ↓
                          [work_order_created]   ← rejoins happy path
```

`MakeToOrderSagaManager.applyRawMaterialsReserved` stashes the per-product shortage map onto `saga.data` before the transition. `MakeToOrderSagaManager.unparkOrNarrowShortage` decrements that map by each receipt's per-product quantity and only un-parks (loops back to `work_order_created`) when the shortage is fully covered. A partial cover narrows the stash without changing state, so the saga stays parked at `raw_material_shortage` until the remaining shortfall arrives.

## Side rail 2 — cancellation (driven by sales)

```
            [started | work_order_created | raw_material_reservation_requested |
             raw_materials_reserved | raw_material_shortage]
                                     ↓
              SalesOrderCancellationRequestedHandler.handle
              → WorkOrderCancellationService.cancelForSalesOrder (per WO)
              → MakeToOrderSagaManager.cancelForWorkOrder, cancelled_via_sales
                                     ↓
                            [compensated]   ← terminal
```

Manufacturing's compensation path skips `compensating` — `MakeToOrderSagaManager.cancelForWorkOrder` flips every active WO's saga straight to `compensated` in the same transaction as the WO mutation (idempotent on already-terminal sagas). The sales-fulfilment saga is the side that aggregates the two acks (inventory + manufacturing) and decides when to advance to `compensated`.

## States declared but never written

`failed` and `compensating` appear in `ALL_STATES` but no code path writes them today. `failed` is reserved for an unrecoverable-error path; `compensating` is unused because the cancel flow flips straight to `compensated`. Both are candidates for trimming under the §2.9 Slice B follow-up "only model states the code actually writes" rule.
