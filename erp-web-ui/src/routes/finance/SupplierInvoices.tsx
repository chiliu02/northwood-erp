import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Plus, Filter, Download } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { FilterPanel, useFieldFilters, type FilterField } from "@/components/ui/FilterPanel";
import { downloadCsv } from "@/lib/csv";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";

interface SupplierInvoiceRow {
  id: string;
  internalInvoiceNumber: string;
  supplierInvoiceNumber: string;
  purchaseOrderHeaderId: string;
  supplierId: string;
  supplierName: string;
  currencyCode: string;
  subtotalAmount: string;
  taxAmount: string;
  totalAmount: string;
  status: string;
  matchStatus: string | null;
}

/**
 * Supplier invoices list — every recorded AP invoice, in any status.
 * Olivia tracks AP across the lifecycle (approved / paid / parked at
 * three_way_match_failed). Drilling in shows lines + match outcome.
 * The narrower {@link PendingReview} queue lives at
 * /supplier-invoices/pending-review.
 */
export function SupplierInvoices() {
  const navigate = useNavigate();
  const { data, isLoading, error } = useQuery({
    queryKey: ["supplier-invoices"],
    queryFn: () => apiGet<SupplierInvoiceRow[]>("/api/supplier-invoices"),
  });

  const filterFields: FilterField<SupplierInvoiceRow>[] = [
    { key: "number", label: "Invoice #", get: (r) => r.internalInvoiceNumber },
    { key: "supplierNumber", label: "Supplier #", get: (r) => r.supplierInvoiceNumber },
    { key: "supplier", label: "Supplier", get: (r) => r.supplierName },
    { key: "status", label: "Status", type: "select", get: (r) => r.status },
    { key: "match", label: "Match", type: "select", get: (r) => r.matchStatus ?? "" },
  ];
  const filter = useFieldFilters(data ?? [], filterFields);

  const columns: Column<SupplierInvoiceRow>[] = [
    {
      key: "number",
      header: "Invoice #",
      width: "180px",
      sortAccessor: (r) => r.internalInvoiceNumber,
      render: (r) => <span className="font-medium tabular-nums">{r.internalInvoiceNumber}</span>,
    },
    {
      key: "supplierInvoiceNumber",
      header: "Supplier #",
      width: "160px",
      sortAccessor: (r) => r.supplierInvoiceNumber,
      render: (r) => <span className="font-mono text-[12px] text-text-muted">{r.supplierInvoiceNumber}</span>,
    },
    { key: "supplier", header: "Supplier", sortAccessor: (r) => r.supplierName, render: (r) => r.supplierName },
    {
      key: "status",
      header: "Status",
      width: "160px",
      sortAccessor: (r) => r.status,
      render: (r) => {
        const s = statusForOrder(r.status);
        return <StatusPill label={s.label} tone={s.tone} />;
      },
    },
    {
      key: "match",
      header: "Match",
      width: "110px",
      sortAccessor: (r) => r.matchStatus ?? "",
      render: (r) =>
        r.matchStatus ? <span className="text-[12px] text-text-muted">{r.matchStatus}</span> : "—",
    },
    {
      key: "subtotal",
      header: "Subtotal",
      numeric: true,
      width: "120px",
      sortAccessor: (r) => Number(r.subtotalAmount),
      render: (r) => formatMoney(r.subtotalAmount),
    },
    {
      key: "tax",
      header: "Tax",
      numeric: true,
      width: "100px",
      sortAccessor: (r) => Number(r.taxAmount),
      render: (r) => formatMoney(r.taxAmount),
    },
    {
      key: "total",
      header: "Total",
      numeric: true,
      width: "130px",
      sortAccessor: (r) => Number(r.totalAmount),
      render: (r) => (
        <span>
          <strong>{formatMoney(r.totalAmount)}</strong>{" "}
          <span className="text-text-faint">{r.currencyCode}</span>
        </span>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Supplier Invoices"
        description="All AP invoices recorded against purchase orders. Drill in for lines + 3-way match outcome. See Pending Review for the variance-failed queue."
        trail={[
          { label: "Finance" },
          { label: "Supplier Invoices" },
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
              onClick={() => downloadCsv("supplier-invoices.csv", filter.filtered)}
              disabled={filter.filtered.length === 0}
            >
              Export
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Plus className="h-4 w-4" />}
              requiresRole="accountant"
              onClick={() => navigate("/supplier-invoices/new")}
            >
              Record invoice
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
            Failed to load supplier invoices: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={filter.filtered}
            rowKey={(r) => r.id}
            onRowClick={(r) => navigate(`/supplier-invoices/${r.id}`)}
            loading={isLoading}
            emptyState={
              filter.active ? (
                <span>No supplier invoices match the filter.</span>
              ) : (
                <span>
                  No supplier invoices yet. Record one via{" "}
                  <code>POST /api/supplier-invoices</code>.
                </span>
              )
            }
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {filter.filtered.length} invoice{filter.filtered.length === 1 ? "" : "s"}
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
