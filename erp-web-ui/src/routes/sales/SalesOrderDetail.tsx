import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Ban, History } from "lucide-react";
import { apiGet, apiPost, ApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { DetailLayout } from "@/components/ui/DetailLayout";
import { ActionButton } from "@/components/ui/ActionButton";
import { AuditTab } from "@/components/ui/AuditTab";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { FormSection, ReadOnlyField, Field } from "@/components/ui/FormSection";
import { TextArea } from "@/components/ui/Form";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";
import { DataGrid, type Column } from "@/components/ui/DataGrid";

interface SalesOrder360 {
  salesOrderHeaderId: string;
  orderNumber: string;
  customerId: string;
  customerName: string;
  orderDate: string;
  requestedDeliveryDate: string;
  orderStatus: string;
  stockStatus: string;
  manufacturingStatus: string;
  shipmentStatus: string;
  invoiceStatus: string;
  paymentStatus: string;
  paymentTerms: string;
  currencyCode: string;
  totalAmount: string;
  invoicedAmount: string;
  paidAmount: string;
  outstandingAmount: string;
  hasShortage: boolean;
  shortageSummary: string | null;
  lastEventType: string | null;
  lastEventAt: string | null;
  updatedAt: string;
}

interface SalesOrderLine {
  lineId: string;
  lineNumber: number;
  productSku: string;
  productName: string;
  orderedQuantity: string;
  reservedQuantity: string;
  unitPrice: string;
  lineTotal: string;
  lineStatus: string;
}

interface SalesOrderAggregate {
  id: string;
  orderNumber: string;
  requestedDeliveryDate: string | null;
  currencyCode: string;
  lines: SalesOrderLine[];
}

const NON_CANCELLABLE = ["shipped", "completed", "cancelled", "rejected"];

/**
 * Sales-order detail. Header/status/money read from the 360 projection on
 * reporting via /api/sales-orders/{id}/360; the read-only Lines tab reads the
 * owning aggregate via the /api/sales-cmd alias (reporting's 360 is
 * header-level only — it carries no line rows). Cancel button only when status
 * is still cancellable (not past goods_shipped) — server enforces this with 409
 * regardless. Posts to /api/sales-cmd/{id}/cancel via the BFF alias.
 */
export function SalesOrderDetail() {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [cancelDialog, setCancelDialog] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading, error: fetchError } = useQuery({
    queryKey: ["sales-order-360", id],
    queryFn: () => apiGet<SalesOrder360>(`/api/sales-orders/${id}/360`),
    enabled: !!id,
  });

  // Lines come from the owning aggregate, not the 360 projection (which is
  // header-level). The -cmd alias routes to sales-service; /api/sales-orders
  // without -cmd routes to reporting.
  const { data: aggregate } = useQuery({
    queryKey: ["sales-order-aggregate", id],
    queryFn: () => apiGet<SalesOrderAggregate>(`/api/sales-cmd/sales-orders/${id}`),
    enabled: !!id,
  });

  const cancelMutation = useMutation({
    mutationFn: () => apiPost(`/api/sales-cmd/sales-orders/${id}/cancel`, {
      reason: reason.trim(),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["sales-order-360", id] });
      queryClient.invalidateQueries({ queryKey: ["sales-orders"] });
      toast.success(`Order ${data?.orderNumber} cancellation requested. Saga will compensate inventory + manufacturing.`);
      setCancelDialog(false);
      setReason("");
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : "Cancel failed."),
  });

  function open() {
    setReason("");
    setError(null);
    setCancelDialog(true);
  }

  if (isLoading) {
    return <div className="flex h-full items-center justify-center text-sm text-text-muted">Loading order…</div>;
  }
  if (fetchError || !data) {
    return (
      <div className="px-8 py-12">
        <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
          Failed to load: {fetchError instanceof Error ? fetchError.message : "Not found"}
        </div>
      </div>
    );
  }

  const status = statusForOrder(data.orderStatus);
  const cancellable = !NON_CANCELLABLE.includes(data.orderStatus);
  const lines = aggregate?.lines ?? [];

  const lineColumns: Column<SalesOrderLine>[] = [
    { key: "ln", header: "#", width: "40px", numeric: true, render: (l) => l.lineNumber },
    { key: "sku", header: "SKU", width: "180px", render: (l) => <span className="font-medium tabular-nums">{l.productSku}</span> },
    { key: "name", header: "Product", render: (l) => l.productName },
    { key: "qty", header: "Qty", numeric: true, width: "90px", render: (l) => formatQty(l.orderedQuantity) },
    { key: "reserved", header: "Reserved", numeric: true, width: "100px", render: (l) => formatQty(l.reservedQuantity) },
    { key: "price", header: "Unit Price", numeric: true, width: "110px", render: (l) => formatMoney(l.unitPrice) },
    { key: "total", header: "Line Total", numeric: true, width: "120px", render: (l) => <strong>{formatMoney(l.lineTotal)}</strong> },
    { key: "status", header: "Status", width: "120px", render: (l) => <StatusPill label={l.lineStatus} tone={toneFor(l.lineStatus)} /> },
  ];

  return (
    <>
      <DetailLayout
        trail={[
          { label: "Sales" },
          { label: "Orders", to: "/sales-orders" },
          { label: data.orderNumber },
        ]}
        title={data.orderNumber}
        subtitle={`${data.customerName} — ${data.orderDate}`}
        status={status}
        actions={
          <>
            <Link
              to={`/system/audit-log?aggregateId=${data.salesOrderHeaderId}`}
              className="inline-flex h-9 items-center gap-1.5 rounded-md border border-border-default bg-bg-surface px-3 text-sm font-medium text-text-secondary hover:bg-bg-subtle"
              title="View audit log for this sales order"
            >
              <History className="h-4 w-4" />
              View audit
            </Link>
            {cancellable && (
              <ActionButton
                variant="danger"
                icon={<Ban className="h-4 w-4" />}
                onClick={open}
                requiresRole="sales_manager"
              >
                Cancel order
              </ActionButton>
            )}
          </>
        }
        tabs={[
          {
            key: "overview",
            label: "Overview",
            content: (
              <div className="grid gap-4 lg:grid-cols-2">
                <FormSection title="Order">
                  <ReadOnlyField label="Order #" value={<span className="font-medium tabular-nums">{data.orderNumber}</span>} />
                  <ReadOnlyField label="Status" value={<StatusPill label={status.label} tone={status.tone} />} />
                  <ReadOnlyField label="Customer" value={data.customerName} />
                  <ReadOnlyField label="Currency" value={data.currencyCode} />
                  <ReadOnlyField label="Order date" value={data.orderDate} />
                  <ReadOnlyField label="Requested delivery" value={aggregate?.requestedDeliveryDate || data.requestedDeliveryDate || "—"} />
                  <ReadOnlyField label="Payment terms" value={
                    <div className="flex items-center gap-2">
                      <span className="font-mono text-xs">{data.paymentTerms}</span>
                      {data.paymentTerms === "prepayment" && data.paymentStatus !== "paid" && (
                        <StatusPill label="awaiting prepayment" tone="warn" />
                      )}
                      {data.paymentTerms === "deposit" && data.paymentStatus !== "paid"
                        && data.paymentStatus !== "partially_paid" && (
                        <StatusPill label="awaiting deposit" tone="warn" />
                      )}
                    </div>
                  } />
                </FormSection>
                <FormSection title="Fulfilment progress">
                  <ReadOnlyField label="Stock" value={<StatusPill label={data.stockStatus || "pending"} tone={toneFor(data.stockStatus)} />} />
                  <ReadOnlyField label="Manufacturing" value={<StatusPill label={data.manufacturingStatus || "pending"} tone={toneFor(data.manufacturingStatus)} />} />
                  <ReadOnlyField label="Shipment" value={<StatusPill label={data.shipmentStatus || "pending"} tone={toneFor(data.shipmentStatus)} />} />
                  <ReadOnlyField label="Invoice" value={<StatusPill label={data.invoiceStatus || "pending"} tone={toneFor(data.invoiceStatus)} />} />
                  <ReadOnlyField label="Payment" value={<StatusPill label={data.paymentStatus || "pending"} tone={toneFor(data.paymentStatus)} />} fullWidth />
                </FormSection>
                <FormSection title="Money" columns={3} className="lg:col-span-2">
                  <ReadOnlyField label="Total" value={<span className="text-base font-semibold tabular-nums">{formatMoney(data.totalAmount)} {data.currencyCode}</span>} />
                  <ReadOnlyField label="Invoiced" value={<span className="tabular-nums">{formatMoney(data.invoicedAmount)}</span>} />
                  <ReadOnlyField label="Paid" value={<span className="tabular-nums">{formatMoney(data.paidAmount)}</span>} />
                  <ReadOnlyField label="Outstanding" value={
                    <span className={Number(data.outstandingAmount) > 0 ? "tabular-nums text-status-warn" : "tabular-nums"}>
                      {formatMoney(data.outstandingAmount)} {data.currencyCode}
                    </span>
                  } fullWidth />
                </FormSection>
                {data.hasShortage && (
                  <div className="rounded-md border border-status-warn/30 bg-status-warn-soft px-4 py-3 text-sm text-status-warn lg:col-span-2">
                    <strong>Material shortage:</strong> {data.shortageSummary}
                  </div>
                )}
              </div>
            ),
          },
          {
            key: "lines",
            label: "Lines",
            badge: lines.length || undefined,
            content: (
              <DataGrid
                columns={lineColumns}
                rows={lines}
                rowKey={(l) => l.lineId}
                emptyState={<span>No lines on this order.</span>}
              />
            ),
          },
          {
            key: "audit",
            label: "Audit",
            content: <AuditTab aggregateId={id} />,
          },
        ]}
      />

      <ConfirmDialog
        open={cancelDialog}
        title="Cancel sales order?"
        message={
          <>
            Cancels <strong>{data.orderNumber}</strong> for {data.customerName}.<br />
            Header flips to <code>cancelled</code> + saga to <code>compensating</code>; emits
            <code> sales.SalesOrderCancellationRequested</code>. Inventory releases the
            reservation; manufacturing cancels every active WO. When both ack the cancel,
            saga advances to <code>compensated</code>. Hard-cancel — WIP is written off.
            <br />
            Server returns 409 if the order is past <code>goods_shipped</code>.
          </>
        }
        confirmLabel="Cancel order"
        variant="danger"
        cancelLabel="Keep order"
        busy={cancelMutation.isPending}
        onCancel={() => setCancelDialog(false)}
        onConfirm={() => cancelMutation.mutate()}
        body={
          <div className="space-y-3">
            <Field label="Reason" hint="Visible in the audit log; helps reviewers understand the cancellation later.">
              <TextArea
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="e.g. customer request; duplicate order; out-of-policy SKU"
                rows={3}
                autoFocus
              />
            </Field>
            {error && (
              <div className="rounded-md border border-status-error/30 bg-status-error-soft px-3 py-2 text-xs text-status-error">
                {error}
              </div>
            )}
          </div>
        }
      />
    </>
  );
}

function toneFor(status: string | null | undefined): "info" | "success" | "warn" | "error" | "neutral" {
  if (!status) return "neutral";
  return statusForOrder(status).tone;
}

function formatMoney(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatQty(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 3 });
}
