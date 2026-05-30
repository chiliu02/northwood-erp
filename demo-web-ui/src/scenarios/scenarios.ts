// Baked scenarios mirroring demo-script.md.
//
// Auto steps fire API calls or poll sagas. Human-pause steps highlight a
// page the narrator should open and prompt them to click "Run" once done —
// the runner then resumes auto-progression. When a human-pause step has a
// `verify` predicate, "Run" enters a 60s polling loop that watches some
// observable state (operation completion, shipment posted, payment recorded)
// until satisfied. This catches "user clicked Run before actually doing the
// manual action" cleanly — the runner stays on the step instead of timing
// out 60s later in the next saga-wait step.
//
// §2.37 (the flip): sales no longer drives manufacturing. A stock-covered
// order reserves in one tick and never touches a work order; a short order
// parks at `stock_reservation_incomplete` while inventory raises a make-to-
// stock replenishment (a work order, routed by inventory's make-vs-buy), and
// un-parks via `inventory.ReplenishmentFulfilled`. The old
// `manufacturing_requested` / `production_started` states are gone.

import type { Scenario } from "./types";
import {
  allWorkOrderOpsCompleted,
  cancelOrderStep,
  customerPaymentRecordedForSo,
  goodsReceiptPostedForPo,
  humanStep,
  payUpfrontInvoiceStep,
  placeDepositOrderStep,
  placeOrderStep,
  refundPostedForInvoice,
  shipmentPostedForSalesOrder,
  supplierInvoiceRecordedForPo,
  supplierPaymentRecordedForPo,
  waitForP2PSaga,
  waitForSalesSaga,
  waitForWorkOrderSaga,
} from "./helpers";

const SCENARIO_3_1: Scenario = {
  id: "3.1",
  title: "3.1 — Sales fulfilment (stock-covered happy path)",
  description: "Place a small order that's in stock; it reserves, ships, invoices, and pays off — no manufacturing.",
  steps: [
    placeOrderStep("1"),
    waitForSalesSaga(["ready_to_ship"], {
      stepId: "wait-ready-to-ship",
      title: "Wait for sales saga → ready_to_ship (stock-covered — reserves in one tick, no work order)",
    }),
    humanStep(
      "post-shipment",
      "Post the shipment",
      "Open /shipments → fill the SO id (from step 1) → Post for the full ordered quantity.",
      shipmentPostedForSalesOrder
    ),
    waitForSalesSaga(["invoice_created", "invoice_partially_paid", "completed"], {
      stepId: "wait-invoice",
      title: "Wait for sales saga → invoice_created (finance auto-creates the customer invoice)",
    }),
    humanStep(
      "record-payment",
      "Record the customer payment",
      "Open /payments → Customer (AR) tab → fill the customer-invoice id → Record the full amount.",
      customerPaymentRecordedForSo
    ),
    waitForSalesSaga(["completed"], {
      stepId: "wait-completed",
      title: "Wait for sales saga → completed",
    }),
  ],
};

const SCENARIO_5_2: Scenario = {
  id: "5.2",
  title: "5.2 — Shortage → make-to-stock WO → raw-material PR + PO",
  description: "A short order parks at stock_reservation_incomplete; inventory raises a make-to-stock WO; the WO is short on raw materials, so purchasing auto-issues a PR→PO; a receipt un-parks it.",
  initialContext: {
    orderedQuantity: "10", // forces FG shortage (sales) + raw-material shortage (work order) given seed quantities
  },
  steps: [
    placeOrderStep("10"),
    waitForSalesSaga(["stock_reservation_incomplete"], {
      stepId: "wait-incomplete",
      title: "Wait for sales saga → stock_reservation_incomplete (parked; inventory raised a make-to-stock replenishment)",
    }),
    waitForWorkOrderSaga(["raw_material_shortage"], {
      stepId: "wait-wo-shortage",
      title: "Wait for the make-to-stock work-order saga → raw_material_shortage",
      captureWorkOrderIds: true,
    }),
    waitForP2PSaga(["waiting_for_goods", "purchase_order_approved", "started"], {
      stepId: "wait-p2p-waiting",
      title: "Wait for P2P saga → waiting_for_goods (PR auto-converted to PO)",
      capturePurchaseOrderId: true,
    }),
    humanStep(
      "post-receipt",
      "Post a goods receipt for the auto-created PO",
      "Open /goods-receipts → fill the PO id (captured by the previous step) and required qty → Post.",
      goodsReceiptPostedForPo
    ),
    waitForWorkOrderSaga(["raw_materials_reserved", "completed"], {
      stepId: "wait-wo-recovered",
      title: "Wait for the work-order saga to un-park → raw_materials_reserved",
    }),
    humanStep(
      "scenario-end",
      "Scenario 5.2 ends here",
      "From here you can complete the order yourself (complete the WO ops, then ship + pay as in 3.1) or run scenario 7.1 to drive the whole thing."
    ),
  ],
};

