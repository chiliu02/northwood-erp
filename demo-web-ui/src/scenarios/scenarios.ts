// Three baked scenarios mirroring demo-script.md.
//
// Auto steps fire API calls or poll sagas. Human-pause steps highlight a
// page the narrator should open and prompt them to click "Run" once done —
// the runner then resumes auto-progression. When a human-pause step has a
// `verify` predicate, "Run" enters a 60s polling loop that watches some
// observable state (operation completion, shipment posted, payment recorded)
// until satisfied. This catches "user clicked Run before actually doing the
// manual action" cleanly — the runner stays on the step instead of timing
// out 60s later in the next saga-wait step.

import type { Scenario } from "./types";
import {
  allWorkOrderOpsCompleted,
  customerPaymentRecordedForSo,
  goodsReceiptPostedForPo,
  humanStep,
  placeOrderStep,
  shipmentPostedForSalesOrder,
  supplierInvoiceRecordedForPo,
  supplierPaymentRecordedForPo,
  waitForWorkOrderSaga,
  waitForP2PSaga,
  waitForSalesSaga,
} from "./helpers";

const SCENARIO_3_1: Scenario = {
  id: "3.1",
  title: "3.1 — Sales fulfilment (happy path)",
  description: "Place a small order, watch all three sagas drive it to completion.",
  steps: [
    placeOrderStep("1"),
    waitForSalesSaga(["manufacturing_requested", "manufacturing_in_progress"], {
      stepId: "wait-mfg-requested",
      title: "Wait for sales saga → manufacturing_requested",
    }),
    waitForWorkOrderSaga(["raw_materials_reserved", "production_started", "production_completed"], {
      stepId: "wait-rm-reserved",
      title: "Wait for work-order saga → raw_materials_reserved",
      captureWorkOrderIds: true,
    }),
    humanStep(
      "complete-ops",
      "Complete operations on the work order",
      "Open /production-board → click the released WO → Complete operation. Repeat per op.",
      allWorkOrderOpsCompleted
    ),
    waitForSalesSaga(["ready_to_ship"], {
      stepId: "wait-ready-to-ship",
      title: "Wait for sales saga → ready_to_ship",
    }),
    humanStep(
      "post-shipment",
      "Post the shipment",
      "Open /shipments → fill SO id (already in URL clipboard from step 1) → Post.",
      shipmentPostedForSalesOrder
    ),
    waitForSalesSaga(["invoice_created", "invoice_partially_paid", "completed"], {
      stepId: "wait-invoice",
      title: "Wait for sales saga → invoice_created (auto via finance)",
    }),
    humanStep(
      "record-payment",
      "Record the customer payment",
      "Open /payments → Customer (AR) tab → fill the customer-invoice id → Record.",
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
  title: "5.2 — Raw material shortage triggers PR + PO",
  description: "Make-to-order parks at raw_material_shortage; purchasing auto-issues a PR; receipt un-parks.",
  initialContext: {
    orderedQuantity: "10",     // forces shortage given seed quantities
  },
  steps: [
    placeOrderStep("10"),
    waitForSalesSaga(["manufacturing_requested"], {
      stepId: "wait-mfg-req",
      title: "Wait for sales saga → manufacturing_requested",
    }),
    waitForWorkOrderSaga(["raw_material_shortage"], {
      stepId: "wait-shortage",
      title: "Wait for work-order saga → raw_material_shortage",
      captureWorkOrderIds: true,
    }),
    waitForP2PSaga(["waiting_for_goods", "started"], {
      stepId: "wait-p2p-waiting",
      title: "Wait for P2P saga → waiting_for_goods (PR auto-converted to PO)",
      capturePurchaseOrderId: true,
    }),
    humanStep(
      "post-receipt",
      "Post a goods receipt for the auto-created PO",
      "Open /goods-receipts → fill the PO id (captured by previous step) and required qty → Post.",
      goodsReceiptPostedForPo
    ),
    waitForWorkOrderSaga(["raw_materials_reserved", "production_started", "completed"], {
      stepId: "wait-mto-recovered",
      title: "Wait for work-order saga to un-park and reach raw_materials_reserved",
    }),
    humanStep(
      "scenario-end",
      "Scenario 5.2 ends here",
      "From here you can either complete the order yourself or run scenario 7.1 to drive the whole thing."
    ),
  ],
};

const SCENARIO_7_1: Scenario = {
  id: "7.1",
  title: "7.1 — Big order touches every service",
  description: "Order quantity forces shortage; PR/PO; receipt; production; shipment; invoice; payments.",
  steps: [
    ...SCENARIO_5_2.steps.slice(0, -1),    // reuse 5.2 up to but not including the "scenario ends" pause
    humanStep(
      "complete-ops-each-wo",
      "Complete operations on every released WO",
      "Open /production-board → Complete each op on each WO until all reach completed.",
      allWorkOrderOpsCompleted
    ),
    waitForSalesSaga(["ready_to_ship"], {
      stepId: "wait-ready-to-ship",
      title: "Wait for sales saga → ready_to_ship",
    }),
    humanStep(
      "post-shipment",
      "Post the shipment",
      "Open /shipments → fill SO id → Post for the full ordered quantity.",
      shipmentPostedForSalesOrder
    ),
    waitForSalesSaga(["invoice_created", "invoice_partially_paid", "completed"], {
      stepId: "wait-invoice",
      title: "Wait for sales saga → invoice_created",
    }),
    humanStep(
      "record-supplier-invoice",
      "Record the supplier invoice for the PR/PO",
      "Open /supplier-invoices → fill PO id, supplier id, lines → Record.",
      supplierInvoiceRecordedForPo
    ),
    waitForP2PSaga(["supplier_invoice_approved"], {
      stepId: "wait-p2p-approved",
      title: "Wait for P2P saga → supplier_invoice_approved",
    }),
    humanStep(
      "pay-supplier",
      "Pay the supplier",
      "Open /payments → Supplier (AP) tab → fill supplier-invoice id → Record.",
      supplierPaymentRecordedForPo
    ),
    waitForP2PSaga(["completed"], {
      stepId: "wait-p2p-completed",
      title: "Wait for P2P saga → completed",
    }),
    humanStep(
      "receive-customer-payment",
      "Record the customer payment",
      "Open /payments → Customer (AR) tab → fill customer-invoice id → Record.",
      customerPaymentRecordedForSo
    ),
    waitForSalesSaga(["completed"], {
      stepId: "wait-sales-completed",
      title: "Wait for sales saga → completed",
    }),
  ],
};

export const SCENARIOS: Scenario[] = [SCENARIO_3_1, SCENARIO_5_2, SCENARIO_7_1];
