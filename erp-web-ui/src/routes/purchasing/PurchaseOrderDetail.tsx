import { useState } from "react";
import { useParams } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, Ban } from "lucide-react";
import { apiGet, apiPost, ApiError } from "@/lib/api";
import { DetailLayout } from "@/components/ui/DetailLayout";
import { ActionButton } from "@/components/ui/ActionButton";
import { AuditTab } from "@/components/ui/AuditTab";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { FormSection, ReadOnlyField, Field } from "@/components/ui/FormSection";
import { TextArea } from "@/components/ui/Form";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";
import { DataGrid, type Column } from "@/components/ui/DataGrid";

interface PoLine {
  lineId: string;
  lineNumber: number;
  productSku: string;
  productName: string;
  orderedQuantity: string;
  unitPrice: string;
  lineTotal: string;
  status: string;
}

interface Po {
  id: string;
  purchaseOrderNumber: string;
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  purchaseRequisitionHeaderId: string;
  purchaseRequisitionNumber: string | null;
  currencyCode: string;
  subtotalAmount: string;
  taxAmount: string;
  totalAmount: string;
  status: string;
  version: number;
  lines: PoLine[];
}

/**
 * PO detail. Reads from /api/purchase-orders-cmd/{id} (the alias to the
 * owning service) so we get the full aggregate, not just the projection
 * row. Approve action is visible only when status='draft' — demos the
 * PO draft/approve workflow.
 */