const SCENARIO_7_1: Scenario = {
  id: "7.1",
  title: "7.1 — Big order touches every service",
  description: "Short order → make-to-stock WO → PR/PO → receipt → production → ReplenishmentFulfilled → ship → invoice → supplier + customer payments.",
  steps: [
    ...SCENARIO_5_2.steps.slice(0, -1), // reuse 5.2 up to (not including) its "scenario ends" pause
    humanStep(
      "complete-ops-each-wo",
      "Complete operations on every released WO",
      "Open /production-board → Complete each op on each WO until all reach completed. The top-level WO completing bumps FG stock and emits inventory.ReplenishmentFulfilled.",
      allWorkOrderOpsCompleted
    ),
    waitForSalesSaga(["ready_to_ship"], {
      stepId: "wait-ready-to-ship",
      title: "Wait for sales saga to retry reservation → ready_to_ship (un-parked by ReplenishmentFulfilled)",
    }),
    humanStep(
      "post-shipment",
      "Post the shipment",
      "Open /shipments → fill the SO id → Post for the full ordered quantity.",
      shipmentPostedForSalesOrder
    ),
    waitForSalesSaga(["invoice_created", "invoice_partially_paid", "completed"], {
      stepId: "wait-invoice",
      title: "Wait for sales saga → invoice_created",
    }),
    humanStep(
      "record-supplier-invoice",
      "Record the supplier invoice for the PR/PO",
      "Open /supplier-invoices → fill the PO id, supplier id, lines → Record.",
      supplierInvoiceRecordedForPo
    ),
    waitForP2PSaga(["supplier_invoice_approved"], {
      stepId: "wait-p2p-approved",
      title: "Wait for P2P saga → supplier_invoice_approved",
    }),
    humanStep(
      "pay-supplier",
      "Pay the supplier",
      "Open /payments → Supplier (AP) tab → fill the supplier-invoice id → Record.",
      supplierPaymentRecordedForPo
    ),
    waitForP2PSaga(["completed"], {
      stepId: "wait-p2p-completed",
      title: "Wait for P2P saga → completed",
    }),
    humanStep(
      "receive-customer-payment",
      "Record the customer payment",
      "Open /payments → Customer (AR) tab → fill the customer-invoice id → Record.",
      customerPaymentRecordedForSo
    ),
    waitForSalesSaga(["completed"], {
      stepId: "wait-sales-completed",
      title: "Wait for sales saga → completed",
    }),
  ],
};

const SCENARIO_REFUND: Scenario = {
  id: "4.1.1",
  title: "4.1.1 — Deposit order cancelled → automatic refund (§2.34)",
  description: "Pay a deposit, cancel before shipment, and watch finance refund it (Dr 2110 Customer Deposits / Cr Bank) — 2110 nets to zero.",
  steps: [
    placeDepositOrderStep("1", "50"),
    waitForSalesSaga(["awaiting_deposit_invoice", "deposit_invoiced"], {
      stepId: "wait-deposit-invoiced",
      title: "Wait for sales saga → deposit_invoiced (finance created the single-line deposit invoice)",
    }),
    payUpfrontInvoiceStep(),
    waitForSalesSaga(["deposit_paid"], {
      stepId: "wait-deposit-paid",
      title: "Wait for sales saga → deposit_paid (Cr 2110 Customer Deposits)",
    }),
    cancelOrderStep("customer changed mind"),
    waitForSalesSaga(["compensated"], {
      stepId: "wait-compensated",
      title: "Wait for sales saga → compensated (inventory released the reservation)",
    }),
    humanStep(
      "verify-refund",
      "Verify the refund posted",
      "Open /journal-entries → filter 'Customer refunds' → see Dr 2110 Customer Deposits / Cr 1000 Bank for the deposit. Across the deposit receipt + this refund, 2110 nets to zero. (The sales-order detail also shows a green 'refunded' lozenge.)",
      refundPostedForInvoice
    ),
  ],
};

export const SCENARIOS: Scenario[] = [SCENARIO_3_1, SCENARIO_5_2, SCENARIO_7_1, SCENARIO_REFUND];
