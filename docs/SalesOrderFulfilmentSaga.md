# SalesOrderFulfilmentSaga — state transitions

State-transition diagram for `com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga`. Edge labels are `<class>.<method>, <current_step>` — the writer that drives the transition followed by the `current_step` value stamped on the saga row.

Post §2.9 Slice A (2026-05-09): every transition the saga can take lives on `SalesOrderFulfilmentSagaManager` (interface in `sales.application.saga`, JDBC impl in `sales.infrastructure.saga.JdbcSalesOrderFulfilmentSagaManager`). The worker class + 9 inbox handlers are thin shells that delegate here. Method names below match the manager's interface verbatim — grep the file to see the body of any transition.

## Happy path

```
                         SalesOrderService.placeOrder
                         → SalesOrderFulfilmentSagaManager.insertStarted
                                     ↓
                                 [started]
                                     ↓
              SalesOrderFulfilmentSagaManager.advance (worker drain),
              wait_for_stock_reserved
                                     ↓
                        [stock_reservation_requested]
                                     ↓
              SalesOrderFulfilmentSagaManager.applyStockReserved,
              wait_for_next_step
                                     ↓
                             [stock_reserved]
                                     ↓
              SalesOrderFulfilmentSagaManager.advance (worker drain),
              wait_for_work_order_created
                                     ↓
                        [manufacturing_requested]
                                     ↓
              SalesOrderFulfilmentSagaManager.applyWorkOrderCreated,
              wait_for_work_order_completion
                                     ↓
                       [manufacturing_in_progress]
                                     ↓
              SalesOrderFulfilmentSagaManager.applyWorkOrderManufacturingCompleted,
              wait_for_shipment
              (only when allWorkOrdersComplete(); partial completions stay)
                                     ↓
                             [ready_to_ship]
                                     ↓
              SalesOrderFulfilmentSagaManager.applyShipmentPosted,
              wait_for_invoice
                                     ↓
                             [goods_shipped]
                                     ↓
              SalesOrderFulfilmentSagaManager.applyCustomerInvoiceCreated,
              wait_for_payment
                                     ↓
                            [invoice_created]
                                     ↓
              SalesOrderFulfilmentSagaManager.applyCustomerPaymentReceived
              (fullySettled), o2c_completed
                                     ↓
                               [completed]   ← terminal
```

The "advance (worker drain)" entries above are the inherited `drain()` from `shared.infrastructure.saga.SagaManager<S>` calling the manager's `advance(SalesOrderFulfilmentSaga)` switch — the same class owns both worker-driven and inbox-driven transitions.

## Side rail 1 — partial payment

```
                            [invoice_created]
                                     ↓
              SalesOrderFulfilmentSagaManager.applyCustomerPaymentReceived
              (!fullySettled), wait_for_remaining_payments
                                     ↓
                             [invoice_paid]
                                     ↓
              SalesOrderFulfilmentSagaManager.applyCustomerPaymentReceived
              (fullySettled, next payment), o2c_completed
                                     ↓
                               [completed]
```

Manager accepts both `invoice_created` and `invoice_paid` as receiving states for `applyCustomerPaymentReceived`, so subsequent payments for the same invoice loop back through it.

## Side rail 2 — full stock cover shortcut

```
                        [stock_reservation_requested]
                                     ↓
              SalesOrderFulfilmentSagaManager.applyStockReserved
              (reservationStatus == reserved), wait_for_shipment
                                     ↓
                             [ready_to_ship]
```

Fires when inventory fully covers every line of the order from on-hand stock. Skips the entire manufacturing leg (`stock_reserved → manufacturing_requested → manufacturing_in_progress → ready_to_ship`) and lands directly at `ready_to_ship` to wait for `ShipmentPosted`. Not exercised by the MTO demo dataset (finished_goods carry zero on-hand), but the saga is correct against any scenario that plants enough stock to cover an order.

## Side rail 3 — no manufacturable lines

```
                        [manufacturing_requested]
                                     ↓
              SalesOrderFulfilmentSagaManager.applyManufacturingDispatched
              (zero accepted lines), no_manufacturable_lines
                                     ↓
                       [stock_reservation_failed]
```

Fires when manufacturing dispatched the request but none of the lines had an active BOM. No handler currently advances out of `stock_reservation_failed` — captured as a follow-up on `StockReservedHandler` Javadoc.

## Side rail 4 — compensation (REST cancel)

```
                   [started | stock_reservation_requested |
                    stock_reserved | manufacturing_requested |
                    manufacturing_in_progress | ready_to_ship]
                                     ↓
              SalesOrderService.cancel
              → SalesOrderFulfilmentSagaManager.requestCompensation,
              wait_for_compensation_acks
              (rejected with 409 once past goods_shipped)
                                     ↓
                            [compensating]
                                     ↓
              SalesOrderFulfilmentSagaManager.applyInventoryCancellationApplied
                  (sets inventoryCancellationAcked)
              SalesOrderFulfilmentSagaManager.applyManufacturingCancellationApplied
                  (sets manufacturingCancellationAcked)
              SalesOrderFulfilmentSagaManager.completeCompensationIfReady (private)
              when both acks received, cancelled
                                     ↓
                            [compensated]   ← terminal
```

Either ack handler may arrive first; whichever fires the second triggers `completeCompensationIfReady` (a private method on the manager) which performs the actual `transitionTo("compensated", …)` and emits `sales.SalesOrderCompensated`. Folded from the former standalone `SagaCompensationCompletionService` in the §2.9 Slice A refactor.

## States declared but never written

`manufacturing_completed` and `invoice_requested` appear in `ALL_STATES` but no code path emits them today. The startup invariant checker reads `ALL_STATES` as a contract against the schema CHECK, so leaving them in is harmless — but per the CLAUDE.md guidance ("only model states the code actually writes"), they're candidates for removal in the §2.7 constants slice.

The third terminal — `failed` — is in the schema CHECK and `TERMINAL_STATES` but also has no writer; reserved for an eventual unrecoverable-error path.
