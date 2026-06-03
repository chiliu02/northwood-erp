import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Filter, Download, X } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { FilterPanel, useFieldFilters, type FilterField } from "@/components/ui/FilterPanel";
import { downloadCsv } from "@/lib/csv";
import { FormSection, ReadOnlyField } from "@/components/ui/FormSection";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";

interface PurchaseOrderTracking {
  purchaseOrderHeaderId: string;
  purchaseOrderNumber: string;
  supplierId: string;
  supplierName: string | null;
  poStatus: string;
  orderDate: string | null;
  expectedReceiptDate: string | null;
  currencyCode: string;
  orderedAmount: string;
  receivedAmount: string;
  invoicedAmount: string;
  paidAmount: string;
  outstandingAmount: string;
  receiptStatus: string | null;
  invoiceStatus: string | null;
  paymentStatus: string | null;
  matchStatus: string | null;
  lastGoodsReceiptHeaderId: string | null;
  lastSupplierInvoiceHeaderId: string | null;
  lastPaymentId: string | null;
  updatedAt: string | null;
}

/**
 * Reporting view of P2P trajectories: a filterable list of every tracked PO
 * (ordered → received → invoiced → paid + 3-way match), sourced from
 * reporting's purchase_order_tracking_view. Clicking a row opens that PO's
 * full trajectory dashboard below the table.
 */
export function PurchaseOrderTracking() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["po-tracking-list"],
    queryFn: () => apiGet<PurchaseOrderTracking[]>("/api/purchase-orders"),
  });

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = useMemo(
    () => (data ?? []).find((r) => r.purchaseOrderHeaderId === selectedId) ?? null,
    [data, selectedId]
  );

  const filterFields: FilterField<PurchaseOrderTracking>[] = [
    { key: "number", label: "PO #", get: (r) => r.purchaseOrderNumber },
    { key: "supplier", label: "Supplier", get: (r) => r.supplierName ?? "" },
    { key: "status", label: "PO status", type: "select", get: (r) => r.poStatus },
    { key: "match", label: "Match", type: "select", get: (r) => r.matchStatus ?? "not_matched", optionLabel: (v) => (v === "matched" ? "Matched" : "Not matched") },
  ];
  const filter = useFieldFilters(data ?? [], filterFields);

  const columns: Column<PurchaseOrderTracking>[] = [
    {
      key: "number",
      header: "PO #",
      width: "150px",
      sortAccessor: (r) => r.purchaseOrderNumber,
      render: (r) => <span className="font-medium tabular-nums">{r.purchaseOrderNumber}</span>,
    },
    { key: "supplier", header: "Supplier", sortAccessor: (r) => r.supplierName ?? "", render: (r) => r.supplierName ?? "—" },
    {
      key: "status",
      header: "Status",
      width: "130px",
      sortAccessor: (r) => r.poStatus,
      render: (r) => { const s = statusForOrder(r.poStatus); return <StatusPill label={s.label} tone={s.tone} />; },
    },
    { key: "ordered", header: "Ordered", numeric: true, width: "110px", sortAccessor: (r) => Number(r.orderedAmount), render: (r) => formatMoney(r.orderedAmount) },
    {
      key: "received", header: "Received", numeric: true, width: "110px",
      sortAccessor: (r) => Number(r.receivedAmount),
      render: (r) => <span className={Number(r.receivedAmount) >= Number(r.orderedAmount) && Number(r.orderedAmount) > 0 ? "text-status-success" : "text-text-muted"}>{formatMoney(r.receivedAmount)}</span>,
    },
    { key: "invoiced", header: "Invoiced", numeric: true, width: "110px", sortAccessor: (r) => Number(r.invoicedAmount), render: (r) => <span className="text-text-muted">{formatMoney(r.invoicedAmount)}</span> },
    { key: "paid", header: "Paid", numeric: true, width: "110px", sortAccessor: (r) => Number(r.paidAmount), render: (r) => <span className="text-text-muted">{formatMoney(r.paidAmount)}</span> },
    {
      key: "outstanding", header: "Outstanding", numeric: true, width: "120px",
      sortAccessor: (r) => Number(r.outstandingAmount),
      render: (r) => <span className={Number(r.outstandingAmount) > 0 ? "text-status-warn" : "text-text-muted"}>{formatMoney(r.outstandingAmount)} <span className="text-text-faint">{r.currencyCode}</span></span>,
    },
    {
      key: "match", header: "Match", width: "120px",
      sortAccessor: (r) => r.matchStatus ?? "",
      render: (r) => <StatusPill label={r.matchStatus === "matched" ? "Matched" : "Not matched"} tone={r.matchStatus === "matched" ? "success" : "neutral"} />,
    },
    { key: "updated", header: "Updated", width: "110px", sortAccessor: (r) => (r.updatedAt ? new Date(r.updatedAt).getTime() : 0), render: (r) => <span className="text-text-muted">{formatRelative(r.updatedAt)}</span> },
  ];

  return (
    <>
      <PageHeader
        title="PO Tracking"
        description="End-to-end P2P trajectory of every purchase order: ordered → received → invoiced → paid, sourced from the reporting projection. Click a row for its full trajectory."
        trail={[{ label: "Reporting" }, { label: "PO Tracking" }]}
        actions={
          <>
            <ActionButton icon={<Filter className="h-4 w-4" />} variant={filter.open ? "primary" : "secondary"} onClick={filter.toggle}>
              Filter
            </ActionButton>
            <ActionButton icon={<Download className="h-4 w-4" />} onClick={() => downloadCsv("po-tracking.csv", filter.filtered)} disabled={filter.filtered.length === 0}>
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

      <div className="space-y-6 px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load PO tracking: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={filter.filtered}
            rowKey={(r) => r.purchaseOrderHeaderId}
            onRowClick={(r) => setSelectedId((prev) => (prev === r.purchaseOrderHeaderId ? null : r.purchaseOrderHeaderId))}
            loading={isLoading}
            emptyState={filter.active ? "No purchase orders match the filter." : "No purchase orders tracked yet."}
          />
        )}
        {data && !error && (
          <div className="text-xs text-text-muted">
            {filter.filtered.length} purchase order{filter.filtered.length === 1 ? "" : "s"}
            {filter.active ? ` (filtered from ${data.length})` : ""}.
          </div>
        )}

        {selected && <TrajectoryDetail row={selected} onClose={() => setSelectedId(null)} />}
      </div>
    </>
  );
}

