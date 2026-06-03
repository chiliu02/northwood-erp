import { useQuery } from "@tanstack/react-query";
import { Plus, Filter, Download } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { FilterPanel, useFieldFilters, type FilterField } from "@/components/ui/FilterPanel";
import { downloadCsv } from "@/lib/csv";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";

/** Mirror of {@code reporting.sales_order_360_view}. */
interface SalesOrderRow {
  salesOrderHeaderId: string;
  orderNumber: string;
  customerId: string;
  customerName: string;
  orderDate: string;
  orderStatus: string;
  stockStatus: string;
  manufacturingStatus: string;
  shipmentStatus: string;
  invoiceStatus: string;
  paymentStatus: string;
  paymentTerms: string;
  currencyCode: string;
  totalAmount: string;
  outstandingAmount: string;
  updatedAt: string;
}

/**
 * The one fully-wired route in C0 — establishes the look-and-feel
 * reference. Reads from `GET /api/sales-orders` (proxied through the
 * ERP BFF to reporting-service's projection).
 */
export function SalesOrders() {
  const navigate = useNavigate();
  const { data, isLoading, error } = useQuery({
    queryKey: ["sales-orders"],
    queryFn: () => apiGet<SalesOrderRow[]>("/api/sales-orders"),
  });

  const filterFields: FilterField<SalesOrderRow>[] = [
    { key: "orderNumber", label: "Order #", get: (r) => r.orderNumber },
    { key: "customer", label: "Customer", get: (r) => r.customerName },
    { key: "status", label: "Status", type: "select", get: (r) => r.orderStatus },
    { key: "terms", label: "Terms", type: "select", get: (r) => r.paymentTerms },
  ];
  const filter = useFieldFilters(data ?? [], filterFields);

  const columns: Column<SalesOrderRow>[] = [
    {
      key: "orderNumber",
      header: "Order #",
      width: "140px",
      sortAccessor: (r) => r.orderNumber,
      render: (r) => <span className="font-medium tabular-nums">{r.orderNumber}</span>,
    },
    {
      key: "customer",
      header: "Customer",
      sortAccessor: (r) => r.customerName,
      render: (r) => <span>{r.customerName}</span>,
    },
    {
      key: "status",
      header: "Status",
      width: "180px",
      sortAccessor: (r) => r.orderStatus,
      render: (r) => {
        const s = statusForOrder(r.orderStatus);
        return (
          <div className="flex items-center gap-1.5">
            <StatusPill label={s.label} tone={s.tone} />
            {isAwaitingPrepayment(r) && <StatusPill label="awaiting prepayment" tone="warn" />}
            {isAwaitingDeposit(r) && <StatusPill label="awaiting deposit" tone="warn" />}
          </div>
        );
      },
    },
    {
      key: "paymentTerms",
      header: "Terms",
      width: "120px",
      sortAccessor: (r) => r.paymentTerms,
      render: (r) => <span className="font-mono text-xs text-text-muted">{r.paymentTerms}</span>,
    },
    {
      key: "fulfilment",
      header: "Fulfilment",
      width: "180px",
      sortAccessor: (r) => fulfilmentReachedCount(r),
      render: (r) => <FulfilmentSummary row={r} />,
    },
    {
      key: "total",
      header: "Total",
      numeric: true,
      width: "120px",
      sortAccessor: (r) => Number(r.totalAmount),
      render: (r) => (
        <span>
          {formatMoney(r.totalAmount)} <span className="text-text-faint">{r.currencyCode}</span>
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
          {formatMoney(r.outstandingAmount)}
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
    <div className="flex h-full flex-col">
      <PageHeader
        title="Sales Orders"
        trail={[
          { label: "Home", to: "/" },
          { label: "Sales", to: "/sales-orders" },
          { label: "Orders" },
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
              onClick={() => downloadCsv("sales-orders.csv", filter.filtered)}
              disabled={filter.filtered.length === 0}
            >
              Export
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Plus className="h-4 w-4" />}
              requiresRole="sales_clerk"
              onClick={() => navigate("/sales-orders/new")}
            >
              New Order
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

      <div className="flex-1 px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load sales orders: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={filter.filtered}
            rowKey={(r) => r.salesOrderHeaderId}
            onRowClick={(r) => navigate(`/sales-orders/${r.salesOrderHeaderId}`)}
            loading={isLoading}
            emptyState={filter.active ? "No orders match the filter." : "No sales orders yet. Click 'New Order' to place one."}
          />
        )}

        {data && (
          <div className="mt-3 text-xs text-text-muted">
            Showing {filter.filtered.length} {filter.filtered.length === 1 ? "order" : "orders"}
            {filter.active ? ` (filtered from ${data.length})` : ""}.
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * Compact dot-and-label summary of where the order sits in the saga
 * progression. Each stage is a tiny dot; filled if reached, hollow if
 * pending. Concrete demo of the cross-context projection — the dots
 * come from five different inbox handlers.
 */
function fulfilmentStages(row: SalesOrderRow): boolean[] {
  return [
    row.stockStatus === "reserved" || isPast(row, "reserved"),
    ["completed", "in_progress"].includes(row.manufacturingStatus) || isPast(row, "manufacturing"),
    ["shipped", "completed"].includes(row.shipmentStatus),
    ["invoiced", "partially_invoiced"].includes(row.invoiceStatus),
    ["paid", "partially_paid"].includes(row.paymentStatus),
  ];
}

/** How many of the five fulfilment stages this order has reached (sort key). */
function fulfilmentReachedCount(row: SalesOrderRow): number {
  return fulfilmentStages(row).filter(Boolean).length;
}

function FulfilmentSummary({ row }: { row: SalesOrderRow }) {
  const stages = fulfilmentStages(row);
  return (
    <div className="flex items-center gap-1">
      {stages.map((reached, i) => (
        <span
          key={i}
          className={
            reached
              ? "h-2 w-2 rounded-full bg-status-success"
              : "h-2 w-2 rounded-full border border-border-strong"
          }
        />
      ))}
      <span className="ml-2 text-[11px] text-text-muted">
        {stages.filter(Boolean).length}/5
      </span>
    </div>
  );
}

// Lozenge for prepayment orders that haven't been paid yet.
// Goes away once the customer pays — same observable as the demo SPA.
function isAwaitingPrepayment(r: SalesOrderRow): boolean {
  return r.paymentTerms === "prepayment" && r.paymentStatus !== "paid";
}

// A deposit order awaiting its up-front deposit (nothing paid yet);
// clears once the deposit lands (partially_paid).
function isAwaitingDeposit(r: SalesOrderRow): boolean {
  return r.paymentTerms === "deposit"
    && r.paymentStatus !== "paid" && r.paymentStatus !== "partially_paid";
}

function isPast(row: SalesOrderRow, after: string): boolean {
  // Coarse-grained "any later stage reached implies this one is too" check.
  if (after === "reserved") {
    return ["in_progress", "completed"].includes(row.manufacturingStatus)
      || ["shipped", "completed"].includes(row.shipmentStatus)
      || ["invoiced", "partially_invoiced", "paid", "partially_paid"].includes(row.invoiceStatus);
  }
  if (after === "manufacturing") {
    return ["shipped", "completed"].includes(row.shipmentStatus);
  }
  return false;
}

function formatMoney(amount: string | number | null | undefined): string {
  if (amount == null) return "—";
  const n = typeof amount === "string" ? Number(amount) : amount;
  if (Number.isNaN(n)) return String(amount);
  return n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
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
  const day = Math.floor(hr / 24);
  return `${day}d ago`;
}
