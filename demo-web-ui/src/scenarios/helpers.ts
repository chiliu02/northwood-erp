// Shared step-builder helpers — used by every scenario.

import { cancelSalesOrder, placeSalesOrder, recordCustomerPayment } from "@/api/commands";
import {
  fetchCustomerInvoices,
  fetchJournalEntries,
  fetchPurchaseOrderDetail,
  fetchSalesOrder360,
  fetchSalesOrderDetail,
  fetchSupplierInvoices,
  fetchWorkOrderDetail,
} from "@/api/fetchers";
import type { CustomerInvoiceView } from "@/api/types";
import type { SagaRow } from "@/sagas/stream";
import type { ScenarioContext, ScenarioStep } from "./types";

const SEED = {
  CUSTOMER_CODE: "CUST-001",                                    // Sydney Home Living
  PRODUCT_ID:    "00000000-0000-7000-8000-000000000001",        // FG-TABLE-001
  PRODUCT_SKU:   "FG-TABLE-001",
  PRODUCT_NAME:  "Wooden Dining Table",
} as const;

/** Place a sales order with the seed customer + product. Stamps salesOrderHeaderId. */
export function placeOrderStep(orderedQuantity: string): ScenarioStep {
  return {
    id: "place-order",
    title: `Place sales order for ${orderedQuantity} × FG-TABLE-001`,
    hint: "POST /api/sales-cmd/sales-orders → sales-service",
    kind: "auto",
    run: async (ctx) => {
      const orderNumber = `SO-${Date.now()}`;
      const result = await placeSalesOrder({
        orderNumber,
        customerCode: ctx.customerCode ?? SEED.CUSTOMER_CODE,
        currencyCode: "AUD",
        lines: [{
          productId:   ctx.productId   ?? SEED.PRODUCT_ID,
          productSku:  ctx.productSku  ?? SEED.PRODUCT_SKU,
          productName: ctx.productName ?? SEED.PRODUCT_NAME,
          orderedQuantity,
          unitPrice: ctx.unitPrice ?? "320",
        }],
      });
      return {
        salesOrderHeaderId: result.id,
        orderedQuantity,
      };
    },
  };
}

/** §2.34: place a deposit order (paymentTerms=deposit) with the seed customer + product. */
export function placeDepositOrderStep(orderedQuantity: string, depositPercent: string): ScenarioStep {
  return {
    id: "place-deposit-order",
    title: `Place a ${depositPercent}% deposit order for ${orderedQuantity} × FG-TABLE-001`,
    hint: "POST /api/sales-cmd/sales-orders (paymentTerms=deposit) → sales-service",
    kind: "auto",
    run: async (ctx) => {
      const result = await placeSalesOrder({
        orderNumber: `SO-DEP-${Date.now()}`,
        customerCode: ctx.customerCode ?? SEED.CUSTOMER_CODE,
        currencyCode: "AUD",
        paymentTerms: "deposit",
        depositPercent,
        lines: [{
          productId:   ctx.productId   ?? SEED.PRODUCT_ID,
          productSku:  ctx.productSku  ?? SEED.PRODUCT_SKU,
          productName: ctx.productName ?? SEED.PRODUCT_NAME,
          orderedQuantity,
          unitPrice: ctx.unitPrice ?? "320",
        }],
      });
      return { salesOrderHeaderId: result.id, orderedQuantity, depositPercent };
    },
  };
}

/**
 * §2.34: find the order's up-front (deposit/prepayment) invoice — the only one
 * created before shipment — and pay it in full. Stamps customerInvoiceHeaderId
 * so the refund-verify step can find the refund journal by source-document id.
 */
export function payUpfrontInvoiceStep(): ScenarioStep {
  return {
    id: "pay-upfront-invoice",
    title: "Pay the deposit invoice in full",
    hint: "poll for the deposit invoice, then POST /api/payments/customer → finance",
    kind: "auto",
    run: async (ctx, signal) => {
      if (!ctx.salesOrderHeaderId) throw new Error("no salesOrderHeaderId in context");
      let invoice: CustomerInvoiceView | undefined;
      await pollUntil(signal, 60_000, async () => {
        const invoices = await fetchCustomerInvoices().catch(() => []);
        invoice = invoices.find((i) => i.salesOrderHeaderId === ctx.salesOrderHeaderId);
        return invoice != null;
      });
      await recordCustomerPayment({
        paymentNumber: `PMT-DEP-${Date.now()}`,
        customerInvoiceHeaderId: invoice!.id,
        amount: invoice!.totalAmount,
        paymentMethod: "bank_transfer",
      });
      return { customerInvoiceHeaderId: invoice!.id };
    },
  };
}

