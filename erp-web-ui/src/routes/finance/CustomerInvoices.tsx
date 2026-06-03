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

interface CustomerInvoiceRow {
  id: string;
  invoiceNumber: string;
  salesOrderHeaderId: string;
  customerName: string;
  currencyCode: string;
  subtotalAmount: string;
  taxAmount: string;
  totalAmount: string;
  status: string;
  version: number;
}

/**
 * Customer invoices list. Auto-generated from sales-order shipments —
 * no creation form here. Drilling in shows lines + payment progress
 * (when /payments link to invoices).
 */
export function CustomerInvoices() {
  const navigate = useNavigate();
  const { data, isLoading, error } = useQuery({
    queryKey: ["customer-invoices"],
    queryFn: () => apiGet<CustomerInvoiceRow[]>("/api/customer-invoices"),
  });

  const filterFields: FilterField<CustomerInvoiceRow>[] = [
    { key: "number", label: "Invoice #", get: (r) => r.invoiceNumber },
    { key: "customer", label: "Customer", get: (r) => r.customerName },
    { key: "status", label: "Status", type: "select", get: (r) => r.status },
  ];
  const filter = useFieldFilters(data ?? [], filterFields);

  const columns: Column<CustomerInvoiceRow>[] = [
    {
      key: "number",
      header: "Invoice #",
      width: "160px",
      sortAccessor: (r) => r.invoiceNumber,
      render: (r) => <span className="font-medium tabular-nums">{r.invoiceNumber}</span>,
    },
    { key: "customer", header: "Customer", sortAccessor: (r) => r.customerName, render: (r) => r.customerName },
    {
      key: "status",
      header: "Status",
      width: "120px",
      sortAccessor: (r) => r.status,
      render: (r) => {
        const s = statusForOrder(r.status);
        return <StatusPill label={s.label} tone={s.tone} />;
      },
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
          <strong>{formatMoney(r.totalAmount)}</strong> <span className="text-text-faint">{r.currencyCode}</span>
        </span>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Customer Invoices"
        description="Auto-generated from each shipment. Olivia tracks AR here; no manual creation path — invoice creation is driven by sales.SalesOrderShipped."
        trail={[
          { label: "Finance" },
          { label: "Customer Invoices" },
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
              onClick={() => downloadCsv("customer-invoices.csv", filter.filtered)}
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
            Failed to load customer invoices: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={filter.filtered}
            rowKey={(r) => r.id}
            onRowClick={(r) => navigate(`/sales-orders/${r.salesOrderHeaderId}`)}
            loading={isLoading}
            emptyState={
              filter.active ? (
                <span>No invoices match the filter.</span>
              ) : (
                <span>
                  No invoices yet. Customer invoices appear when a sales order is shipped via{" "}
                  <code>POST /api/shipments</code>.
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
