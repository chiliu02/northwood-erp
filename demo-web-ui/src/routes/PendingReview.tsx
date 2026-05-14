import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, XCircle } from "lucide-react";
import { fetchPendingReview } from "@/api/fetchers";
import { manualApproveSupplierInvoice, rejectSupplierInvoice } from "@/api/commands";
import type { SupplierInvoiceView } from "@/api/types";
import { Button, FieldRow, FormStatus, Input, type SubmitState } from "@/components/ui/Form";
import { Modal } from "@/components/ui/Modal";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { formatMoney, truncateUuid } from "@/lib/utils";
import { PERSONAS } from "@/personas";

export function PendingReview() {
  const persona = PERSONAS.olivia;
  const { data, isLoading, error } = useQuery({
    queryKey: ["pending-review"],
    queryFn: fetchPendingReview,
    refetchInterval: 5_000,
  });

  const list = data ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-baseline gap-3">
        <h1 className="text-[28px] font-semibold tracking-tight">Pending 3-way review</h1>
        <span className="flex items-center gap-2 text-sm text-text-muted">
          <span className="h-1.5 w-1.5 rounded-full" style={{ background: persona.accentVar }} aria-hidden />
          {persona.name} · {persona.role}
        </span>
        <span className="ml-auto text-xs text-text-faint">
          {isLoading ? "loading…" : `${list.length} flagged`}
        </span>
      </div>

      <p className="text-sm text-text-muted">
        Supplier invoices that the matching engine flagged
        (<span className="font-mono">three_way_match_failed</span>): the invoice quantity or unit price
        diverges from the receipt + PO line beyond tolerance. Reviewer note + decision triggers a real
        GL posting and unblocks the P2P saga.
      </p>

      {error && (
        <div
          className="rounded-md border px-4 py-3 text-sm"
          style={{
            borderColor: "var(--color-state-error)",
            background: "color-mix(in srgb, var(--color-state-error) 12%, transparent)",
          }}
        >
          <p className="font-medium" style={{ color: "var(--color-state-error)" }}>
            Couldn't reach finance-service on :8086
          </p>
          <p className="mt-1 text-text-muted">{String(error)}</p>
        </div>
      )}

      <div className="overflow-hidden rounded-lg border border-border-subtle bg-bg-elevated">
        <table className="w-full text-sm">
          <thead className="border-b border-border-subtle bg-bg-base/50 text-left text-[11px] uppercase tracking-wider text-text-muted">
            <tr>
              <th className="px-4 py-2 font-semibold">Internal #</th>
              <th className="px-4 py-2 font-semibold">Supplier #</th>
              <th className="px-4 py-2 font-semibold">Supplier</th>
              <th className="px-4 py-2 text-right font-semibold">Total</th>
              <th className="px-4 py-2 font-semibold">Match</th>
              <th className="px-4 py-2 font-semibold">PO</th>
              <th className="px-4 py-2 text-right font-semibold"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border-subtle">
            {list.map((inv) => (
              <tr key={inv.id} className="hover:bg-bg-hover">
                <td className="px-4 py-2 font-mono">{inv.internalInvoiceNumber}</td>
                <td className="px-4 py-2 font-mono text-xs">{inv.supplierInvoiceNumber}</td>
                <td className="px-4 py-2">{inv.supplierName}</td>
                <td className="px-4 py-2 text-right tabular-nums">
                  {formatMoney(inv.totalAmount, inv.currencyCode)}
                </td>
                <td className="px-4 py-2">
                  <StatusBadge kind="error">{inv.matchStatus ?? "—"}</StatusBadge>
                </td>
                <td className="px-4 py-2 font-mono text-xs text-text-faint">
                  {truncateUuid(inv.purchaseOrderHeaderId)}
                </td>
                <td className="px-4 py-2 text-right">
                  <ReviewActions invoice={inv} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!isLoading && list.length === 0 && !error && (
          <p className="px-4 py-6 text-center text-sm text-text-faint">
            Inbox empty. Variance-flagged invoices land here when{" "}
            <span className="font-mono">SupplierInvoiceService.recordInvoice</span> fails the price
            tolerance check (default 2.0%).
          </p>
        )}
      </div>
    </div>
  );
}

type ReviewMode = "approve" | "reject";

function ReviewActions({ invoice }: { invoice: SupplierInvoiceView }) {
  const [mode, setMode] = useState<ReviewMode | null>(null);
  return (
    <>
      <Button variant="secondary" onClick={() => setMode("approve")}>
        <CheckCircle2 className="-ml-0.5 mr-1 inline h-3.5 w-3.5" /> approve
      </Button>{" "}
      <Button variant="ghost" onClick={() => setMode("reject")}>
        <XCircle className="-ml-0.5 mr-1 inline h-3.5 w-3.5" /> reject
      </Button>
      <ReviewModal
        mode={mode}
        invoice={invoice}
        onClose={() => setMode(null)}
      />
    </>
  );
}

function ReviewModal({ mode, invoice, onClose }: {
  mode: ReviewMode | null;
  invoice: SupplierInvoiceView;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [reviewer, setReviewer] = useState("olivia");
  const [reason, setReason] = useState("");
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  async function onSubmit() {
    if (!mode) return;
    setSubmit({ status: "submitting" });
    try {
      const op = mode === "approve" ? manualApproveSupplierInvoice : rejectSupplierInvoice;
      await op(invoice.id, { reviewer, reason });
      setSubmit({
        status: "success",
        message: mode === "approve"
          ? "Approved — GL posted, P2P saga advancing"
          : "Rejected — invoice cancelled (terminal)",
      });
      queryClient.invalidateQueries({ queryKey: ["pending-review"] });
      setTimeout(() => { setSubmit({ status: "idle" }); onClose(); }, 1200);
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <Modal
      open={mode !== null}
      onClose={() => { setSubmit({ status: "idle" }); onClose(); }}
      title={mode === "approve" ? "Manually approve invoice" : "Reject invoice"}
      subtitle={
        <>
          <span className="font-mono">{invoice.internalInvoiceNumber}</span> · {invoice.supplierName} ·{" "}
          {formatMoney(invoice.totalAmount, invoice.currencyCode)}
        </>
      }
    >
      <div className="space-y-4">
        <p className="text-sm text-text-muted">
          {mode === "approve" ? (
            <>
              Reviewer override the failed match. Emits{" "}
              <span className="font-mono">finance.SupplierInvoiceApproved</span>; same downstream
              effect as the auto-approve path.
            </>
          ) : (
            <>
              Cancel this invoice. Status flips to <span className="font-mono">cancelled</span>; no
              event, no GL movement, terminal.
            </>
          )}
        </p>
        <FieldRow label="Reviewer" required>
          <Input value={reviewer} onChange={(e) => setReviewer(e.target.value)} />
        </FieldRow>
        <FieldRow label="Reason / note" required hint="Captured on the invoice for audit">
          <Input value={reason} onChange={(e) => setReason(e.target.value)} />
        </FieldRow>
        <div className="flex items-center justify-end gap-3">
          <FormStatus state={submit} />
          <Button
            variant={mode === "approve" ? "primary" : "destructive"}
            onClick={onSubmit}
            disabled={submit.status === "submitting" || !reviewer || !reason}
          >
            {mode === "approve" ? "Approve" : "Reject"}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
