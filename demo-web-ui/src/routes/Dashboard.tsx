import { useQuery } from "@tanstack/react-query";
import {
  fetchFinancialDashboard,
  fetchFinancialDashboardSnapshot,
  type FinancialDashboardRow,
} from "@/api/reporting";
import { Card, CardBody } from "@/components/ui/Card";
import { cn, formatMoney } from "@/lib/utils";
import { TrendingUp, TrendingDown, ListChecks, Wallet, Receipt, Package } from "lucide-react";

export function Dashboard() {
  const { data, error, isLoading } = useQuery({
    queryKey: ["financial-dashboard", "AUD"],
    queryFn: () => fetchFinancialDashboard("AUD"),
    refetchInterval: 5_000,  // gentle polling; SSE will replace in Phase 3
  });

  // As-of-now snapshot (AR / AP / open counts) — separate from per-day deltas.
  const { data: snapshot } = useQuery({
    queryKey: ["financial-dashboard-snapshot", "AUD"],
    queryFn: () => fetchFinancialDashboardSnapshot("AUD"),
    refetchInterval: 5_000,
  });

  const today = data?.[0];                      // newest first per the controller
  const last7 = (data ?? []).slice(0, 7).reverse();

  return (
    <div className="space-y-6">
      <div className="flex items-baseline justify-between">
        <div>
          <h1 className="text-[28px] font-semibold tracking-tight">Dashboard</h1>
          <p className="text-sm text-text-muted">
            {today
              ? `Today · ${today.currencyCode} · ${today.dashboardDate}`
              : "Awaiting first event for the financial-dashboard projection"}
          </p>
        </div>
        <FetchIndicator isLoading={isLoading} hasError={!!error} />
      </div>

      {error && <ErrorBanner message={String(error)} />}

      {/* Row 1: as-of-now snapshot — AR, AP, currently-open counts.
          Distinct from row 2's per-day deltas. */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <KpiTile
          label="Accounts receivable"
          value={snapshot ? formatMoney(snapshot.accountsReceivable, snapshot.currencyCode) : "—"}
          icon={<Receipt className="h-4 w-4" style={{ color: "var(--color-state-success)" }} />}
        />
        <KpiTile
          label="Accounts payable"
          value={snapshot ? formatMoney(snapshot.accountsPayable, snapshot.currencyCode) : "—"}
          icon={<Wallet className="h-4 w-4" style={{ color: "var(--color-state-warn)" }} />}
        />
        <KpiTile
          label="Open SOs"
          value={snapshot?.openSalesOrdersCount ?? "—"}
          icon={<ListChecks className="h-4 w-4 text-text-muted" />}
        />
        <KpiTile
          label="Open POs"
          value={snapshot?.openPurchaseOrdersCount ?? "—"}
          icon={<ListChecks className="h-4 w-4 text-text-muted" />}
        />
      </div>

      {/* Row 2: per-day deltas — events that happened today. */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <KpiTile
          label="Sales revenue (today)"
          value={today ? formatMoney(today.salesRevenue, today.currencyCode) : "—"}
          icon={<TrendingUp className="h-4 w-4" style={{ color: "var(--color-state-success)" }} />}
          spark={last7.map((r) => Number(r.salesRevenue))}
        />
        <KpiTile
          label="Cash received (today)"
          value={today ? formatMoney(today.cashReceived, today.currencyCode) : "—"}
          spark={last7.map((r) => Number(r.cashReceived))}
        />
        <KpiTile
          label="COGS (today)"
          value={today ? formatMoney(today.costOfGoodsSold, today.currencyCode) : "—"}
          icon={<TrendingDown className="h-4 w-4" style={{ color: "var(--color-state-warn)" }} />}
        />
        <KpiTile
          label="Cash paid (today)"
          value={today ? formatMoney(today.cashPaid, today.currencyCode) : "—"}
        />
      </div>

      {/* Row 3: cross-cutting. Inventory value (snapshot — SUM of
          on_hand × standard_cost); Open WOs from snapshot; gross % from today. */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <KpiTile
          label="Inventory value"
          value={snapshot ? formatMoney(snapshot.inventoryValue, snapshot.currencyCode) : "—"}
          icon={<Package className="h-4 w-4 text-text-muted" />}
        />
        <KpiTile
          label="Open WOs"
          value={snapshot?.openWorkOrdersCount ?? "—"}
          icon={<ListChecks className="h-4 w-4 text-text-muted" />}
        />
        <KpiTile
          label="Gross % (today)"
          value={today ? grossPercent(today) : "—"}
        />
      </div>

      <Card>
        <CardBody>
          <div className="flex items-baseline justify-between">
            <h2 className="text-lg font-semibold">Last 7 days</h2>
            <p className="text-xs text-text-faint">revenue vs COGS · simple bars (Recharts swap in Phase 2)</p>
          </div>
          <div className="mt-4">
            <SimpleStackedBars rows={last7} />
          </div>
        </CardBody>
      </Card>

      <ParkedColumnsNote />
    </div>
  );
}

function grossPercent(row: FinancialDashboardRow): string {
  const rev = Number(row.salesRevenue);
  const cogs = Number(row.costOfGoodsSold);
  if (rev <= 0) return "—";
  return `${(((rev - cogs) / rev) * 100).toFixed(1)}%`;
}

interface KpiTileProps {
  label: string;
  value: string | number;
  icon?: React.ReactNode;
  spark?: number[];
}

function KpiTile({ label, value, icon, spark }: KpiTileProps) {
  return (
    <Card>
      <CardBody className="space-y-2">
        <div className="flex items-center justify-between text-xs uppercase tracking-wider text-text-muted">
          <span>{label}</span>
          {icon}
        </div>
        <div className="text-2xl font-semibold tabular-nums">{value}</div>
        {spark && spark.length > 0 && <Sparkline values={spark} />}
      </CardBody>
    </Card>
  );
}

function Sparkline({ values }: { values: number[] }) {
  const max = Math.max(...values, 1);
  return (
    <div className="flex h-6 items-end gap-0.5">
      {values.map((v, i) => (
        <div
          key={i}
          className="flex-1 rounded-sm bg-bg-hover"
          style={{
            height: `${Math.max((v / max) * 100, 4)}%`,
            background: "var(--color-state-success)",
            opacity: 0.5 + (i / values.length) * 0.5,
          }}
        />
      ))}
    </div>
  );
}

function SimpleStackedBars({ rows }: { rows: FinancialDashboardRow[] }) {
  if (rows.length === 0) return <p className="text-sm text-text-faint">No data yet.</p>;
  const max = Math.max(...rows.map((r) => Number(r.salesRevenue) + Number(r.costOfGoodsSold)), 1);
  return (
    <div className="flex h-40 items-end gap-3">
      {rows.map((r) => {
        const rev = Number(r.salesRevenue);
        const cogs = Number(r.costOfGoodsSold);
        const totalPct = ((rev + cogs) / max) * 100;
        const cogsPct = rev + cogs > 0 ? (cogs / (rev + cogs)) * 100 : 0;
        return (
          <div key={r.dashboardDate} className="flex flex-1 flex-col items-center gap-1.5">
            <div className="flex h-full w-full flex-col-reverse overflow-hidden rounded-sm bg-bg-hover" style={{ height: `${Math.max(totalPct, 2)}%` }}>
              <div style={{ height: `${100 - cogsPct}%`, background: "var(--color-state-success)" }} />
              <div style={{ height: `${cogsPct}%`,       background: "var(--color-state-warn)" }} />
            </div>
            <span className="text-[10px] text-text-faint">{r.dashboardDate.slice(5)}</span>
          </div>
        );
      })}
    </div>
  );
}

function FetchIndicator({ isLoading, hasError }: { isLoading: boolean; hasError: boolean }) {
  if (hasError) {
    return <span className="text-xs text-state-error">disconnected</span>;
  }
  return (
    <span className="flex items-center gap-1.5 text-xs text-text-faint">
      <span
        className={cn(
          "h-1.5 w-1.5 rounded-full",
          isLoading ? "animate-pulse" : ""
        )}
        style={{ background: "var(--color-state-success)" }}
      />
      live · 5s polling
    </span>
  );
}

function ErrorBanner({ message }: { message: string }) {
  return (
    <div
      className="rounded-md border px-4 py-3 text-sm"
      style={{
        borderColor: "var(--color-state-error)",
        background: "color-mix(in srgb, var(--color-state-error) 12%, transparent)",
      }}
    >
      <p className="font-medium" style={{ color: "var(--color-state-error)" }}>
        Couldn't reach reporting-service on :8087
      </p>
      <p className="mt-1 text-text-muted">{message}</p>
      <p className="mt-1 text-xs text-text-faint">
        Run <span className="font-mono">mvn -pl reporting-service spring-boot:run</span> and retry.
      </p>
    </div>
  );
}

function ParkedColumnsNote() {
  return (
    <div className="rounded-md border border-border-subtle bg-bg-elevated px-4 py-3 text-xs text-text-muted">
      <p>
        <span className="font-medium text-text-primary">WIP value</span> is the last
        parked column — gated on a costing decision (LIFO / FIFO / weighted-avg)
        for {" "}<span className="font-mono">wip_balance.average_cost</span>.
        AR · AP · Inventory value · open counts all went live via{" "}
        <span className="font-mono">GET /api/financial-dashboard/snapshot</span>.
      </p>
    </div>
  );
}