/** §1G.3 / §2.34: cancel the order (must be pre-shipment). */
export function cancelOrderStep(reason: string): ScenarioStep {
  return {
    id: "cancel-order",
    title: "Cancel the order",
    hint: "POST /api/sales-cmd/sales-orders/{id}/cancel → sales-service",
    kind: "auto",
    run: async (ctx) => {
      if (!ctx.salesOrderHeaderId) throw new Error("no salesOrderHeaderId in context");
      await cancelSalesOrder(ctx.salesOrderHeaderId, { reason });
    },
  };
}

/** Poll the sales-fulfilment saga (sales-service) until the SO's saga is in one of the expected states. */
export function waitForSalesSaga(expectedStates: string[], opts?: { stepId?: string; title?: string }): ScenarioStep {
  return {
    id: opts?.stepId ?? `wait-sales-saga-${expectedStates[0]}`,
    title: opts?.title ?? `Wait until sales saga reaches ${expectedStates.join(" / ")}`,
    hint: "polling /api/sagas/sales every 2s",
    kind: "auto",
    run: async (ctx, signal) => {
      if (!ctx.salesOrderHeaderId) throw new Error("no salesOrderHeaderId in context");
      await pollUntil(signal, 60_000, async () => {
        const rows = await fetchSagaRows("sales");
        const saga = rows.find((r) => r.domainKey === ctx.salesOrderHeaderId);
        return saga != null && expectedStates.includes(saga.state);
      });
    },
  };
}

/**
 * Wait for the work-order saga(s) for this SO to reach an expected state.
 * If `captureWorkOrderIds=true`, stamps the discovered WO ids onto the context.
 */
export function waitForWorkOrderSaga(expectedStates: string[], opts?: {
  stepId?: string; title?: string; captureWorkOrderIds?: boolean;
}): ScenarioStep {
  return {
    id: opts?.stepId ?? `wait-mto-saga-${expectedStates[0]}`,
    title: opts?.title ?? `Wait until work-order saga reaches ${expectedStates.join(" / ")}`,
    hint: "polling /api/sagas/manufacturing every 2s",
    kind: "auto",
    run: async (ctx, signal) => {
      if (!ctx.salesOrderHeaderId) throw new Error("no salesOrderHeaderId in context");
      // Post-§2.37 the make-to-stock work order is created by inventory's
      // replenishment (no ManufacturingDispatched / SO→WO binding). The
      // work-order saga's domainKey is the work_order_id, not the SO, so we
      // can't match on the SO directly — poll the manufacturing work-order
      // sagas and accept any whose state is in `expectedStates` and whose
      // updatedAt is at/after the order date (a loose recency check).
      let workOrderIds: string[] = [];
      await pollUntil(signal, 60_000, async () => {
        const rows = await fetchSagaRows("manufacturing");
        const so = await fetchSalesOrder360(ctx.salesOrderHeaderId!).catch(() => null);
        if (!so) return false;
        const matched = rows.filter((r) =>
          expectedStates.includes(r.state) &&
          (!so.lastEventAt || (r.updatedAt ?? "") >= (so.orderDate ?? "1970-01-01"))
        );
        if (matched.length === 0) return false;
        workOrderIds = matched.map((r) => r.domainKey);
        return true;
      });
      if (opts?.captureWorkOrderIds) {
        return { workOrderIds };
      }
    },
  };
}

/** Wait for the purchase-to-pay saga to surface (it's created when a PR converts to a PO). */
export function waitForP2PSaga(expectedStates: string[], opts?: {
  stepId?: string; title?: string; capturePurchaseOrderId?: boolean;
}): ScenarioStep {
  return {
    id: opts?.stepId ?? `wait-p2p-saga-${expectedStates[0]}`,
    title: opts?.title ?? `Wait until P2P saga reaches ${expectedStates.join(" / ")}`,
    hint: "polling /api/sagas/purchasing every 2s",
    kind: "auto",
    run: async (_ctx, signal) => {
      let poId: string | undefined;
      await pollUntil(signal, 90_000, async () => {
        const rows = await fetchSagaRows("purchasing");
        const matched = rows.filter((r) => expectedStates.includes(r.state));
        if (matched.length === 0) return false;
        // Pick the most recently updated saga.
        const best = matched.sort((a, b) => (b.updatedAt ?? "").localeCompare(a.updatedAt ?? ""))[0];
        poId = best.domainKey;
        return true;
      });
      if (opts?.capturePurchaseOrderId && poId) {
        return { purchaseOrderHeaderId: poId };
      }
    },
  };
}

/** Pause for the user to perform an action manually, then click "Run". */
export function humanStep(
  id: string,
  title: string,
  hint: string,
  verify?: (ctx: ScenarioContext, signal: AbortSignal) => Promise<boolean>
): ScenarioStep {
  return {
    id, title, hint,
    kind: "human-pause",
    run: async () => {
      // No-op — the runner advances when the user clicks "Run", which is just
      // confirmation. The actual side-effect happens in whatever page the
      // narrator opened (linked from the hint).
    },
    verify,
  };
}

