import { useQuery } from "@tanstack/react-query";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { DataGrid, type Column } from "@/components/ui/DataGrid";

interface StockMovement {
  stockMovementId: string;
  warehouseId: string;
  productId: string;
  productSku: string;
  productName: string;
  movementType: string;
  direction: string;
  quantity: string;
  unitCost: string;
  totalCost: string;
  sourceType: string | null;
  sourceId: string | null;
  movementDate: string;
}

/**
 * Stock movement audit list. Append-only — every on-hand mutation site
 * (receipt, shipment, top-level WO completion) writes one row via
 * StockMovementWriter. Read-only observation; capped at 200 most-recent
 * rows by default.
 */
export function StockMovements() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["stock-movements"],
    queryFn: () => apiGet<StockMovement[]>("/api/stock-movements?limit=200"),
  });

  const columns: Column<StockMovement>[] = [
    {
      key: "date",
      header: "When",
      width: "160px",
      render: (m) => <span className="text-text-muted text-xs">{formatDate(m.movementDate)}</span>,
    },
    {
      key: "type",
      header: "Type",
      width: "180px",
      render: (m) => <span className="text-text-muted">{m.movementType.replace(/_/g, " ")}</span>,
    },
    {
      key: "direction",
      header: "Dir",
      width: "60px",
      render: (m) => (
        <span className={m.direction === "in" ? "text-status-success" : "text-status-warning"}>
          {m.direction === "in" ? "↑ in" : "↓ out"}
        </span>
      ),
    },
    {
      key: "sku",
      header: "SKU",
      width: "180px",
      render: (m) => <span className="font-medium tabular-nums">{m.productSku}</span>,
    },
    { key: "name", header: "Product", render: (m) => m.productName },
    {
      key: "quantity",
      header: "Qty",
      width: "120px",
      numeric: true,
      render: (m) => <span className="tabular-nums">{formatQty(m.quantity)}</span>,
    },
    {
      key: "totalCost",
      header: "Total Cost",
      width: "140px",
      numeric: true,
      render: (m) => <span className="tabular-nums">{formatMoney(m.totalCost)}</span>,
    },
  ];

  return (
    <>
      <PageHeader
        title="Stock Movements"
        description="Append-only audit of every on-hand mutation. Sourced from goods receipts, shipments, and finished-goods completion."
        trail={[
          { label: "Home", to: "/" },
          { label: "Inventory" },
          { label: "Stock Movements" },
        ]}
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load stock movements: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(m) => m.stockMovementId}
            loading={isLoading}
            emptyState="No movements yet."
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {data.length} movement{data.length === 1 ? "" : "s"} (most recent first; capped at 200).
          </div>
        )}
      </div>
    </>
  );
}

function formatQty(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 3 });
}

function formatMoney(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatDate(s: string): string {
  return new Date(s).toLocaleString("en-AU", { dateStyle: "short", timeStyle: "short" });
}
