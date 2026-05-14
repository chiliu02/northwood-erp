import { useQuery } from "@tanstack/react-query";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { StatusPill } from "@/components/ui/StatusPill";

interface AvailableToPromiseRow {
  productId: string;
  productSku: string;
  productName: string;
  onHandQuantity: string;
  reservedForSales: string;
  reservedForProduction: string;
  availableQuantity: string;
  incomingFromProduction: string;
  incomingFromPurchase: string;
  earliestAvailableDate: string | null;
  stockStatus: string;
  updatedAt: string | null;
}

/** Reporting projection of every product's available-to-promise position. */
export function Atp() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["atp"],
    queryFn: () => apiGet<AvailableToPromiseRow[]>("/api/atp"),
    refetchInterval: 5_000,
  });

  const columns: Column<AvailableToPromiseRow>[] = [
    {
      key: "sku",
      header: "SKU",
      width: "150px",
      render: (r) => <span className="font-mono text-xs">{r.productSku}</span>,
    },
    { key: "name", header: "Product", render: (r) => r.productName },
    {
      key: "onHand",
      header: "On hand",
      numeric: true,
      width: "100px",
      render: (r) => <span className="tabular-nums">{formatQty(r.onHandQuantity)}</span>,
    },
    {
      key: "resSales",
      header: "Res. (sales)",
      numeric: true,
      width: "110px",
      render: (r) => <span className="tabular-nums text-text-muted">{formatQty(r.reservedForSales)}</span>,
    },
    {
      key: "resProd",
      header: "Res. (prod)",
      numeric: true,
      width: "110px",
      render: (r) => <span className="tabular-nums text-text-muted">{formatQty(r.reservedForProduction)}</span>,
    },
    {
      key: "available",
      header: "Available",
      numeric: true,
      width: "110px",
      render: (r) => <span className="tabular-nums font-medium">{formatQty(r.availableQuantity)}</span>,
    },
    {
      key: "incProd",
      header: "Inc. (prod)",
      numeric: true,
      width: "110px",
      render: (r) => <span className="tabular-nums text-text-muted">{formatQty(r.incomingFromProduction)}</span>,
    },
    {
      key: "incPurch",
      header: "Inc. (purch)",
      numeric: true,
      width: "110px",
      render: (r) => <span className="tabular-nums text-text-muted">{formatQty(r.incomingFromPurchase)}</span>,
    },
    {
      key: "earliest",
      header: "Earliest avail",
      width: "120px",
      render: (r) => <span className="text-text-muted">{r.earliestAvailableDate ?? "—"}</span>,
    },
    {
      key: "status",
      header: "Status",
      width: "130px",
      render: (r) => <StatusPill label={r.stockStatus} tone={statusTone(r.stockStatus)} />,
    },
  ];

  return (
    <>
      <PageHeader
        title="Available-to-Promise"
        description="Per-product stock position projected from inventory + open sales/production + incoming receipts. Refreshes every 5 seconds."
        trail={[
          { label: "Home", to: "/" },
          { label: "Reporting" },
          { label: "Available-to-Promise" },
        ]}
      />
      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load ATP: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(r) => r.productId}
            loading={isLoading}
            emptyState="No products in the projection yet."
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">{data.length} product{data.length === 1 ? "" : "s"}.</div>
        )}
      </div>
    </>
  );
}

function formatQty(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { maximumFractionDigits: 3 });
}

function statusTone(s: string): "success" | "warn" | "error" | "neutral" {
  switch (s) {
    case "available":
    case "ok":
      return "success";
    case "low":
    case "watching":
      return "warn";
    case "out":
    case "shortage":
      return "error";
    default:
      return "neutral";
  }
}
