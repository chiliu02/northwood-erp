import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Filter, Download } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { FilterPanel, useFieldFilters, type FilterField } from "@/components/ui/FilterPanel";
import { downloadCsv } from "@/lib/csv";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";

interface PoRow {
  purchaseOrderHeaderId: string;
  purchaseOrderNumber: string;
  supplierName: string;
  poStatus: string;
  orderDate: string;
  currencyCode: string;
  orderedAmount: string;
  receivedAmount: string;
  invoicedAmount: string;
  paidAmount: string;
  outstandingAmount: string;
  receiptStatus: string;
  invoiceStatus: string;
  paymentStatus: string;
  matchStatus: string;
  updatedAt: string;
}

/**
 * PO list — Tom's main screen. Reads from reporting's
 * purchase_order_tracking_view via the BFF. Click-through to detail
 * which carries the approve action when status='draft'.
 */
export function PurchaseOrders() {
  const navigate = useNavigate();
  const { data, isLoading, error } = useQuery({
    queryKey: ["purchase-orders"],
    queryFn: () => apiGet<PoRow[]>("/api/purchase-orders"),
  });

  const filterFields: FilterField<PoRow>[] = [
    { key: "number", label: "PO #", get: (r) => r.purchaseOrderNumber },
    { key: "supplier", label: "Supplier", get: (r) => r.supplierName },
    { key: "status", label: "Status", type: "select", get: (r) => r.poStatus },
    { key: "match", label: "Match", type: "select", get: (r) => r.matchStatus, optionLabel: (v) => (v === "matched" ? "Matched" : "Not matched") },
  ];
  const filter = useFieldFilters(data ?? [], filterFields);

  const columns: Column<PoRow>[] = [
    {
      key: "number",
      header: "PO #",
      width: "150px",
      sortAccessor: (r) => r.purchaseOrderNumber,
      render: (r) => <span className="font-medium tabular-nums">{r.purchaseOrderNumber}</span>,
    },
    { key: "supplier", header: "Supplier", sortAccessor: (r) => r.supplierName, render: (r) => r.supplierName },
    {
      key: "status",
      header: "Status",
      width: "140px",
      sortAccessor: (r) => r.poStatus,
      render: (r) => {
        const s = statusForOrder(r.poStatus);
        return <StatusPill label={s.label} tone={s.tone} />;
      },
    },
    {
      key: "match",
      header: "Match",
      width: "120px",
      sortAccessor: (r) => r.matchStatus,
      render: (r) => (
        <StatusPill
          label={r.matchStatus === "matched" ? "Matched" : "Not matched"}
          tone={r.matchStatus === "matched" ? "success" : "neutral"}
        />
      ),
    },
    {
      key: "ordered",
      header: "Ordered",
      numeric: true,
      width: "120px",
      sortAccessor: (r) => Number(r.orderedAmount),
      render: (r) => formatMoney(r.orderedAmount),
    },
    {
      key: "received",
      header: "Received",
      numeric: true,
      width: "120px",
      sortAccessor: (r) => Number(r.receivedAmount),
      render: (r) => (
        <span className={Number(r.receivedAmount) === Number(r.orderedAmount) ? "text-status-success" : "text-text-muted"}>
          {formatMoney(r.receivedAmount)}
        </span>
      ),
    },
    {
      key: "outstanding",
      header: "Outstanding",
      numeric: true,
      width: "120px",
      sortAccessor: (r) => Number(r.outstandingAmount),
      render: (r) => (
        <span className={Number(r.outstandingAmount) > 0 ? "text-status-warn" : "text-text-muted"}>
          {formatMoney(r.outstandingAmount)} <span className="text-text-faint">{r.currencyCode}</span>
        </span>
      ),
    },
    {
      key: "updated",
      header: "Updated",
      width: "120px",
      sortAccessor: (r) => new Date(r.updatedAt).getTime(),
      render: (r) => <span className="text-text-muted">{formatRelative(r.updatedAt)}</span>,
    },
  ];

  return (
    <>
      <PageHeader
        title="Purchase Orders"
        trail={[
          { label: "Purchasing" },
          { label: "Purchase Orders" },
        ]}
        actions={
          <>
            <ActionButton
              icon={<Filter className="h-4 w-4" />}
              variant={filter.open ? "primary" : "secondary"}
              onClick={filter.toggle}
            >
              Filter
            </ActionButton>
            <ActionButton
              icon={<Download className="h-4 w-4" />}
              onClick={() => downloadCsv("purchase-orders.csv", filter.filtered)}
              disabled={filter.filtered.length === 0}
            >
              Export
            </ActionButton>
          </>
        }
      />

      <FilterPanel
        open={filter.open}
        rows={data ?? []}
        fields={filterFields}
        values={filter.values}
        onChange={filter.set}
        onClear={filter.clear}
        onClose={filter.close}
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load purchase orders: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={filter.filtered}
            rowKey={(r) => r.purchaseOrderHeaderId}
            onRowClick={(r) => navigate(`/purchase-orders/${r.purchaseOrderHeaderId}`)}
            loading={isLoading}
            emptyState={filter.active ? "No purchase orders match the filter." : "No purchase orders yet."}
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {filter.filtered.length} purchase order{filter.filtered.length === 1 ? "" : "s"}
            {filter.active ? ` (filtered from ${data.length})` : ""}.
          </div>
        )}
      </div>
    </>
  );
}

function formatMoney(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatRelative(iso: string): string {
  if (!iso) return "—";
  const then = new Date(iso).getTime();
  const diff = Math.max(0, Date.now() - then);
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  return `${Math.floor(hr / 24)}d ago`;
}
