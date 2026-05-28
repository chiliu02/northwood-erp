import { useQuery } from "@tanstack/react-query";
import { Plus, Filter, Download } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
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

  const columns: Column<SalesOrderRow>[] = [
    {
      key: "orderNumber",
      header: "Order #",
      width: "140px",
      render: (r) => <span className="font-medium tabular-nums">{r.orderNumber}</span>,
    },
    {
      key: "customer",
      header: "Customer",
      render: (r) => <span>{r.customerName}</span>,
    },
    {
      key: "status",
      header: "Status",
      width: "140px",
      render: (r) => {
        const s = statusForOrder(r.orderStatus);
        return <StatusPill label={s.label} tone={s.tone} />;
      },
    },
    {
      key: "paymentTerms",
      header: "Terms",
      width: "120px",
      render: (r) => <span className="font-mono text-xs text-text-muted">{r.paymentTerms}</span>,
    },
    {
      key: "fulfilment",
      header: "Fulfilment",
      width: "180px",
      render: (r) => <FulfilmentSummary row={r} />,
    },
    {
      key: "total",
      header: "Total",
      numeric: true,
      width: "120px",
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
            <ActionButton icon={<Filter className="h-4 w-4" />}>Filter</ActionButton>
            <ActionButton icon={<Download className="h-4 w-4" />}>Export</ActionButton>
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

      <div className="flex-1 px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load sales orders: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(r) => r.salesOrderHeaderId}
            onRowClick={(r) => navigate(`/sales-orders/${r.salesOrderHeaderId}`)}
            loading={isLoading}
            emptyState="No sales orders yet. Click 'New Order' to place one."
          />
        )}

        {data && (
          <div className="mt-3 text-xs text-text-muted">
            Showing {data.length} {data.length === 1 ? "order" : "orders"}.
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
function FulfilmentSummary({ row }: { row: SalesOrderRow }) {
  const stages = [
    { reached: row.stockStatus === "reserved" || isPast(row, "reserved") },
    { reached: ["completed", "in_progress"].includes(row.manufacturingStatus) || isPast(row, "manufacturing") },
    { reached: ["shipped", "completed"].includes(row.shipmentStatus) },
    { reached: ["invoiced", "partially_invoiced"].includes(row.invoiceStatus) },
    { reached: ["paid", "partially_paid"].includes(row.paymentStatus) },
  ];
  return (
    <div className="flex items-center gap-1">
      {stages.map((s, i) => (
        <span
          key={i}
          className={
            s.reached
              ? "h-2 w-2 rounded-full bg-status-success"
              : "h-2 w-2 rounded-full border border-border-strong"
          }
        />
      ))}
      <span className="ml-2 text-[11px] text-text-muted">
        {stages.filter((s) => s.reached).length}/5
      </span>
    </div>
  );
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
