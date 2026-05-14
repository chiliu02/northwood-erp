import { useState } from "react";
import { useParams } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, X } from "lucide-react";
import { apiGet, apiPost, ApiError } from "@/lib/api";
import { DetailLayout } from "@/components/ui/DetailLayout";
import { ActionButton } from "@/components/ui/ActionButton";
import { AuditTab } from "@/components/ui/AuditTab";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { FormSection, ReadOnlyField, Field } from "@/components/ui/FormSection";
import { TextInput, TextArea } from "@/components/ui/Form";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";
import { DataGrid, type Column } from "@/components/ui/DataGrid";

interface InvoiceLine {
  lineId: string;
  lineNumber: number;
  productId: string;
  productSku: string;
  productName: string;
  quantity: string;
  unitPrice: string;
  taxRate: string;
  taxAmount: string;
  lineTotal: string;
}

interface Invoice {
  id: string;
  internalInvoiceNumber: string;
  supplierInvoiceNumber: string;
  purchaseOrderHeaderId: string;
  goodsReceiptHeaderId: string;
  supplierId: string;
  supplierName: string;
  currencyCode: string;
  subtotalAmount: string;
  taxAmount: string;
  totalAmount: string;
  status: string;
  matchStatus: string;
  version: number;
  lines: InvoiceLine[];
}

type ReviewAction = "approve" | "reject";

