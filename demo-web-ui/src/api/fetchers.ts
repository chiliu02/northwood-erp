import type {
  AvailableToPromiseRow,
  BomFlatComponent,
  BomTree,
  CustomerInvoiceView,
  GoodsReceiptView,
  JournalEntrySummary,
  JournalEntryView,
  MaterialShortageRow,
  ProductionPlanningRow,
  ProductRow,
  PurchaseOrderTracking,
  PurchaseOrderView,
  PurchaseRequisitionView,
  ReplenishmentHistoryRow,
  SalesOrder360,
  SalesOrderView,
  StockBalanceRow,
  StockItemRow,
  SupplierInvoiceView,
  SupplierPriceView,
  SupplierView,
  WorkOrderView,
} from "./types";

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (res.status === 404) {
    throw new NotFoundError(url);
  }
  if (!res.ok) {
    throw new Error(`${url} failed: ${res.status} ${res.statusText}`);
  }
  return res.json();
}

export class NotFoundError extends Error {
  constructor(url: string) { super(`404 ${url}`); }
}

// Reporting projection lists + details
export const fetchSalesOrders     = ()                : Promise<SalesOrder360[]>            => getJson("/api/sales-orders");
export const fetchSalesOrder360   = (id: string)      : Promise<SalesOrder360>              => getJson(`/api/sales-orders/${id}/360`);
// Sales-side header + lines (different shape from the reporting projection
// above). Routed via the /api/sales-cmd alias because /api/sales-orders is
// owned by reporting in the BFF route table.
export const fetchSalesOrderDetail = (id: string)     : Promise<SalesOrderView>             => getJson(`/api/sales-cmd/sales-orders/${id}`);
export const fetchPurchaseOrders  = ()                : Promise<PurchaseOrderTracking[]>    => getJson("/api/purchase-orders");
export const fetchPOTracking      = (id: string)      : Promise<PurchaseOrderTracking>      => getJson(`/api/purchase-orders/${id}/tracking`);
// Purchasing-side header + lines (different shape from the reporting tracking
// projection above). Routed via the /api/purchase-orders-cmd alias because
// /api/purchase-orders is owned by reporting in the BFF route table.
export const fetchPurchaseOrderDetail = (id: string)  : Promise<PurchaseOrderView>          => getJson(`/api/purchase-orders-cmd/${id}`);
export const fetchWorkOrders      = ()                : Promise<ProductionPlanningRow[]>    => getJson("/api/work-orders");
export const fetchWorkOrderBoard  = (id: string)      : Promise<ProductionPlanningRow>      => getJson(`/api/work-orders/${id}/board`);
// Manufacturing-side WO detail (header + operations + materials). Routed via the
// /api/work-orders-cmd alias because /api/work-orders is owned by reporting in
// the BFF route table.
export const fetchWorkOrderDetail = (id: string)      : Promise<WorkOrderView>              => getJson(`/api/work-orders-cmd/${id}`);
export const fetchAtp             = ()                : Promise<AvailableToPromiseRow[]>    => getJson("/api/atp");
export const fetchAtpForProduct   = (id: string)      : Promise<AvailableToPromiseRow>      => getJson(`/api/atp/${id}`);
export const fetchMaterialShortages = (includeResolved = false): Promise<MaterialShortageRow[]> =>
  getJson(`/api/material-shortages?includeResolved=${includeResolved}`);
export const fetchReplenishmentHistory = (limit = 50): Promise<ReplenishmentHistoryRow[]> =>
  getJson(`/api/replenishment-history?limit=${limit}`);
export const fetchReplenishmentHistoryForProduct = (productId: string, limit = 20): Promise<ReplenishmentHistoryRow[]> =>
  getJson(`/api/replenishment-history?productId=${productId}&limit=${limit}`);

// Owning-service catalogs
export const fetchProducts   = (): Promise<ProductRow[]>   => getJson("/api/products");
export const fetchStockItems = (): Promise<StockItemRow[]> => getJson("/api/stock-items");
export const fetchStockBalance = (productId: string, warehouseCode: string): Promise<StockBalanceRow> =>
  getJson(`/api/stock-adjustments/balance?productId=${productId}&warehouseCode=${encodeURIComponent(warehouseCode)}`);
export const fetchSuppliers  = (): Promise<SupplierView[]> => getJson("/api/suppliers");
export const fetchBomTree    = (productId: string): Promise<BomTree> =>
  getJson(`/api/boms/by-product/${productId}`);
export const fetchBomFlat    = (productId: string): Promise<BomFlatComponent[]> =>
  getJson(`/api/boms/by-product/${productId}/flat`);

// Inventory — goods receipts
export const fetchGoodsReceipts = (): Promise<GoodsReceiptView[]> => getJson("/api/goods-receipts");
export const fetchGoodsReceipt  = (id: string): Promise<GoodsReceiptView> => getJson(`/api/goods-receipts/${id}`);

// Finance reads
export const fetchCustomerInvoices = (): Promise<CustomerInvoiceView[]> =>
  getJson("/api/customer-invoices");
export const fetchCustomerInvoice = (id: string): Promise<CustomerInvoiceView> =>
  getJson(`/api/customer-invoices/${id}`);
export const fetchPendingReview = (): Promise<SupplierInvoiceView[]> =>
  getJson("/api/supplier-invoices/pending-review");
export const fetchSupplierInvoices = (): Promise<SupplierInvoiceView[]> =>
  getJson("/api/supplier-invoices");
export const fetchSupplierInvoice = (id: string): Promise<SupplierInvoiceView> =>
  getJson(`/api/supplier-invoices/${id}`);
export const fetchJournalEntries = (sourceDocumentType?: string): Promise<JournalEntrySummary[]> => {
  const q = sourceDocumentType ? `?sourceDocumentType=${encodeURIComponent(sourceDocumentType)}` : "";
  return getJson(`/api/journal-entries${q}`);
};
export const fetchJournalEntry = (id: string): Promise<JournalEntryView> =>
  getJson(`/api/journal-entries/${id}`);

// Purchasing reads
export const fetchPurchaseRequisitions = (): Promise<PurchaseRequisitionView[]> =>
  getJson("/api/purchase-requisitions");
export const fetchSupplierPrices = (supplierId: string): Promise<SupplierPriceView[]> =>
  getJson(`/api/supplier-product-prices/by-supplier/${supplierId}`);
