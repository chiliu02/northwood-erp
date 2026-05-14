import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, X, RefreshCw } from "lucide-react";
import { apiGet, apiPost, ApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { StatusPill } from "@/components/ui/StatusPill";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { Field } from "@/components/ui/FormSection";
import { TextInput, TextArea } from "@/components/ui/Form";

interface SupplierInvoiceLine {
  lineId: string;
  productSku: string;
  quantity: string;
  unitPrice: string;
  lineTotal: string;
}

interface SupplierInvoice {
  id: string;
  internalInvoiceNumber: string;
  supplierInvoiceNumber: string;
  supplierName: string;
  currencyCode: string;
  totalAmount: string;
  status: string;          // 'three_way_match_failed' for queue rows
  matchStatus: string;
  lines: SupplierInvoiceLine[];
}

type ReviewAction = "approve" | "reject";

/**
 * Manual-review queue for invoices parked at three_way_match_failed.
 * Demo-interesting screen for the Finance persona — shows the queue
 * lights up when a supplier invoice fails the 3-way match (price or
 * quantity variance), and lets Daniel resolve each one with a recorded
 * reviewer + reason.
 *
 * Approve emits SupplierInvoiceApproved (saga advances + GL posts);
 * reject is terminal (status 'cancelled', no event).
 */
export function PendingReview() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [dialog, setDialog] = useState<{ invoice: SupplierInvoice; action: ReviewAction } | null>(null);
  const [reviewer, setReviewer] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading, error: fetchError, refetch } = useQuery({
    queryKey: ["pending-review"],
    queryFn: () => apiGet<SupplierInvoice[]>("/api/supplier-invoices/pending-review"),
  });

  const reviewMutation = useMutation({
    mutationFn: async (vars: { id: string; action: ReviewAction; reviewer: string; reason: string }) => {
      const path = vars.action === "approve"
        ? `/api/supplier-invoices/${vars.id}/manual-approve`
        : `/api/supplier-invoices/${vars.id}/reject`;
      await apiPost(path, { reviewer: vars.reviewer, reason: vars.reason });
    },
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: ["pending-review"] });
      toast.success(
        vars.action === "approve"
          ? `Invoice ${dialog?.invoice.internalInvoiceNumber} approved.`
          : `Invoice ${dialog?.invoice.internalInvoiceNumber} rejected.`
      );
      closeDialog();
    },
    onError: (err) => {
      setError(err instanceof ApiError ? err.message : "Action failed.");
    },
  });

  function openDialog(invoice: SupplierInvoice, action: ReviewAction) {
    setDialog({ invoice, action });
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
    reviewMutation.mutate({
      id: dialog.invoice.id,
      action: dialog.action,
      reviewer: reviewer.trim(),
      reason: reason.trim(),
    });
  }

  const columns: Column<SupplierInvoice>[] = [
    {
      key: "internalNumber",
      header: "Invoice #",
      width: "160px",
      render: (r) => <span className="font-medium tabular-nums">{r.internalInvoiceNumber}</span>,
    },
    {
      key: "supplierNumber",
      header: "Supplier #",
      width: "150px",
      render: (r) => <span className="text-text-muted">{r.supplierInvoiceNumber || "—"}</span>,
    },
    {
      key: "supplier",
      header: "Supplier",
      render: (r) => <span>{r.supplierName}</span>,
    },
    {
      key: "lines",
      header: "Lines",
      width: "70px",
      numeric: true,
      render: (r) => <span className="text-text-muted">{r.lines.length}</span>,
    },
    {
      key: "matchStatus",
      header: "Match",
      width: "140px",
      render: () => <StatusPill label="3-way failed" tone="error" />,
    },
    {
      key: "total",
      header: "Total",
      numeric: true,
      width: "130px",
      render: (r) => (
        <span>
          {formatMoney(r.totalAmount)} <span className="text-text-faint">{r.currencyCode}</span>
        </span>
      ),
    },
    {
      key: "actions",
      header: "",
      width: "200px",
      render: (r) => (
        <div className="flex justify-end gap-1.5">
          <ActionButton
            variant="primary"
            icon={<Check className="h-4 w-4" />}
            onClick={(e) => {
              e.stopPropagation();
              openDialog(r, "approve");
            }}
            requiresRole="finance_manager"
            className="!h-8 !px-2 text-xs"
          >
            Approve
          </ActionButton>
          <ActionButton
            variant="danger"
            icon={<X className="h-4 w-4" />}
            onClick={(e) => {
              e.stopPropagation();
              openDialog(r, "reject");
            }}
            requiresRole="finance_manager"
            className="!h-8 !px-2 text-xs"
          >
            Reject
          </ActionButton>
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Pending 3-way Match Review"
        description="Supplier invoices parked at three_way_match_failed. Approve overrides the match check and posts the invoice + GL; reject is terminal."
        trail={[
          { label: "Home", to: "/" },
          { label: "Finance" },
          { label: "Pending Review" },
        ]}
        actions={
          <ActionButton
            icon={<RefreshCw className="h-4 w-4" />}
            onClick={() => refetch()}
          >
            Refresh
          </ActionButton>
        }
      />

      <div className="px-8 py-6">
        {fetchError ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load pending review queue: {(fetchError as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(r) => r.id}
            onRowClick={(r) => navigate(`/supplier-invoices/${r.id}`)}
            loading={isLoading}
            emptyState={
              <span>
                No invoices awaiting review. The queue lights up when an invoice fails the
                3-way match (price ≥ tolerance off, or quantity variance).
              </span>
            }
          />
        )}
        {data && data.length > 0 && (
          <div className="mt-3 text-xs text-text-muted">
            {data.length} invoice{data.length === 1 ? "" : "s"} awaiting review.
          </div>
        )}
      </div>

      <ConfirmDialog
        open={dialog !== null}
        title={dialog?.action === "approve" ? "Manual approve invoice?" : "Reject invoice?"}
        message={
          dialog && (
            <>
              Invoice <strong className="text-text-primary">{dialog.invoice.internalInvoiceNumber}</strong>{" "}
              from {dialog.invoice.supplierName} for{" "}
              <strong className="text-text-primary">
                {formatMoney(dialog.invoice.totalAmount)} {dialog.invoice.currencyCode}
              </strong>
              .
              <br />
              {dialog.action === "approve"
                ? "Approving overrides the 3-way match failure, posts the invoice, and emits SupplierInvoiceApproved (P2P saga advances, GL posts)."
                : "Rejecting marks the invoice cancelled. No event, no GL movement, no projection update. This is terminal."}
            </>
          )
        }
        confirmLabel={dialog?.action === "approve" ? "Approve" : "Reject"}
        variant={dialog?.action === "approve" ? "primary" : "danger"}
        busy={reviewMutation.isPending}
        onCancel={closeDialog}
        onConfirm={submit}
        body={
          <div className="space-y-3">
            <Field label="Reviewer" required>
              <TextInput
                placeholder="Your name (e.g. Daniel Lee)"
                value={reviewer}
                onChange={(e) => setReviewer(e.target.value)}
                autoFocus
              />
            </Field>
            <Field label="Reason" hint="Why is this acceptable / rejected?">
              <TextArea
                placeholder={
                  dialog?.action === "approve"
                    ? "e.g. supplier confirmed price increase via separate email"
                    : "e.g. duplicate invoice; supplier resubmitting"
                }
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                rows={3}
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

function formatMoney(amount: string | number | null | undefined): string {
  if (amount == null) return "—";
  const n = typeof amount === "string" ? Number(amount) : amount;
  if (Number.isNaN(n)) return String(amount);
  return n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