// -------- Verify predicates --------

/** All operations on every captured work order are status='completed' (or 'skipped'). */
export const allWorkOrderOpsCompleted = async (ctx: ScenarioContext): Promise<boolean> => {
  if (!ctx.workOrderIds || ctx.workOrderIds.length === 0) return false;
  for (const woId of ctx.workOrderIds) {
    const wo = await fetchWorkOrderDetail(woId).catch(() => null);
    if (!wo) return false;
    const incomplete = wo.operations.some((op) => op.status !== "completed" && op.status !== "skipped");
    if (incomplete) return false;
  }
  return true;
};

/** A shipment has been posted for {@code ctx.salesOrderHeaderId}. */
export const shipmentPostedForSalesOrder = async (ctx: ScenarioContext): Promise<boolean> => {
  if (!ctx.salesOrderHeaderId) return false;
  const so = await fetchSalesOrderDetail(ctx.salesOrderHeaderId).catch(() => null);
  if (!so) return false;
  // SO header flips to 'shipped' once shipment posts (sales-saga ShipmentPostedHandler).
  return so.status === "shipped" || so.status === "invoiced" || so.status === "completed";
};

/** A goods receipt has been posted against {@code ctx.purchaseOrderHeaderId}. */
export const goodsReceiptPostedForPo = async (ctx: ScenarioContext): Promise<boolean> => {
  if (!ctx.purchaseOrderHeaderId) return false;
  const po = await fetchPurchaseOrderDetail(ctx.purchaseOrderHeaderId).catch(() => null);
  if (!po) return false;
  // PO header status flips to 'partial' / 'received' once any receipt posts.
  return po.status === "partial" || po.status === "received" || po.status === "invoiced";
};

/** A supplier invoice has been recorded against {@code ctx.purchaseOrderHeaderId}. */
export const supplierInvoiceRecordedForPo = async (ctx: ScenarioContext): Promise<boolean> => {
  if (!ctx.purchaseOrderHeaderId) return false;
  const invoices = await fetchSupplierInvoices().catch(() => []);
  return invoices.some((inv) => inv.purchaseOrderHeaderId === ctx.purchaseOrderHeaderId);
};

/** A supplier payment has been recorded for the PO's invoice (status flips to 'paid'). */
export const supplierPaymentRecordedForPo = async (ctx: ScenarioContext): Promise<boolean> => {
  if (!ctx.purchaseOrderHeaderId) return false;
  const invoices = await fetchSupplierInvoices().catch(() => []);
  const inv = invoices.find((i) => i.purchaseOrderHeaderId === ctx.purchaseOrderHeaderId);
  return inv != null && inv.status === "paid";
};

/** A customer payment has been recorded against the customer invoice for this SO. */
export const customerPaymentRecordedForSo = async (ctx: ScenarioContext): Promise<boolean> => {
  if (!ctx.salesOrderHeaderId) return false;
  const invoices = await fetchCustomerInvoices().catch(() => []);
  const inv = invoices.find((i) => i.salesOrderHeaderId === ctx.salesOrderHeaderId);
  return inv != null && inv.status === "paid";
};

/** §2.34: a customer_refund journal (Dr 2110 / Cr Bank) has posted for the order's up-front invoice. */
export const refundPostedForInvoice = async (ctx: ScenarioContext): Promise<boolean> => {
  if (!ctx.customerInvoiceHeaderId) return false;
  const journals = await fetchJournalEntries("customer_refund").catch(() => []);
  return journals.some((j) => j.sourceDocumentId === ctx.customerInvoiceHeaderId);
};

// -------- Internals --------

const SERVICE_TO_SAGA_TYPE: Record<"sales" | "manufacturing" | "purchasing", string> = {
  sales:         "sales_order_fulfilment",
  manufacturing: "work_order",
  purchasing:    "purchase_to_pay",
};

async function fetchSagaRows(service: "sales" | "manufacturing" | "purchasing"): Promise<SagaRow[]> {
  // Single aggregated endpoint via the BFF; filter client-side by sagaType.
  const res = await fetch("/api/sagas");
  if (!res.ok) throw new Error(`/api/sagas → ${res.status}`);
  const all: SagaRow[] = await res.json();
  return all.filter((r) => r.sagaType === SERVICE_TO_SAGA_TYPE[service]);
}

async function pollUntil(
  signal: AbortSignal,
  timeoutMs: number,
  predicate: () => Promise<boolean>
): Promise<void> {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    if (signal.aborted) throw new DOMException("Scenario aborted", "AbortError");
    if (await predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error(`Timed out waiting (${timeoutMs / 1000}s)`);
}
