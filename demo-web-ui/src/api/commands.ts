// Command-side API wrappers. Path prefixes mirror the Vite dev proxy in
// vite.config.ts (the SPA never knows the per-service ports).
//
// All endpoints expect a JSON body matching the *Request records on the Java
// side (see ./types-commands.ts for the shapes).

import type {
  PlaceOrderRequest,
  AdjustStockRequest,
  PostGoodsReceiptRequest,
  PostShipmentRequest,
  RecordSupplierInvoiceRequest,
  RecordSupplierPaymentRequest,
  RecordCustomerPaymentRequest,
  CompleteOperationRequest,
  ChangeSalesPriceRequest,
  ChangeStandardCostRequest,
  SetReorderPolicyRequest,
  ChangeMakeVsBuyRequest,
  CreateProductRequest,
  CancelSalesOrderRequest,
  ApprovePurchaseOrderRequest,
} from "./types-commands";

async function postJson<T = unknown>(url: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${url} → ${res.status} ${res.statusText}${text ? ": " + text : ""}`);
  }
  if (res.status === 204) return {} as T;
  // Some endpoints return 201 Created with no body (e.g. setSupplierProductPrice).
  const ct = res.headers.get("content-type") ?? "";
  if (!ct.includes("application/json")) return {} as T;
  return res.json();
}

async function putJson<T = unknown>(url: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${url} → ${res.status} ${res.statusText}${text ? ": " + text : ""}`);
  }
  if (res.status === 204) return {} as T;
  return res.json();
}

// Sales — sales-service via /api/sales-orders proxy maps to reporting; need a
// dedicated path for the place-order command. Adding a `/api/sales-orders/place`
// rewrite would conflict with reporting's GET; route writes to the owning
// service via /api/sales-cmd path that the proxy maps.
//
// All command endpoints return their respective *View record from the service,
// whose primary key is always `id` (not `<entity>HeaderId`). The response is
// the full View; we type only the fields callers actually read here.
export const placeSalesOrder = (req: PlaceOrderRequest) =>
  postJson<{ id: string; orderNumber: string }>(
    "/api/sales-cmd/sales-orders", req
  );

export const postGoodsReceipt = (req: PostGoodsReceiptRequest) =>
  postJson<{ id: string }>("/api/goods-receipts", req);

export const adjustStock = (req: AdjustStockRequest) =>
  postJson<{ id: string }>("/api/stock-adjustments", req);

export const postShipment = (req: PostShipmentRequest) =>
  postJson<{ id: string }>("/api/shipments", req);

export const recordSupplierInvoice = (req: RecordSupplierInvoiceRequest) =>
  postJson<{ id: string }>("/api/supplier-invoices", req);

export const recordSupplierPayment = (req: RecordSupplierPaymentRequest) =>
  postJson<{ id: string }>("/api/payments", req);

export const recordCustomerPayment = (req: RecordCustomerPaymentRequest) =>
  postJson<{ id: string }>("/api/payments/customer", req);

export const completeOperation = (workOrderId: string, sequence: number, req: CompleteOperationRequest) =>
  postJson<{}>(`/api/work-orders-cmd/${workOrderId}/operations/${sequence}/complete`, req);

export const changeProductSalesPrice = (productId: string, req: ChangeSalesPriceRequest) =>
  putJson<{}>(`/api/products/${productId}/sales-price`, req);

export const changeProductStandardCost = (productId: string, req: ChangeStandardCostRequest) =>
  putJson<{}>(`/api/products/${productId}/standard-cost`, req);

export const setProductReorderPolicy = (productId: string, req: SetReorderPolicyRequest) =>
  putJson<{}>(`/api/products/${productId}/reorder-policy`, req);

export const discontinueProduct = (productId: string) =>
  postJson<{}>(`/api/products/${productId}/discontinue`, {});

// §1G.2: make-vs-buy classification. Emits product.MakeVsBuyChanged; manufacturing's
// replenishment projection updates, gating the make-to-order entry path.
export const changeProductMakeVsBuy = (productId: string, req: ChangeMakeVsBuyRequest) =>
  putJson<{}>(`/api/products/${productId}/make-vs-buy`, req);

// §1G.1: register a new SKU. Returns the created ProductView (productId, sku, ...).
export const createProduct = (req: CreateProductRequest) =>
  postJson<{ productId: string; sku: string }>(`/api/products`, req);

// §1G.3: cancel a sales order. Server enforces NON_CANCELLABLE_STATUSES (shipped,
// completed, cancelled, rejected) with HTTP 409. Returns the (now-cancelled) order.
export const cancelSalesOrder = (salesOrderId: string, req: CancelSalesOrderRequest) =>
  postJson<{ id: string; status: string }>(`/api/sales-cmd/sales-orders/${salesOrderId}/cancel`, req);

// §1G.4: approve a draft PO. Flips header 'draft' → 'sent' and emits
// purchasing.PurchaseOrderApproved; saga walks started → purchase_order_approved.
export const approvePurchaseOrder = (purchaseOrderId: string, req: ApprovePurchaseOrderRequest) =>
  postJson<{}>(`/api/purchase-orders-cmd/${purchaseOrderId}/approve`, req);

// Olivia — supplier invoice review
export interface ManualReviewRequest { reviewer: string; reason: string; }

export const manualApproveSupplierInvoice = (id: string, req: ManualReviewRequest) =>
  postJson<{}>(`/api/supplier-invoices/${id}/manual-approve`, req);

export const rejectSupplierInvoice = (id: string, req: ManualReviewRequest) =>
  postJson<{}>(`/api/supplier-invoices/${id}/reject`, req);

// Daniel — journal reversal
export interface ReverseJournalEntryRequest { reason: string; postingDate?: string; }
export interface ReverseBySourceRequest {
  sourceDocumentType: string;
  sourceDocumentId: string;
  reason: string;
  postingDate?: string;
}
export interface ReverseBySourceResponse { reversedCount: number; reversalIds: string[]; }

export const reverseJournalEntry = (id: string, req: ReverseJournalEntryRequest) =>
  postJson<{ journalEntryHeaderId: string; journalNumber: string }>(
    `/api/journal-entries/${id}/reverse`, req
  );

export const reverseBySource = (req: ReverseBySourceRequest) =>
  postJson<ReverseBySourceResponse>(`/api/journal-entries/reverse-by-source`, req);

// Tom — supplier prices
export interface SetSupplierProductPriceRequest {
  supplierId: string;
  productId: string;
  currencyCode: string;
  unitPrice: string;
}

export const setSupplierProductPrice = (req: SetSupplierProductPriceRequest) =>
  putJson<{}>(`/api/supplier-product-prices`, req);
