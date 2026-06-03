import { useQuery } from "@tanstack/react-query";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { StatusPill } from "@/components/ui/StatusPill";

interface StockReservation {
  stockReservationHeaderId: string;
  salesOrderHeaderId: string | null;
  workOrderId: string | null;
  warehouseId: string;
  status: string;
  lineCount: number;
  totalRequestedQuantity: string;
  totalReservedQuantity: string;
  totalShortageQuantity: string;
  createdAt: string;
}

/**
 * Stock reservation observation list. Reservations are saga-driven — created
 * by StockReservationService in response to inbound StockReservationRequested
 * events. Mike (warehouse_clerk) reads this to see why a sales order or
 * work order is parked / shipped / shorted.
 */
export function StockReservations() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["stock-reservations"],
    queryFn: () => apiGet<StockReservation[]>("/api/stock-reservations"),
  });

  const columns: Column<StockReservation>[] = [
    {
      key: "source",
      header: "Source",
      width: "120px",
      render: (r) =>
        r.salesOrderHeaderId
          ? <span className="text-text-muted">sales</span>
          : <span className="text-text-muted">work order</span>,
    },
    {
      key: "headerId",
      header: "Reservation",
      render: (r) => <span className="tabular-nums text-xs">{r.stockReservationHeaderId.slice(0, 8)}…</span>,
    },
    {
      key: "lines",
      header: "Lines",
      width: "80px",
      numeric: true,
      render: (r) => <span className="tabular-nums">{r.lineCount}</span>,
    },
    {
      key: "requested",
      header: "Requested",
      width: "120px",
      numeric: true,
      render: (r) => <span className="tabular-nums">{formatQty(r.totalRequestedQuantity)}</span>,
    },
    {
      key: "reserved",
      header: "Reserved",
      width: "120px",
      numeric: true,
      render: (r) => <span className="tabular-nums">{formatQty(r.totalReservedQuantity)}</span>,
    },
    {
      key: "shortage",
      header: "Shortage",
      width: "120px",
      numeric: true,
      render: (r) => {
        const n = Number(r.totalShortageQuantity);
        const cls = n > 0 ? "text-status-warning" : "text-text-muted";
        return <span className={`tabular-nums ${cls}`}>{formatQty(r.totalShortageQuantity)}</span>;
      },
    },
    {
      key: "status",
      header: "Status",
      width: "150px",
      render: (r) => <StatusPill label={r.status} tone={statusTone(r.status)} />,
    },
    {
      key: "createdAt",
      header: "Created",
      width: "160px",
      render: (r) => <span className="text-text-muted text-xs">{formatDate(r.createdAt)}</span>,
    },
  ];

  return (
    <>
      <PageHeader
        title="Stock Reservations"
        description="Saga-driven reservations against on-hand stock. Sourced by sales orders or work orders; status reflects how much of the request the warehouse could honour."
        trail={[
          { label: "Inventory" },
          { label: "Reservations" },
        ]}
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load reservations: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(r) => r.stockReservationHeaderId}
            loading={isLoading}
            emptyState="No reservations yet."
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {data.length} reservation{data.length === 1 ? "" : "s"}.
          </div>
        )}
      </div>
    </>
  );
}

function statusTone(s: string): "success" | "warn" | "error" | "neutral" {
  switch (s) {
    case "reserved": return "success";
    case "partially_reserved": return "warn";
    case "failed": return "error";
    case "released": return "neutral";
    case "consumed": return "neutral";
    default: return "neutral";
  }
}

function formatQty(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 3 });
}

function formatDate(s: string): string {
  return new Date(s).toLocaleString("en-AU", { dateStyle: "short", timeStyle: "short" });
}
