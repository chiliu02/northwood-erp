import { useQuery } from "@tanstack/react-query";
import {
  TrendingUp,
  TrendingDown,
  ListChecks,
  Wallet,
  Receipt,
  Package,
} from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { DataGrid, type Column } from "@/components/ui/DataGrid";

interface FinancialDashboardRow {
  dashboardDate: string;
  currencyCode: string;
  salesRevenue: string;
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
  updatedAt: string;
}

interface FinancialDashboardSnapshot {
  currencyCode: string;
  accountsReceivable: string;
  accountsPayable: string;
  inventoryValue: string;
  wipValue: string;
  openSalesOrdersCount: number;
  openPurchaseOrdersCount: number;
  openWorkOrdersCount: number;
  asOf: string;
}

const CURRENCY = "AUD";

export function FinancialDashboard() {
  const { data: rows, isLoading, error } = useQuery({
    queryKey: ["financial-dashboard", CURRENCY],
    queryFn: () => apiGet<FinancialDashboardRow[]>(`/api/financial-dashboard?currency=${CURRENCY}`),
    refetchInterval: 5_000,
  });

  const { data: snapshot } = useQuery({
    queryKey: ["financial-dashboard-snapshot", CURRENCY],
    queryFn: () => apiGet<FinancialDashboardSnapshot>(`/api/financial-dashboard/snapshot?currency=${CURRENCY}`),
    refetchInterval: 5_000,
  });

  const today = rows?.[0];

  const dailyColumns: Column<FinancialDashboardRow>[] = [
    { key: "date", header: "Date", width: "110px", render: (r) => <span className="tabular-nums">{r.dashboardDate}</span> },
    {
      key: "rev",
      header: "Sales revenue",
      numeric: true,
      width: "130px",
      render: (r) => <span className="tabular-nums">{formatMoney(r.salesRevenue, r.currencyCode)}</span>,
    },
    {
      key: "cogs",
      header: "COGS",
      numeric: true,
      width: "130px",
      render: (r) => <span className="tabular-nums text-text-muted">{formatMoney(r.costOfGoodsSold, r.currencyCode)}</span>,
    },
    {
      key: "gp",
      header: "Gross profit",
      numeric: true,
      width: "130px",
      render: (r) => <span className="tabular-nums font-medium">{formatMoney(r.grossProfit, r.currencyCode)}</span>,
    },
    {
      key: "cashIn",
      header: "Cash received",
      numeric: true,
      width: "130px",
      render: (r) => <span className="tabular-nums">{formatMoney(r.cashReceived, r.currencyCode)}</span>,
    },
    {
      key: "cashOut",
      header: "Cash paid",
      numeric: true,
      width: "130px",
      render: (r) => <span className="tabular-nums">{formatMoney(r.cashPaid, r.currencyCode)}</span>,
    },
    {
      key: "ar",
      header: "AR @ EOD",
      numeric: true,
      width: "130px",
      render: (r) => <span className="tabular-nums text-text-muted">{formatMoney(r.accountsReceivable, r.currencyCode)}</span>,
    },
    {
      key: "ap",
      header: "AP @ EOD",
      numeric: true,
      width: "130px",
      render: (r) => <span className="tabular-nums text-text-muted">{formatMoney(r.accountsPayable, r.currencyCode)}</span>,
    },
    {
      key: "inv",
      header: "Inventory @ EOD",
      numeric: true,
      width: "150px",
      render: (r) => <span className="tabular-nums text-text-muted">{formatMoney(r.inventoryValue, r.currencyCode)}</span>,
    },
  ];

  return (
    <>
      <PageHeader
        title="Financial Dashboard"
        description={
          today
            ? `Today: ${today.currencyCode} · ${today.dashboardDate}. Refreshes every 5 seconds.`
            : "Awaiting first event for the financial-dashboard projection."
        }
        trail={[
          { label: "Home", to: "/" },
          { label: "Reporting" },
          { label: "Financial Dashboard" },
        ]}
      />

      <div className="space-y-6 px-8 py-6">
        {error && (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to reach reporting-service: {(error as Error).message}
          </div>
        )}

        {/* Row 1: as-of-now snapshot */}
        <section>
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">As of now</h2>
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            <KpiTile
              label="Accounts receivable"
              value={snapshot ? formatMoney(snapshot.accountsReceivable, snapshot.currencyCode) : "—"}
              icon={<Receipt className="h-4 w-4 text-status-success" />}
            />
            <KpiTile
              label="Accounts payable"
              value={snapshot ? formatMoney(snapshot.accountsPayable, snapshot.currencyCode) : "—"}
              icon={<Wallet className="h-4 w-4 text-status-warn" />}
            />
            <KpiTile
              label="Inventory value"
              value={snapshot ? formatMoney(snapshot.inventoryValue, snapshot.currencyCode) : "—"}
              icon={<Package className="h-4 w-4 text-text-muted" />}
            />
            <KpiTile
              label="Open SOs / POs / WOs"
              value={
                snapshot
                  ? `${snapshot.openSalesOrdersCount} · ${snapshot.openPurchaseOrdersCount} · ${snapshot.openWorkOrdersCount}`
                  : "—"
              }
              icon={<ListChecks className="h-4 w-4 text-text-muted" />}
            />
          </div>
        </section>

        {/* Row 2: per-day deltas */}
        <section>
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">Today</h2>
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            <KpiTile
              label="Sales revenue"
              value={today ? formatMoney(today.salesRevenue, today.currencyCode) : "—"}
              icon={<TrendingUp className="h-4 w-4 text-status-success" />}
            />
            <KpiTile
              label="COGS"
              value={today ? formatMoney(today.costOfGoodsSold, today.currencyCode) : "—"}
              icon={<TrendingDown className="h-4 w-4 text-status-warn" />}
            />
            <KpiTile
              label="Cash received"
              value={today ? formatMoney(today.cashReceived, today.currencyCode) : "—"}
            />
            <KpiTile
              label="Cash paid"
              value={today ? formatMoney(today.cashPaid, today.currencyCode) : "—"}
            />
          </div>
        </section>

        {/* Row 3: per-day grid */}
        <section>
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">Recent days</h2>
          <DataGrid
            columns={dailyColumns}
            rows={rows ?? []}
            rowKey={(r) => r.dashboardDate}
            loading={isLoading}
            emptyState="No dashboard rows yet."
          />
        </section>

        <p className="text-xs text-text-muted">
          <strong>AR / AP / Inventory @ EOD</strong> are refreshed by a rollup worker every 60 s — historical
          balance queries (<code className="font-mono">GET /api/financial-dashboard/{"{date}"}</code>) return the
          worker's last write for that date. The <em>As of now</em> row up top is computed real-time on every read.{" "}
          <strong>WIP value</strong> stays at 0 — gated on a costing decision (LIFO / FIFO / weighted-avg) for{" "}
          <code className="font-mono">wip_balance.average_cost</code>.
        </p>
      </div>
    </>
  );
}

function KpiTile({ label, value, icon }: { label: string; value: string; icon?: React.ReactNode }) {
  return (
    <div className="rounded-md border border-border-default bg-bg-surface px-4 py-3">
      <div className="flex items-center justify-between text-[11px] font-medium uppercase tracking-wider text-text-muted">
        <span>{label}</span>
        {icon}
      </div>
      <div className="mt-1 text-xl font-semibold tabular-nums text-text-primary">{value}</div>
    </div>
  );
}

function formatMoney(v: string | null | undefined, currency: string): string {
  if (v == null) return "—";
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return `${currency} ${n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}