export function PurchaseOrderDetail() {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [approveDialog, setApproveDialog] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [rejectDialog, setRejectDialog] = useState(false);
  const [rejectReason, setRejectReason] = useState("");
  const [rejectError, setRejectError] = useState<string | null>(null);

  const { data, isLoading, error: fetchError } = useQuery({
    queryKey: ["po-aggregate", id],
    // -cmd alias routes to purchasing-service (the owning service for the aggregate);
    // /api/purchase-orders without -cmd routes to reporting (projection rows).
    queryFn: () => apiGet<Po>(`/api/purchase-orders-cmd/${id}`),
    enabled: !!id,
  });

  const approveMutation = useMutation({
    mutationFn: () => apiPost(`/api/purchase-orders-cmd/${id}/approve`, {
      reason: reason.trim() || null,
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["po-aggregate", id] });
      queryClient.invalidateQueries({ queryKey: ["purchase-orders"] });
      setApproveDialog(false);
      setReason("");
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : "Approve failed."),
  });

  const rejectMutation = useMutation({
    mutationFn: () => apiPost(`/api/purchase-orders-cmd/${id}/reject`, {
      reason: rejectReason.trim() || null,
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["po-aggregate", id] });
      queryClient.invalidateQueries({ queryKey: ["purchase-orders"] });
      setRejectDialog(false);
      setRejectReason("");
    },
    onError: (err) => setRejectError(err instanceof ApiError ? err.message : "Reject failed."),
  });

  function open() {
    setReason("");
    setError(null);
    setApproveDialog(true);
  }

  function submit() {
    approveMutation.mutate();
  }

  function openReject() {
    setRejectReason("");
    setRejectError(null);
    setRejectDialog(true);
  }

  function submitReject() {
    rejectMutation.mutate();
  }

  if (isLoading) {
    return <div className="flex h-full items-center justify-center text-sm text-text-muted">Loading purchase order…</div>;
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

  const status = statusForOrder(data.status);
  const isDraft = data.status === "draft";

  const lineColumns: Column<PoLine>[] = [
    { key: "ln", header: "#", width: "40px", numeric: true, render: (l) => l.lineNumber },
    { key: "sku", header: "SKU", width: "180px", render: (l) => <span className="font-medium tabular-nums">{l.productSku}</span> },
    { key: "name", header: "Product", render: (l) => l.productName },
    { key: "qty", header: "Qty", numeric: true, width: "90px", render: (l) => formatQty(l.orderedQuantity) },
    { key: "price", header: "Unit Price", numeric: true, width: "110px", render: (l) => formatMoney(l.unitPrice) },
    { key: "total", header: "Line Total", numeric: true, width: "120px", render: (l) => <strong>{formatMoney(l.lineTotal)}</strong> },
    { key: "status", header: "Status", width: "120px", render: (l) => <StatusPill label={l.status} tone="neutral" /> },
  ];

  return (
    <>
      <DetailLayout
        trail={[
          { label: "Purchasing" },
          { label: "Purchase Orders", to: "/purchase-orders" },
          { label: data.purchaseOrderNumber },
        ]}
        title={data.purchaseOrderNumber}
        subtitle={`${data.supplierCode} — ${data.supplierName}`}
        status={status}
        actions={
          isDraft && (
            <>
              <ActionButton
                variant="primary"
                icon={<Check className="h-4 w-4" />}
                onClick={open}
                requiresRole="purchasing_manager"
              >
                Approve
              </ActionButton>
              <ActionButton
                variant="danger"
                icon={<Ban className="h-4 w-4" />}
                onClick={openReject}
                requiresRole="purchasing_manager"
              >
                Reject
              </ActionButton>
            </>
          )
        }
        tabs={[
          {
            key: "overview",
            label: "Overview",
            content: (
              <div className="grid gap-4 lg:grid-cols-2">
                <FormSection title="Header">
                  <ReadOnlyField label="PO #" value={<span className="font-medium tabular-nums">{data.purchaseOrderNumber}</span>} />
                  <ReadOnlyField label="Status" value={<StatusPill label={status.label} tone={status.tone} />} />
                  <ReadOnlyField label="Supplier" value={<span>{data.supplierName} <span className="text-text-faint">({data.supplierCode})</span></span>} />
                  <ReadOnlyField label="Currency" value={data.currencyCode} />
                </FormSection>
                <FormSection title="Source">
                  <ReadOnlyField label="From requisition" value={data.purchaseRequisitionNumber ?? <code className="text-xs text-text-muted">{shortUuid(data.purchaseRequisitionHeaderId)}</code>} fullWidth />
                </FormSection>
                <FormSection title="Totals" columns={3} className="lg:col-span-2">
                  <ReadOnlyField label="Subtotal" value={<span className="tabular-nums">{formatMoney(data.subtotalAmount)} {data.currencyCode}</span>} />
                  <ReadOnlyField label="Tax" value={<span className="tabular-nums">{formatMoney(data.taxAmount)} {data.currencyCode}</span>} />
                  <ReadOnlyField label="Total" value={<span className="text-base font-semibold tabular-nums">{formatMoney(data.totalAmount)} {data.currencyCode}</span>} />
                </FormSection>
              </div>
            ),
          },
          {
            key: "lines",
            label: "Lines",
            badge: data.lines.length,
            content: <DataGrid columns={lineColumns} rows={data.lines} rowKey={(l) => l.lineId} />,
          },
          {
            key: "audit",
            label: "Audit",
            content: <AuditTab aggregateId={id} />,
          },
        ]}
      />

      <ConfirmDialog
        open={approveDialog}
        title="Approve purchase order?"
        message={
          <>
            Approves <strong>{data.purchaseOrderNumber}</strong> for {data.supplierName}{" "}
            (<strong>{formatMoney(data.totalAmount)} {data.currencyCode}</strong>).
            <br />
            Status flips draft → sent, emits <code>purchasing.PurchaseOrderApproved</code>, and
            advances the P2P saga from <code>started</code> to <code>purchase_order_approved</code>.
          </>
        }
        confirmLabel="Approve"
        busy={approveMutation.isPending}
        onCancel={() => setApproveDialog(false)}
        onConfirm={submit}
        body={
          <div className="space-y-3">
            <Field label="Reason / note">
              <TextArea
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="e.g. matches budget; supplier checked"
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

      <ConfirmDialog
        open={rejectDialog}
        title="Reject purchase order?"
        variant="danger"
        message={
          <>
            Rejects <strong>{data.purchaseOrderNumber}</strong> for {data.supplierName}.
            <br />
            Status flips draft → cancelled, emits <code>purchasing.PurchaseOrderCancelled</code>, and
            terminates the P2P saga at <code>cancelled</code>. Use this for an erroneous draft
            (e.g. wrong supplier, or zero-priced lines that can't be approved).
          </>
        }
        confirmLabel="Reject"
        busy={rejectMutation.isPending}
        onCancel={() => setRejectDialog(false)}
        onConfirm={submitReject}
        body={
          <div className="space-y-3">
            <Field label="Reason / note">
              <TextArea
                value={rejectReason}
                onChange={(e) => setRejectReason(e.target.value)}
                placeholder="e.g. wrong supplier; lines unpriced — recreate after fixing the price list"
                rows={3}
                autoFocus
              />
            </Field>
            {rejectError && (
              <div className="rounded-md border border-status-error/30 bg-status-error-soft px-3 py-2 text-xs text-status-error">
                {rejectError}
              </div>
            )}
          </div>
        }
      />
    </>
  );
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
function shortUuid(uuid: string): string {
  return uuid ? `${uuid.slice(0, 8)}…${uuid.slice(-4)}` : "—";
}