function TrajectoryDetail({ row, onClose }: { row: PurchaseOrderTracking; onClose: () => void }) {
  const poStatus = statusForOrder(row.poStatus);
  return (
    <div className="space-y-5 rounded-md border border-border-default bg-bg-surface p-5">
      <div className="flex items-start justify-between">
        <div>
          <h2 className="text-sm font-semibold text-text-primary">{row.purchaseOrderNumber}</h2>
          <p className="text-xs text-text-muted">{row.supplierName ?? "—"}</p>
        </div>
        <button type="button" onClick={onClose} className="flex h-7 w-7 items-center justify-center rounded-md text-text-muted hover:bg-bg-subtle" aria-label="Close">
          <X className="h-4 w-4" />
        </button>
      </div>

      <FormSection title="Header">
        <ReadOnlyField label="PO status" value={<StatusPill label={poStatus.label} tone={poStatus.tone} />} />
        <ReadOnlyField label="Order date" value={row.orderDate ?? "—"} />
        <ReadOnlyField label="Expected receipt" value={row.expectedReceiptDate ?? "—"} />
        <ReadOnlyField label="Currency" value={row.currencyCode} />
        <ReadOnlyField label="Updated" value={row.updatedAt ?? "—"} />
        <ReadOnlyField label="Supplier id" value={<code className="text-xs text-text-muted">{shortUuid(row.supplierId)}</code>} />
      </FormSection>

      <section>
        <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">Amounts</h3>
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
          <Tile label="Ordered" value={formatMoneyC(row.orderedAmount, row.currencyCode)} />
          <Tile label="Received" value={formatMoneyC(row.receivedAmount, row.currencyCode)} />
          <Tile label="Invoiced" value={formatMoneyC(row.invoicedAmount, row.currencyCode)} />
          <Tile label="Paid" value={formatMoneyC(row.paidAmount, row.currencyCode)} />
          <Tile label="Outstanding" value={formatMoneyC(row.outstandingAmount, row.currencyCode)} emphasize />
        </div>
      </section>

      <section>
        <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">Sub-statuses</h3>
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <StatusTile label="Receipts" status={row.receiptStatus} />
          <StatusTile label="Invoices" status={row.invoiceStatus} />
          <StatusTile label="Payments" status={row.paymentStatus} />
          <StatusTile label="3-way match" status={row.matchStatus} />
        </div>
      </section>

      <section>
        <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">Last document ids</h3>
        <div className="grid grid-cols-1 gap-2 lg:grid-cols-3">
          <DocRef label="Last goods receipt" id={row.lastGoodsReceiptHeaderId} />
          <DocRef label="Last supplier invoice" id={row.lastSupplierInvoiceHeaderId} />
          <DocRef label="Last payment" id={row.lastPaymentId} />
        </div>
      </section>
    </div>
  );
}

function Tile({ label, value, emphasize }: { label: string; value: string; emphasize?: boolean }) {
  return (
    <div className={emphasize ? "rounded-md border border-brand-primary/30 bg-brand-primary-soft px-4 py-3" : "rounded-md border border-border-default bg-bg-elevated px-4 py-3"}>
      <div className="text-[11px] font-medium uppercase tracking-wider text-text-muted">{label}</div>
      <div className="mt-1 text-lg font-semibold tabular-nums text-text-primary">{value}</div>
    </div>
  );
}

function StatusTile({ label, status }: { label: string; status: string | null }) {
  const pill = statusForOrder(status);
  return (
    <div className="rounded-md border border-border-default bg-bg-elevated px-4 py-3">
      <div className="text-[11px] font-medium uppercase tracking-wider text-text-muted">{label}</div>
      <div className="mt-1.5"><StatusPill label={pill.label} tone={pill.tone} /></div>
    </div>
  );
}

function DocRef({ label, id }: { label: string; id: string | null }) {
  return (
    <div className="rounded-md border border-border-default bg-bg-elevated px-4 py-2">
      <div className="text-[11px] font-medium uppercase tracking-wider text-text-muted">{label}</div>
      <div className="mt-1 font-mono text-xs text-text-secondary">{id ? shortUuid(id) : "—"}</div>
    </div>
  );
}

function formatMoney(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatMoneyC(v: string | null | undefined, currency: string): string {
  if (v == null) return "—";
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return `${currency} ${n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatRelative(iso: string | null): string {
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

function shortUuid(uuid: string): string {
  return uuid ? `${uuid.slice(0, 8)}…${uuid.slice(-4)}` : "—";
}
