import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Check } from "lucide-react";
import { fetchPurchaseOrders } from "@/api/fetchers";
import { approvePurchaseOrder } from "@/api/commands";
import type { PurchaseOrderTracking } from "@/api/types";
import { MasterDetail } from "@/components/MasterDetail";
import { StatusBadge, inferStatusKind } from "@/components/ui/StatusBadge";
import { Modal } from "@/components/ui/Modal";
import { Button, FieldRow, FormStatus, Input, type SubmitState } from "@/components/ui/Form";
import { formatMoney, truncateUuid } from "@/lib/utils";

export function PurchaseOrders() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["purchase-orders"],
    queryFn: fetchPurchaseOrders,
    refetchInterval: 5_000,
  });

  return (
    <MasterDetail
      title="Purchase orders"
      persona="tom"
      items={data}
      isLoading={isLoading}
      error={error}
      errorContext="reporting-service on :8087"
      rowKey={(o) => o.purchaseOrderHeaderId}
      renderRow={(o) => (
        <div className="space-y-1">
          <div className="flex items-center justify-between">
            <span className="font-mono text-text-primary">{o.purchaseOrderNumber}</span>
            <StatusBadge kind={inferStatusKind(o.poStatus)}>{o.poStatus}</StatusBadge>
          </div>
          <div className="flex items-center justify-between text-xs text-text-muted">
            <span>{o.supplierName ?? "—"}</span>
            <span className="tabular-nums">{formatMoney(o.orderedAmount, o.currencyCode)}</span>
          </div>
        </div>
      )}
      renderDetail={(o) => <POTrackingDetail po={o} />}
    />
  );
}

function POTrackingDetail({ po }: { po: PurchaseOrderTracking }) {
  const isDraft = po.poStatus === "draft";
  return (
    <div className="space-y-5">
      <header className="space-y-1">
        <div className="flex items-center gap-3">
          <h2 className="font-mono text-xl font-semibold">{po.purchaseOrderNumber}</h2>
          <StatusBadge kind={inferStatusKind(po.poStatus)}>{po.poStatus}</StatusBadge>
          <span className="ml-auto">
            {isDraft && <ApprovePoButton po={po} />}
          </span>
        </div>
        <p className="text-sm text-text-muted">
          {po.supplierName ?? "—"} · ordered {po.orderDate ?? "—"}
          {po.expectedReceiptDate && <> · expected {po.expectedReceiptDate}</>}
        </p>
      </header>

      <section>
        <h3 className="mb-2 text-sm font-semibold text-text-primary">Money flow</h3>
        <div className="space-y-2 text-sm">
          <FlowRow label="Ordered"     value={formatMoney(po.orderedAmount,     po.currencyCode)} pct={1.0} kind="neutral" />
          <FlowRow label="Received"    value={formatMoney(po.receivedAmount,    po.currencyCode)} pct={pct(po.receivedAmount, po.orderedAmount)} kind={inferStatusKind(po.receiptStatus)} />
          <FlowRow label="Invoiced"    value={formatMoney(po.invoicedAmount,    po.currencyCode)} pct={pct(po.invoicedAmount, po.orderedAmount)} kind={inferStatusKind(po.invoiceStatus)} />
          <FlowRow label="Paid"        value={formatMoney(po.paidAmount,        po.currencyCode)} pct={pct(po.paidAmount,     po.orderedAmount)} kind={inferStatusKind(po.paymentStatus)} />
          <FlowRow label="Outstanding" value={formatMoney(po.outstandingAmount, po.currencyCode)} pct={pct(po.outstandingAmount, po.orderedAmount)} kind="warn" subtle />
        </div>
      </section>

      <section className="grid grid-cols-2 gap-2 text-sm">
        <Tag label="receipt"  value={po.receiptStatus} />
        <Tag label="invoice"  value={po.invoiceStatus} />
        <Tag label="payment"  value={po.paymentStatus} />
        <Tag label="match"    value={po.matchStatus} />
      </section>

      <section className="space-y-1 text-xs text-text-faint">
        <p>id: <span className="font-mono">{truncateUuid(po.purchaseOrderHeaderId)}</span></p>
        <p>updated: <span className="font-mono">{po.updatedAt ?? "—"}</span></p>
      </section>
    </div>
  );
}

