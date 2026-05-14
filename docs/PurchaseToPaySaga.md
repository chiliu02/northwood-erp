# PurchaseToPaySaga — state transitions

State-transition diagram for `com.northwood.purchasing.domain.saga.PurchaseToPaySaga`. Edge labels are `<class>.<method>, <current_step>`. One saga row per purchase order; the saga is inserted at `started` in the same transaction as PO creation.

Post §2.9 Slice C (2026-05-10): every transition lives on `PurchaseToPaySagaManager` (interface in `purchasing.application.saga`, JDBC impl in `purchasing.infrastructure.saga.JdbcPurchaseToPaySagaManager`). The worker shell + 3 inbox handlers + `PurchaseOrderService` are thin callers that delegate here.

## Happy path

```
                  PurchaseOrderService.createManual / convertFromRequisition
                  → PurchaseToPaySagaManager.insertStarted
                                     ↓
                                 [started]
                                     ↓
              PurchaseOrderService.convertFromRequisition (auto-approve)
                  OR PurchaseOrderService.approve (manual REST)
                  → PurchaseToPaySagaManager.approve, wait_for_worker_pickup
                                     ↓
                       [purchase_order_approved]
                                     ↓
              PurchaseToPaySagaWorker.advance (worker drain),
              wait_for_goods_receipt
                                     ↓
                          [waiting_for_goods]
                                     ↓
              GoodsReceivedHandler.handle (fully received)
              → PurchaseToPaySagaManager.applyGoodsReceived,
              wait_for_supplier_invoice
                                     ↓
                           [goods_received]
                                     ↓
              SupplierInvoiceApprovedHandler.handle
              → PurchaseToPaySagaManager.applySupplierInvoiceApproved,
              wait_for_payment
                                     ↓
                      [supplier_invoice_approved]
                                     ↓
              SupplierPaymentMadeHandler.handle (fullySettled)
              → PurchaseToPaySagaManager.applySupplierPaymentMade, p2p_completed
                                     ↓
                              [completed]   ← terminal
```

## Two entry paths into `purchase_order_approved`

```
                                 [started]
                                     ↓
                      ┌──────────────┴──────────────┐
                      ↓                             ↓
   convertFromRequisition (auto-approve)   approve (manual REST)
   shortage-driven; gated by               POST /api/purchase-orders/{id}/approve
   northwood.purchasing.shortagePoAutoApprove
                      └──────────────┬──────────────┘
                                     ↓
              PurchaseToPaySagaManager.approve, wait_for_worker_pickup
                                     ↓
                       [purchase_order_approved]
```

`PurchaseToPaySagaManager.approve` is idempotent — already-approved sagas return their current state without a re-transition. Both auto-approve and manual paths land at the same method.

## Side rail — partial supplier payment

```
                      [supplier_invoice_approved]
                                     ↓
              SupplierPaymentMadeHandler.handle (!fullySettled)
              → PurchaseToPaySagaManager.applySupplierPaymentMade,
              wait_for_remaining_payments
              (handler then projects partial payment via paymentProjection.addPartialPayment)
                                     ↓
                       [supplier_payment_made]
                                     ↓
              SupplierPaymentMadeHandler.handle (fullySettled, next payment)
              → PurchaseToPaySagaManager.applySupplierPaymentMade, p2p_completed
                                     ↓
                              [completed]
```

Manager accepts both `supplier_invoice_approved` and `supplier_payment_made` as receiving states for `applySupplierPaymentMade`, so subsequent payments for the same invoice loop back through it. Handler reads the manager's returned state to decide whether to project `markFullyPaid` (state="completed") or `addPartialPayment` (state="supplier_payment_made").

## Goods receipt — partial vs full

`GoodsReceivedHandler.handle` always records the receipt into `PurchaseOrderReceiptProjection` (which reclassifies the PO header to `partially_received` / `received`); then asks `PurchaseToPaySagaManager.applyGoodsReceived(poId, fullyReceived)` to advance the saga. The manager only transitions when `fullyReceived` is true and the saga is in `waiting_for_goods`. There's no separate `partially_received` saga state — partial-vs-full distinction lives on the receipt projection's row, not the saga state machine.

## States declared but never written

`failed` appears in `ALL_STATES` but no code path emits it today (reserved for an unrecoverable-error path). `manual_review_required` is in `TERMINAL_STATES` but not in `ALL_STATES` — meaning the schema CHECK lists it as a recognised stop-polling sink, but no Java transition currently writes it. The `three_way_match_*` family was removed from `ALL_STATES` per the "only model states the code actually writes" rule (variance handling deferred).

There is **no compensation flow** on this saga. The cancel-order saga compensates sales + manufacturing only; purchasing isn't part of that flow. If a future cancel-PO command lands, `compensating` / `compensated` would need to be added to both `ALL_STATES` and the schema CHECK in the same slice.