export function SupplierInvoiceDetail() {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<ReviewAction | null>(null);
  const [reviewer, setReviewer] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading, error: fetchError } = useQuery({
    queryKey: ["supplier-invoice", id],
    queryFn: () => apiGet<Invoice>(`/api/supplier-invoices/${id}`),
    enabled: !!id,
  });

  const reviewMutation = useMutation({
    mutationFn: async (vars: { action: ReviewAction; reviewer: string; reason: string }) => {
      const path = vars.action === "approve"
        ? `/api/supplier-invoices/${id}/manual-approve`
        : `/api/supplier-invoices/${id}/reject`;
      await apiPost(path, { reviewer: vars.reviewer, reason: vars.reason });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["supplier-invoice", id] });
      queryClient.invalidateQueries({ queryKey: ["pending-review"] });
      closeDialog();
    },
    onError: (err) => {
      setError(err instanceof ApiError ? err.message : "Action failed.");
    },
  });

  function openDialog(action: ReviewAction) {
    setDialog(action);
    setReviewer("");
    setReason("");
    setError(null);
  }
  function closeDialog() {
    setDialog(null);
    setReviewer("");
    setReason("");
    setError(null);
  }
  function submit() {
    if (!dialog || !reviewer.trim()) {
      setError("Reviewer is required.");
      return;
    }
    reviewMutation.mutate({ action: dialog, reviewer: reviewer.trim(), reason: reason.trim() });
  }

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-text-muted">
        Loading invoice…
      </div>
    );
  }

  if (fetchError || !data) {
    return (
      <div className="px-8 py-12">
        <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
          Failed to load invoice: {fetchError instanceof Error ? fetchError.message : "Not found"}
        </div>
      </div>
    );
  }

  const status = statusForOrder(data.status);
  const isPendingReview = data.status === "three_way_match_failed";

  const lineColumns: Column<InvoiceLine>[] = [
    { key: "lineNumber", header: "#", width: "50px", numeric: true, render: (l) => l.lineNumber },
    { key: "sku", header: "SKU", width: "180px", render: (l) => <span className="font-medium tabular-nums">{l.productSku}</span> },
    { key: "name", header: "Product", render: (l) => <span>{l.productName}</span> },
    { key: "qty", header: "Qty", numeric: true, width: "80px", render: (l) => formatQty(l.quantity) },
    { key: "price", header: "Unit Price", numeric: true, width: "110px", render: (l) => formatMoney(l.unitPrice) },
    { key: "tax", header: "Tax", numeric: true, width: "90px", render: (l) => formatMoney(l.taxAmount) },
    { key: "total", header: "Line Total", numeric: true, width: "110px", render: (l) => <strong>{formatMoney(l.lineTotal)}</strong> },
  ];

  return (
    <>
      <DetailLayout
        trail={[
          { label: "Home", to: "/" },
          { label: "Finance" },
          { label: "Supplier Invoices", to: "/supplier-invoices/pending-review" },
          { label: data.internalInvoiceNumber },
        ]}
        title={data.internalInvoiceNumber}
        subtitle={`Supplier invoice ${data.supplierInvoiceNumber || "(no external #)"} — ${data.supplierName}`}
        status={status}
        actions={
          isPendingReview && (
            <>
              <ActionButton
                icon={<X className="h-4 w-4" />}
                variant="danger"
                onClick={() => openDialog("reject")}
                requiresRole="finance_manager"
              >
                Reject
              </ActionButton>
              <ActionButton
                icon={<Check className="h-4 w-4" />}
                variant="primary"
                onClick={() => openDialog("approve")}
                requiresRole="finance_manager"
              >
                Approve
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
                <FormSection title="Invoice">
                  <ReadOnlyField label="Internal #" value={<span className="font-medium tabular-nums">{data.internalInvoiceNumber}</span>} />
                  <ReadOnlyField label="Supplier #" value={data.supplierInvoiceNumber || "—"} />
                  <ReadOnlyField label="Supplier" value={data.supplierName} />
                  <ReadOnlyField label="Currency" value={data.currencyCode} />
                  <ReadOnlyField label="Status" value={<StatusPill label={status.label} tone={status.tone} />} />
                  <ReadOnlyField label="Match" value={<StatusPill label={data.matchStatus.replace(/_/g, " ")} tone={data.matchStatus.includes("failed") ? "error" : "success"} />} />
                </FormSection>
                <FormSection title="Linked documents">
                  <ReadOnlyField label="Purchase Order" value={<code className="text-xs text-text-muted">{shortUuid(data.purchaseOrderHeaderId)}</code>} />
                  <ReadOnlyField label="Goods Receipt" value={data.goodsReceiptHeaderId ? <code className="text-xs text-text-muted">{shortUuid(data.goodsReceiptHeaderId)}</code> : "—"} />
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
            content: (
              <DataGrid columns={lineColumns} rows={data.lines} rowKey={(l) => l.lineId} />
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
        open={dialog !== null}
        title={dialog === "approve" ? "Manual approve invoice?" : "Reject invoice?"}
        message={
          <>
            <strong className="text-text-primary">{data.internalInvoiceNumber}</strong> — {data.supplierName} —{" "}
            <strong className="text-text-primary">{formatMoney(data.totalAmount)} {data.currencyCode}</strong>
            <br />
            {dialog === "approve"
              ? "Approving overrides the 3-way match failure, posts the invoice, and emits SupplierInvoiceApproved."
              : "Rejecting marks the invoice cancelled. No event, no GL movement. Terminal."}
          </>
        }
        confirmLabel={dialog === "approve" ? "Approve" : "Reject"}
        variant={dialog === "approve" ? "primary" : "danger"}
        busy={reviewMutation.isPending}
        onCancel={closeDialog}
        onConfirm={submit}
        body={
          <div className="space-y-3">
            <Field label="Reviewer" required>
              <TextInput value={reviewer} onChange={(e) => setReviewer(e.target.value)} autoFocus />
            </Field>
            <Field label="Reason">
              <TextArea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} />
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

function formatMoney(v: string | number | null | undefined): string {
  if (v == null) return "—";
  const n = typeof v === "string" ? Number(v) : v;
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatQty(v: string | number | null | undefined): string {
  if (v == null) return "—";
  const n = typeof v === "string" ? Number(v) : v;
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 3 });
}

function shortUuid(uuid: string): string {
  return uuid ? `${uuid.slice(0, 8)}…${uuid.slice(-4)}` : "—";
}