function FlowRow({ label, value, pct, kind, subtle }: {
  label: string; value: string; pct: number;
  kind: "neutral" | "pending" | "active" | "success" | "warn" | "error" | "terminal";
  subtle?: boolean;
}) {
  const colour = kind === "neutral" ? "var(--color-text-faint)" : `var(--color-state-${kind})`;
  return (
    <div className="grid grid-cols-[80px_1fr_120px] items-center gap-3">
      <span className="text-xs uppercase tracking-wider text-text-muted">{label}</span>
      <div className="h-1.5 overflow-hidden rounded-full bg-bg-hover">
        <div
          className="h-full transition-all"
          style={{ width: `${Math.min(Math.max(pct * 100, 0), 100)}%`, background: colour, opacity: subtle ? 0.5 : 1 }}
        />
      </div>
      <span className="text-right font-medium tabular-nums">{value}</span>
    </div>
  );
}

function Tag({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex items-center justify-between rounded-md border border-border-subtle px-3 py-1.5">
      <span className="text-xs uppercase tracking-wider text-text-muted">{label}</span>
      <StatusBadge kind={inferStatusKind(value)}>{value ?? "—"}</StatusBadge>
    </div>
  );
}

function pct(part: string, whole: string): number {
  const w = Number(whole);
  if (!w || isNaN(w)) return 0;
  return Number(part) / w;
}

function ApprovePoButton({ po }: { po: PurchaseOrderTracking }) {
  const [open, setOpen] = useState(false);
  const [approver, setApprover] = useState("tom");
  const [reason, setReason] = useState("");
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });
  const queryClient = useQueryClient();

  async function onSubmit() {
    if (!approver.trim() || !reason.trim()) {
      setSubmit({ status: "error", message: "Approver and reason are required" });
      return;
    }
    setSubmit({ status: "submitting" });
    try {
      await approvePurchaseOrder(po.purchaseOrderHeaderId, {
        approver: approver.trim(),
        reason: reason.trim(),
      });
      setSubmit({ status: "success", message: "PO approved" });
      queryClient.invalidateQueries({ queryKey: ["purchase-orders"] });
      setTimeout(() => setOpen(false), 800);
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <>
      <Button variant="primary" onClick={() => setOpen(true)}>
        <Check className="h-3.5 w-3.5" /> Approve PO
      </Button>
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title="Approve purchase order"
        subtitle={<><span className="font-mono">{po.purchaseOrderNumber}</span> · {po.supplierName ?? "—"}</>}
      >
        <div className="space-y-4">
          <p className="text-sm text-text-muted">
            Flips header <span className="font-mono">draft</span> → <span className="font-mono">sent</span> and emits{" "}
            <span className="font-mono">purchasing.PurchaseOrderApproved</span>. P2P saga walks{" "}
            <span className="font-mono">started → purchase_order_approved → waiting_for_goods</span>.
            Shortage-driven POs auto-approve when{" "}
            <span className="font-mono">northwood.purchasing.shortagePoAutoApprove=true</span> (default), so this
            path matters for manual PRs.
          </p>
          <FieldRow label="Approver" required>
            <Input value={approver} onChange={(e) => setApprover(e.target.value)} />
          </FieldRow>
          <FieldRow label="Reason" required>
            <Input
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Needed for Aug build"
            />
          </FieldRow>
          <div className="flex items-center justify-end gap-3">
            <FormStatus state={submit} />
            <Button variant="primary" onClick={onSubmit} disabled={submit.status === "submitting"}>
              Approve PO
            </Button>
          </div>
        </div>
      </Modal>
    </>
  );
}
