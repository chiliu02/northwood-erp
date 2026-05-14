import { useQuery } from "@tanstack/react-query";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { StatusPill } from "@/components/ui/StatusPill";

interface MaterialShortageRow {
  materialProductId: string;
  materialSku: string;
  materialName: string;
  requiredQuantity: string;
  availableQuantity: string;
  shortageQuantity: string;
  affectedWorkOrdersCount: number;
  affectedSalesOrdersCount: number;
  openPurchaseOrdersCount: number;
  incomingPurchaseQuantity: string;
  expectedReceiptDate: string | null;
  status: string;
  updatedAt: string | null;
}

/** Reporting projection of material shortages — gaps between required and on-hand. */
export function MaterialShortages() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["material-shortages"],
    queryFn: () => apiGet<MaterialShortageRow[]>("/api/material-shortages"),
    refetchInterval: 5_000,
  });

  const columns: Column<MaterialShortageRow>[] = [
    {
      key: "sku",
      header: "SKU",
      width: "150px",
      render: (r) => <span className="font-mono text-xs">{r.materialSku}</span>,
    },
    { key: "name", header: "Material", render: (r) => r.materialName },
    {
      key: "required",
      header: "Required",
      numeric: true,
      width: "100px",
      render: (r) => <span className="tabular-nums">{formatQty(r.requiredQuantity)}</span>,
    },
    {
      key: "available",
      header: "Available",
      numeric: true,
      width: "100px",
      render: (r) => <span className="tabular-nums">{formatQty(r.availableQuantity)}</span>,
    },
    {
      key: "shortage",
      header: "Shortage",
      numeric: true,
      width: "100px",
      render: (r) => <span className="tabular-nums font-medium text-status-error">{formatQty(r.shortageQuantity)}</span>,
    },
    {
      key: "wos",
      header: "Affected WOs",
      numeric: true,
      width: "100px",
      render: (r) => <span className="tabular-nums text-text-muted">{r.affectedWorkOrdersCount}</span>,
    },
    {
      key: "sos",
      header: "Affected SOs",
      numeric: true,
      width: "100px",
      render: (r) => <span className="tabular-nums text-text-muted">{r.affectedSalesOrdersCount}</span>,
    },
    {
      key: "pos",
      header: "Open POs",
      numeric: true,
      width: "90px",
      render: (r) => <span className="tabular-nums text-text-muted">{r.openPurchaseOrdersCount}</span>,
    },
    {
      key: "incoming",
      header: "Incoming",
      numeric: true,
      width: "100px",
      render: (r) => <span className="tabular-nums">{formatQty(r.incomingPurchaseQuantity)}</span>,
    },
    {
      key: "eta",
      header: "Expected",
      width: "120px",
      render: (r) => <span className="text-text-muted">{r.expectedReceiptDate ?? "—"}</span>,
    },
    {
      key: "status",
      header: "Status",
      width: "130px",
      render: (r) => <StatusPill label={r.status} tone={statusTone(r.status)} />,
    },
  ];

  return (
    <>
      <PageHeader
        title="Material Shortages"
        description="Materials whose required quantity (across active work orders) exceeds on-hand stock. Tom uses this to prioritise PR creation."
        trail={[
          { label: "Home", to: "/" },
          { label: "Reporting" },
          { label: "Material Shortages" },
        ]}
      />
      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load shortages: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(r) => r.materialProductId}
            loading={isLoading}
            emptyState="No material shortages — everything's covered."
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {data.length} material{data.length === 1 ? "" : "s"} short.
          </div>
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
    case "covered":
    case "resolved":
      return "success";
    case "incoming":
    case "partial":
      return "warn";
    case "open":
    case "critical":
      return "error";
    default:
      return "neutral";
  }
}
