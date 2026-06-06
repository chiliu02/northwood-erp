import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Ban, History, Pencil, Plus, Trash2, Check, X } from "lucide-react";
import { apiGet, apiPost, apiPatch, apiDelete, ApiError } from "@/lib/api";
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
  productId: string;
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
  version: number;
  lines: SalesOrderLine[];
}

interface Product {
  productId: string;
  sku: string;
  name: string;
  salesPrice: string;
  status: string;
}

const NON_CANCELLABLE = ["shipped", "completed", "cancelled", "rejected"];

/**
 * Sales-order detail. Header/status/money read from the 360 projection on
 * reporting via /api/sales-orders/{id}/360; the Lines tab reads the owning
 * aggregate via the /api/sales-cmd alias (reporting's 360 is header-level only
 * — it carries no line rows).
 *
 * <p>The Lines tab is editable while the order is pre-reservation (line
 * amendment, §1G slice A): add / change quantity / remove (sales_clerk) and
 * change price (sales_manager), each routed through /api/sales-cmd with an
 * If-Match version for optimistic concurrency. The server is the source of
 * truth — it returns 409 once the amendable window has closed.
 */
export function SalesOrderDetail() {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [cancelDialog, setCancelDialog] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [amending, setAmending] = useState(false);
  // Per-line edit buffers (seeded from the server values) + the add-line draft.
  const [edits, setEdits] = useState<Record<string, { qty: string; price: string }>>({});
  const [addDraft, setAddDraft] = useState<{ productId: string; quantity: string; price: string } | null>(null);

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

  const { data: products } = useQuery({
    queryKey: ["products"],
    queryFn: () => apiGet<Product[]>("/api/products"),
    enabled: amending,
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

  function ifMatch(): Record<string, string> {
    return aggregate ? { "If-Match": String(aggregate.version) } : {};
  }

  function onAmendSuccess(message: string) {
    queryClient.invalidateQueries({ queryKey: ["sales-order-aggregate", id] });
    queryClient.invalidateQueries({ queryKey: ["sales-order-360", id] });
    queryClient.invalidateQueries({ queryKey: ["sales-orders"] });
    toast.success(message);
  }

  function onAmendError(e: unknown) {
    toast.error(`Amendment failed: ${e instanceof ApiError ? e.message : String(e)}`);
  }

  const changeQtyMutation = useMutation({
    mutationFn: (v: { lineId: string; quantity: string }) =>
      apiPatch(`/api/sales-cmd/sales-orders/${id}/lines/${v.lineId}`, { orderedQuantity: v.quantity }, ifMatch()),
    onSuccess: () => onAmendSuccess("Line quantity updated."),
    onError: onAmendError,
  });

  const changePriceMutation = useMutation({
    mutationFn: (v: { lineId: string; price: string }) =>
      apiPatch(`/api/sales-cmd/sales-orders/${id}/lines/${v.lineId}/price`, { unitPrice: v.price }, ifMatch()),
    onSuccess: () => onAmendSuccess("Line price updated."),
    onError: onAmendError,
  });

  const removeLineMutation = useMutation({
    mutationFn: (lineId: string) =>
      apiDelete(`/api/sales-cmd/sales-orders/${id}/lines/${lineId}`, ifMatch()),
    onSuccess: () => onAmendSuccess("Line removed."),
    onError: onAmendError,
  });

  const addLineMutation = useMutation({
    mutationFn: (v: { productId: string; productSku: string; productName: string; quantity: string; price: string }) =>
      apiPost(`/api/sales-cmd/sales-orders/${id}/lines`, {
        productId: v.productId,
        productSku: v.productSku,
        productName: v.productName,
        orderedQuantity: v.quantity,
        unitPrice: v.price,
        taxRate: "0",
      }, ifMatch()),
    onSuccess: () => {
      onAmendSuccess("Line added.");
      setAddDraft(null);
    },
    onError: onAmendError,
  });

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
  // "awaiting prepayment/deposit" only applies while the order is live. A
  // terminal order (cancelled → refunded, rejected, completed) is no longer
  // waiting on the up-front payment.
  const terminal = ["cancelled", "rejected", "completed"].includes(data.orderStatus);
  const lines = aggregate?.lines ?? [];
  const activeLines = lines.filter((l) => l.lineStatus !== "cancelled");

  // Client-side proxy for the server's amendable window: order not
  // terminal/shipped, and not parked on a material shortage (amending a
  // shortage-parked order is a later slice). A fully-reserved order is
  // amendable — inventory reconciles the change incrementally. The server is
  // authoritative and returns 409 if this is stale.
  const amendable = !NON_CANCELLABLE.includes(data.orderStatus) && !data.hasShortage;
  const notAmendableReason = NON_CANCELLABLE.includes(data.orderStatus)
    ? `Order is ${data.orderStatus}; lines can no longer be amended.`
    : data.hasShortage
      ? "This order has a material shortage and is awaiting replenishment; lines can't be amended while it's parked."
      : null;

  const amendBusy = changeQtyMutation.isPending || changePriceMutation.isPending
    || removeLineMutation.isPending || addLineMutation.isPending;

  const sellableProducts = (products ?? []).filter((p) => p.status === "active");

  function draftFor(line: SalesOrderLine) {
    return edits[line.lineId] ?? { qty: line.orderedQuantity, price: line.unitPrice };
  }
  function setDraft(lineId: string, patch: Partial<{ qty: string; price: string }>) {
    setEdits((prev) => ({ ...prev, [lineId]: { ...(prev[lineId] ?? { qty: "", price: "" }), ...patch } }));
  }
  function startAmending() {
    setEdits({});
    setAddDraft(null);
    setAmending(true);
  }

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

  const linesTab = amending ? (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <p className="text-xs text-text-muted">
          Change quantities or remove lines (sales clerk); override a unit price (sales manager). Each change
          is applied immediately with optimistic concurrency.
        </p>
        <ActionButton icon={<X className="h-4 w-4" />} onClick={() => setAmending(false)} disabled={amendBusy}>
          Done
        </ActionButton>
      </div>
      <table className="w-full text-sm">
        <thead className="border-b border-border-default text-left text-[11px] uppercase tracking-wider text-text-muted">
          <tr>
            <th className="py-2 pr-3 font-semibold">#</th>
            <th className="py-2 pr-3 font-semibold">Product</th>
            <th className="py-2 pr-3 text-right font-semibold">Quantity</th>
            <th className="py-2 pr-3 text-right font-semibold">Unit price</th>
            <th className="py-2 font-semibold"></th>
          </tr>
        </thead>
        <tbody>
          {activeLines.map((line) => {
            const d = draftFor(line);
            const qtyChanged = d.qty !== line.orderedQuantity && Number(d.qty) > 0;
            const priceChanged = d.price !== line.unitPrice && Number(d.price) >= 0;
            return (
              <tr key={line.lineId} className="border-b border-border-default last:border-b-0">
                <td className="py-2 pr-3 tabular-nums text-text-muted">{line.lineNumber}</td>
                <td className="py-2 pr-3">
                  <span className="font-medium tabular-nums">{line.productSku}</span>
                  <span className="text-text-muted"> · {line.productName}</span>
                </td>
                <td className="py-2 pr-3">
                  <div className="flex items-center justify-end gap-1.5">
                    <input
                      type="number" step="0.01" min="0.01"
                      value={d.qty}
                      onChange={(e) => setDraft(line.lineId, { qty: e.target.value })}
                      className="h-9 w-24 rounded-md border border-border-default bg-bg-surface px-2 text-right text-sm focus:border-border-focus focus:outline-none"
                    />
                    <ActionButton
                      variant="ghost"
                      requiresRole="sales_clerk"
                      disabled={!qtyChanged || amendBusy}
                      onClick={() => changeQtyMutation.mutate({ lineId: line.lineId, quantity: d.qty })}
                      title="Update quantity"
                    >
                      <Check className="h-3.5 w-3.5" />
                    </ActionButton>
                  </div>
                </td>
                <td className="py-2 pr-3">
                  <div className="flex items-center justify-end gap-1.5">
                    <input
                      type="number" step="0.01" min="0"
                      value={d.price}
                      onChange={(e) => setDraft(line.lineId, { price: e.target.value })}
                      className="h-9 w-24 rounded-md border border-border-default bg-bg-surface px-2 text-right text-sm focus:border-border-focus focus:outline-none"
                    />
                    <ActionButton
                      variant="ghost"
                      requiresRole="sales_manager"
                      requiresRoleHint="Only a sales manager can override a line's unit price."
                      disabled={!priceChanged || amendBusy}
                      onClick={() => changePriceMutation.mutate({ lineId: line.lineId, price: d.price })}
                      title="Update unit price"
                    >
                      <Check className="h-3.5 w-3.5" />
                    </ActionButton>
                  </div>
                </td>
                <td className="py-2 text-right">
                  <ActionButton
                    variant="ghost"
                    requiresRole="sales_clerk"
                    disabled={activeLines.length <= 1 || amendBusy}
                    onClick={() => removeLineMutation.mutate(line.lineId)}
                    title={activeLines.length <= 1 ? "Cannot remove the last line — cancel the order instead" : "Remove line"}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </ActionButton>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {addDraft ? (
        <div className="flex items-end gap-2 rounded-md border border-border-default bg-bg-subtle px-3 py-3">
          <div className="flex-1">
          <Field label="Product">
            <select
              value={addDraft.productId}
              onChange={(e) => {
                const p = sellableProducts.find((x) => x.productId === e.target.value);
                setAddDraft({ ...addDraft, productId: e.target.value, price: p?.salesPrice ?? addDraft.price });
              }}
              className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-2 text-sm focus:border-border-focus focus:outline-none"
            >
              <option value="">— pick a product —</option>
              {sellableProducts.map((p) => (
                <option key={p.productId} value={p.productId}>{p.sku} · {p.name}</option>
              ))}
            </select>
          </Field>
          </div>
          <Field label="Qty">
            <input
              type="number" step="0.01" min="0.01"
              value={addDraft.quantity}
              onChange={(e) => setAddDraft({ ...addDraft, quantity: e.target.value })}
              className="h-9 w-24 rounded-md border border-border-default bg-bg-surface px-2 text-right text-sm focus:border-border-focus focus:outline-none"
            />
          </Field>
          <Field label="Unit price">
            <input
              type="number" step="0.01" min="0"
              value={addDraft.price}
              onChange={(e) => setAddDraft({ ...addDraft, price: e.target.value })}
              className="h-9 w-28 rounded-md border border-border-default bg-bg-surface px-2 text-right text-sm focus:border-border-focus focus:outline-none"
            />
          </Field>
          <ActionButton
            variant="primary"
            requiresRole="sales_clerk"
            disabled={!addDraft.productId || !(Number(addDraft.quantity) > 0) || amendBusy}
            onClick={() => {
              const p = sellableProducts.find((x) => x.productId === addDraft.productId);
              if (!p) return;
              addLineMutation.mutate({
                productId: p.productId, productSku: p.sku, productName: p.name,
                quantity: addDraft.quantity, price: addDraft.price,
              });
            }}
          >
            Add
          </ActionButton>
          <ActionButton variant="ghost" onClick={() => setAddDraft(null)} disabled={amendBusy}>
            <X className="h-3.5 w-3.5" />
          </ActionButton>
        </div>
      ) : (
        <ActionButton
          icon={<Plus className="h-4 w-4" />}
          requiresRole="sales_clerk"
          onClick={() => setAddDraft({ productId: "", quantity: "1", price: "0" })}
        >
          Add line
        </ActionButton>
      )}
    </div>
  ) : (
    <div className="space-y-3">
      <div className="flex items-center justify-end">
        {amendable ? (
          <ActionButton
            icon={<Pencil className="h-4 w-4" />}
            requiresRole="sales_clerk"
            onClick={startAmending}
          >
            Amend lines
          </ActionButton>
        ) : (
          notAmendableReason && (
            <span className="text-xs text-text-faint">{notAmendableReason}</span>
          )
        )}
      </div>
      <DataGrid
        columns={lineColumns}
        rows={lines}
        rowKey={(l) => l.lineId}
        emptyState={<span>No lines on this order.</span>}
      />
    </div>
  );

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
                requiresRoleHint="For demo purposes, only a sales manager can cancel an order."
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
                      {!terminal && data.paymentTerms === "prepayment" && data.paymentStatus !== "paid" && (
                        <StatusPill label="awaiting prepayment" tone="warn" />
                      )}
                      {!terminal && data.paymentTerms === "deposit" && data.paymentStatus !== "paid"
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
            badge: activeLines.length || undefined,
            content: linesTab,
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
