import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";

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
  currencyCode: string;
  totalAmount: string;
  outstandingAmount: string;
  updatedAt: string;
}

/**
 * Sales-order 360 dashboard — every order with the full saga-state
 * roll-up: order / stock / manufacturing / shipment / invoice / payment.
 * Reads from {@code reporting.sales_order_360_view} via the same endpoint
 * that {@link SalesOrders} uses; the difference is the visible columns.
 */
export function SalesOrder360Dashboard() {
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
      key: "order",
      header: "Order",
      width: "130px",
      render: (r) => {
        const s = statusForOrder(r.orderStatus);
        return <StatusPill label={s.label} tone={s.tone} />;
      },
    },
    {
      key: "stock",
      header: "Stock",
      width: "130px",
      render: (r) => <span className="text-text-muted">{r.stockStatus.replace(/_/g, " ")}</span>,
    },
    {
      key: "mfg",
      header: "Manufacturing",
      width: "150px",
      render: (r) => <span className="text-text-muted">{r.manufacturingStatus.replace(/_/g, " ")}</span>,
    },
    {
      key: "shipment",
      header: "Shipment",
      width: "130px",
      render: (r) => <span className="text-text-muted">{r.shipmentStatus.replace(/_/g, " ")}</span>,
    },
    {
      key: "invoice",
      header: "Invoice",
      width: "120px",
      render: (r) => <span className="text-text-muted">{r.invoiceStatus.replace(/_/g, " ")}</span>,
    },
    {
      key: "payment",
      header: "Payment",
      width: "120px",
      render: (r) => <span className="text-text-muted">{r.paymentStatus.replace(/_/g, " ")}</span>,
    },
    {
      key: "total",
      header: "Total",
      width: "140px",
      numeric: true,
      render: (r) => <span className="tabular-nums">{formatMoney(r.totalAmount)} {r.currencyCode}</span>,
    },
    {
      key: "outstanding",
      header: "Outstanding",
      width: "140px",
      numeric: true,
      render: (r) => {
        const n = Number(r.outstandingAmount);
        const cls = n > 0 ? "text-status-warning" : "text-text-muted";
        return <span className={`tabular-nums ${cls}`}>{formatMoney(r.outstandingAmount)}</span>;
      },
    },
  ];

  return (
    <>
      <PageHeader
        title="Sales Order 360"
        description="Cross-context status roll-up per sales order. The five sub-status columns track each saga step end to end; click into a row for full detail."
        trail={[
          { label: "Home", to: "/" },
          { label: "Sales" },
          { label: "360 View" },
        ]}
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load 360 view: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(r) => r.salesOrderHeaderId}
            onRowClick={(r) => navigate(`/sales-orders/${r.salesOrderHeaderId}`)}
            loading={isLoading}
            emptyState="No sales orders."
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {data.length} order{data.length === 1 ? "" : "s"}.
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
