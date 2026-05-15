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

## Side rail — manual rejection of a parked supplier invoice

```
                           [goods_received]
                                     ↓
              SupplierInvoiceRejectedHandler.handle
              → PurchaseToPaySagaManager.applySupplierInvoiceRejected,
              supplier_invoice_rejected
                                     ↓
                               [failed]   ← terminal
```

`SupplierInvoiceService.manualReject` flips the invoice to `cancelled` and emits `finance.SupplierInvoiceRejected`; purchasing's handler lands the saga in terminal `failed`. The auto-match-failed → parked state on the invoice itself lives at `SupplierInvoice.status='three_way_match_failed'`, not on the saga — the saga stays at `goods_received` until the reviewer decides between `manual-approve` (re-emits `SupplierInvoiceApproved`, continues the happy path) and `manual-reject` (this rail).

There is no projection writeback on rejection — the PO header stays in whatever state it's in (typically `'sent'` or `'partially_received'`). PO-cancellation is a separate concern (deferred; see *No compensation flow* below).

## State coverage

Every state in `PurchaseToPaySaga.ALL_STATES` (`started`, `purchase_order_approved`, `waiting_for_goods`, `goods_received`, `supplier_invoice_approved`, `supplier_payment_made`, `completed`, `failed`) is written by code. Per the "only model states the code actually writes" rule, no dead constants — the schema CHECK on `purchasing.purchase_to_pay_saga.saga_state` mirrors this list exactly.

There is **no compensation flow** on this saga. The cancel-order saga compensates sales + manufacturing only; purchasing isn't part of that flow. If a future cancel-PO command lands, `compensating` / `compensated` would need to be added to both `ALL_STATES` and the schema CHECK in the same slice.
