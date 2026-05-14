// Mirrors reporting-service's FinancialDashboardResponse record (DTOs will be
// codegen'd from /v3/api-docs in a later phase; for Phase 1 the hand-written
// shape is fine).

export interface FinancialDashboardRow {
  dashboardDate: string;       // ISO date
  currencyCode: string;
  salesRevenue: string;        // BigDecimal serialised as string
  costOfGoodsSold: string;
  grossProfit: string;
  inventoryValue: string;
  wipValue: string;
  accountsReceivable: string;
  accountsPayable: string;
  cashReceived: string;
  cashPaid: string;
  openSalesOrdersCount: number;
  openPurchaseOrdersCount: number;
  openWorkOrdersCount: number;
  updatedAt: string;           // ISO instant
}

export async function fetchFinancialDashboard(
  currency = "AUD"
): Promise<FinancialDashboardRow[]> {
  const res = await fetch(`/api/financial-dashboard?currency=${encodeURIComponent(currency)}`);
  if (!res.ok) {
    throw new Error(`financial-dashboard fetch failed: ${res.status} ${res.statusText}`);
  }
  return res.json();
}

// As-of-now totals (AR, AP, inventory_value, currently-open counts) —
// SUM-window over reporting's local projections. Distinct shape from the
// per-day delta rows above; both are needed to drive the Dashboard cleanly.
export interface FinancialDashboardSnapshot {
  currencyCode: string;
  accountsReceivable: string;          // BigDecimal serialised as string
  accountsPayable: string;
  inventoryValue: string;
  openSalesOrdersCount: number;
  openPurchaseOrdersCount: number;
  openWorkOrdersCount: number;
  asOf: string;                        // ISO instant
}

export async function fetchFinancialDashboardSnapshot(
  currency = "AUD"
): Promise<FinancialDashboardSnapshot> {
  const res = await fetch(`/api/financial-dashboard/snapshot?currency=${encodeURIComponent(currency)}`);
  if (!res.ok) {
    throw new Error(`financial-dashboard snapshot fetch failed: ${res.status} ${res.statusText}`);
  }
  return res.json();
}
