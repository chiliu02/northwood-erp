import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { SlidersHorizontal } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";

interface StockItem {
  productId: string;
  productSku: string;
  productName: string;
  productType: string;
  baseUomCode: string;
  trackingMode: string;
  reorderPoint: string | null;
  reorderQuantity: string | null;
  onHand: string;
  reserved: string;
  available: string;
}

// §2.35 Slice F: reporting.replenishment_history_view row.
interface ReplenishmentHistoryRow {
  replenishmentRequestId: string;
  productId: string;
  productSku: string | null;
  productName: string | null;
  warehouseId: string;
  requestedQuantity: string;
  targetService: string;
  reason: string;
  status: string;
  dispatchedAggregateKind: string | null;
  dispatchedAggregateId: string | null;
  requestedAt: string;
  dispatchedAt: string | null;
  fulfilledAt: string | null;
}

/**
 * Stock item master — Mike's catalogue. Reorder policy lives on Product
 * (Shape A); inventory projects it. The list is read-only here; thresholds
 * are edited via Product Detail.
 */
export function StockItems() {
  const navigate = useNavigate();
  const { data, isLoading, error } = useQuery({
    queryKey: ["stock-items"],
    queryFn: () => apiGet<StockItem[]>("/api/stock-items"),
  });

  const columns: Column<StockItem>[] = [
    {
      key: "sku",
      header: "SKU",
      width: "180px",
      render: (s) => <span className="font-medium tabular-nums">{s.productSku}</span>,
    },
    { key: "name", header: "Name", render: (s) => s.productName },
    {
      key: "type",
      header: "Type",
      width: "160px",
      render: (s) => <span className="text-text-muted">{s.productType.replace(/_/g, " ")}</span>,
    },
    {
      key: "uom",
      header: "UoM",
      width: "80px",
      render: (s) => <span className="text-text-muted tabular-nums">{s.baseUomCode}</span>,
    },
    {
      key: "tracking",
      header: "Tracking",
      width: "120px",
      render: (s) => <span className="text-text-muted">{s.trackingMode}</span>,
    },
    {
      key: "onHand",
      header: "On Hand",
      width: "110px",
      numeric: true,
      render: (s) => <span className="tabular-nums">{formatQty(s.onHand)}</span>,
    },
    {
      key: "reserved",
      header: "Reserved",
      width: "110px",
      numeric: true,
      render: (s) => <span className="tabular-nums text-text-muted">{formatQty(s.reserved)}</span>,
    },
    {
      key: "available",
      header: "Available",
      width: "110px",
      numeric: true,
      render: (s) => <span className="tabular-nums font-medium">{formatQty(s.available)}</span>,
    },
    {
      key: "reorderPoint",
      header: "Reorder Pt",
      width: "120px",
      numeric: true,
      render: (s) => <span className="tabular-nums">{formatQty(s.reorderPoint)}</span>,
    },
    {
      key: "reorderQty",
      header: "Reorder Qty",
      width: "120px",
      numeric: true,
      render: (s) => <span className="tabular-nums">{formatQty(s.reorderQuantity)}</span>,
    },
  ];

  return (
    <>
      <PageHeader
        title="Stock Balances"
        description="Inventory's projection of every catalogued product. Reorder policy is owned by product-service and projected here via product.ReorderPolicyChanged."
        trail={[
          { label: "Home", to: "/" },
          { label: "Inventory" },
          { label: "Stock Balances" },
        ]}
        actions={
          <ActionButton
            variant="primary"
            icon={<SlidersHorizontal className="h-4 w-4" />}
            requiresRole="warehouse_manager"
            onClick={() => navigate("/stock-adjustments/new")}
          >
            Adjust stock
          </ActionButton>
        }
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load stock items: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(s) => s.productId}
            loading={isLoading}
            emptyState="No stock items projected yet."
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {data.length} item{data.length === 1 ? "" : "s"}.
          </div>
        )}

        <ReplenishmentActivity />
      </div>
    </>
  );
}

/**
 * §2.35 Slice F: "Replenishment activity" widget. Lists the most recent
 * auto-replenishment requests system-wide with their state. Driven by the
 * reporting.replenishment_history_view projection.
 */
function ReplenishmentActivity() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["replenishment-history"],
    queryFn: () => apiGet<ReplenishmentHistoryRow[]>("/api/replenishment-history?limit=20"),
    refetchInterval: 5_000,
  });

  const columns: Column<ReplenishmentHistoryRow>[] = [
    {
      key: "sku",
      header: "SKU",
      width: "180px",
      render: (r) => <span className="font-mono">{r.productSku ?? r.productId.slice(0, 8)}</span>,
    },
    {
      key: "qty",
      header: "Qty",
      width: "90px",
      numeric: true,
      render: (r) => <span className="tabular-nums">{Number(r.requestedQuantity)}</span>,
    },
    {
      key: "reason",
      header: "Reason",
      width: "180px",
      render: (r) => <span className="text-text-muted">{r.reason.replace(/_/g, " ")}</span>,
    },
    {
      key: "target",
      header: "Target",
      width: "140px",
      render: (r) => <span className="text-text-muted">{r.targetService}</span>,
    },
    {
      key: "status",
      header: "Status",
      width: "120px",
      render: (r) => <span className={statusClass(r.status)}>{r.status}</span>,
    },
    {
      key: "dispatched-to",
      header: "Dispatched to",
      width: "160px",
      render: (r) => (
        <span className="font-mono text-xs text-text-muted">
          {r.dispatchedAggregateId ? r.dispatchedAggregateId.slice(0, 8) : "—"}
        </span>
      ),
    },
    {
      key: "requested",
      header: "Requested",
      width: "180px",
      render: (r) => <span className="text-xs text-text-muted">{formatTs(r.requestedAt)}</span>,
    },
    {
      key: "fulfilled",
      header: "Fulfilled",
      width: "180px",
      render: (r) => <span className="text-xs text-text-muted">{formatTs(r.fulfilledAt)}</span>,
    },
  ];

  return (
    <section className="mt-8 space-y-2">
      <div className="flex items-baseline gap-3">
        <h2 className="text-base font-semibold">Replenishment activity</h2>
        <span className="text-xs text-text-muted">
          §2.35 — auto-raised on reorder-point breach or WO raw-material shortage. Routed by make-vs-buy.
        </span>
      </div>
      {error ? (
        <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
          Failed to load replenishment history: {(error as Error).message}
        </div>
      ) : (
        <DataGrid
          columns={columns}
          rows={data ?? []}
          rowKey={(r) => r.replenishmentRequestId}
          loading={isLoading}
          emptyState="No replenishments yet. Ship goods past a SKU's reorder point to see one appear here."
        />
      )}
    </section>
  );
}

function statusClass(s: string): string {
  if (s === "fulfilled") return "font-medium text-status-success";
  if (s === "dispatched") return "font-medium text-status-warn";
  return "text-text-muted";
}

function formatTs(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

function formatQty(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 3 });
}
