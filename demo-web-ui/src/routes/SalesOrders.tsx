import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban } from "lucide-react";
import { fetchSalesOrders } from "@/api/fetchers";
import { cancelSalesOrder } from "@/api/commands";
import type { SalesOrder360 } from "@/api/types";
import { MasterDetail } from "@/components/MasterDetail";
import { StatusBadge, inferStatusKind } from "@/components/ui/StatusBadge";
import { Modal } from "@/components/ui/Modal";
import { Button, FieldRow, FormStatus, Input, type SubmitState } from "@/components/ui/Form";
import { formatMoney, truncateUuid } from "@/lib/utils";

const NON_CANCELLABLE = new Set(["shipped", "completed", "cancelled", "rejected"]);

export function SalesOrders() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["sales-orders"],
    queryFn: fetchSalesOrders,
    refetchInterval: 5_000,
  });

  return (
    <MasterDetail
      title="Sales orders"
      persona="sarah"
      items={data}
      isLoading={isLoading}
      error={error}
      errorContext="reporting-service on :8087"
      rowKey={(o) => o.salesOrderHeaderId}
      renderRow={(o) => (
        <div className="space-y-1">
          <div className="flex items-center justify-between">
            <span className="font-mono text-text-primary">{o.orderNumber}</span>
            <StatusBadge kind={inferStatusKind(o.orderStatus)}>{o.orderStatus}</StatusBadge>
          </div>
          <div className="flex items-center justify-between text-xs text-text-muted">
            <span>{o.customerName ?? "—"}</span>
            <span className="tabular-nums">{formatMoney(o.totalAmount, o.currencyCode)}</span>
          </div>
        </div>
      )}
      renderDetail={(o) => <SalesOrderDetail order={o} />}
    />
  );
}

function SalesOrderDetail({ order }: { order: SalesOrder360 }) {
  const cancellable = !NON_CANCELLABLE.has(order.orderStatus);
  return (
    <div className="space-y-5">
      <header className="space-y-1">
        <div className="flex items-center gap-3">
          <h2 className="font-mono text-xl font-semibold">{order.orderNumber}</h2>
          <StatusBadge kind={inferStatusKind(order.orderStatus)}>{order.orderStatus}</StatusBadge>
          {order.hasShortage && <StatusBadge kind="warn">shortage</StatusBadge>}
          <span className="ml-auto">
            {cancellable && <CancelOrderButton order={order} />}
          </span>
        </div>
        <p className="text-sm text-text-muted">
          {order.customerName ?? "—"} · ordered {order.orderDate ?? "—"}
          {order.requestedDeliveryDate && <> · requested {order.requestedDeliveryDate}</>}
        </p>
      </header>

      <Timeline order={order} />

      <section className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Stat label="Total"       value={formatMoney(order.totalAmount,       order.currencyCode)} />
        <Stat label="Invoiced"    value={formatMoney(order.invoicedAmount,    order.currencyCode)} />
        <Stat label="Paid"        value={formatMoney(order.paidAmount,        order.currencyCode)} />
        <Stat label="Outstanding" value={formatMoney(order.outstandingAmount, order.currencyCode)} />
      </section>

      {order.shortageSummary && (
        <section>
          <h3 className="text-sm font-semibold text-text-primary">Shortage</h3>
          <p className="mt-1 text-sm text-text-muted">{order.shortageSummary}</p>
        </section>
      )}

      <section className="space-y-1 text-xs text-text-faint">
        <p>last event: <span className="font-mono">{order.lastEventType ?? "—"}</span> @ <span className="font-mono">{order.lastEventAt ?? "—"}</span></p>
        <p>id: <span className="font-mono">{truncateUuid(order.salesOrderHeaderId)}</span></p>
      </section>
    </div>
  );
}

function Timeline({ order }: { order: SalesOrder360 }) {
  // Six demo-relevant stages mirroring the saga timeline in demo-script.md.
  const stages: Array<{ key: keyof SalesOrder360 | "stock"; label: string; value: string | null }> = [
    { key: "orderStatus",         label: "placed",   value: order.orderStatus },
    { key: "stockStatus",         label: "stock",    value: order.stockStatus },
    { key: "manufacturingStatus", label: "make",     value: order.manufacturingStatus },
    { key: "shipmentStatus",      label: "ship",     value: order.shipmentStatus },
    { key: "invoiceStatus",       label: "invoice",  value: order.invoiceStatus },
    { key: "paymentStatus",       label: "payment",  value: order.paymentStatus },
  ];
  return (
    <div className="flex items-center gap-1">
      {stages.map((s, i) => {
        const kind = inferStatusKind(s.value);
        const colour = `var(--color-state-${kind === "neutral" ? "pending" : kind})`;
        return (
          <div key={i} className="flex flex-1 flex-col items-center gap-1.5">
            <div className="flex w-full items-center gap-1">
              <span
                className="h-2.5 w-2.5 shrink-0 rounded-full border-2"
                style={{ borderColor: colour, background: kind === "neutral" || kind === "pending" ? "transparent" : colour }}
                aria-hidden
              />
              {i < stages.length - 1 && (
                <span className="h-px flex-1" style={{ background: colour, opacity: 0.5 }} />
              )}
            </div>
            <span className="text-[10px] uppercase tracking-wider text-text-muted">{s.label}</span>
            <span className="text-[10px] text-text-faint">{s.value ?? "—"}</span>
          </div>
        );
      })}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-border-subtle p-3">
      <p className="text-xs uppercase tracking-wider text-text-muted">{label}</p>
      <p className="mt-1 text-lg font-semibold tabular-nums">{value}</p>
    </div>
  );
}

function CancelOrderButton({ order }: { order: SalesOrder360 }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });
  const queryClient = useQueryClient();

  async function onSubmit() {
    if (!reason.trim()) {
      setSubmit({ status: "error", message: "Reason is required" });
      return;
    }
    setSubmit({ status: "submitting" });
    try {
      await cancelSalesOrder(order.salesOrderHeaderId, { reason: reason.trim() });
      setSubmit({ status: "success", message: "cancellation requested" });
      queryClient.invalidateQueries({ queryKey: ["sales-orders"] });
      setTimeout(() => setOpen(false), 800);
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <>
      <Button variant="destructive" onClick={() => setOpen(true)}>
        <Ban className="h-3.5 w-3.5" /> Cancel order
      </Button>
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title="Cancel sales order"
        subtitle={<><span className="font-mono">{order.orderNumber}</span> · {order.customerName ?? "—"}</>}
      >
        <div className="space-y-4">
          <p className="text-sm text-text-muted">
            Header flips to <span className="font-mono">cancelled</span> + saga to{" "}
            <span className="font-mono">compensating</span>; inventory releases the stock reservation; manufacturing
            cancels every active WO. When both ack the cancel, the saga advances to{" "}
            <span className="font-mono">compensated</span>. Hard-cancel — WIP is written off. Server rejects with
            HTTP 409 once past <span className="font-mono">goods_shipped</span> (credit-note / return-goods flow is
            out of scope).
          </p>
          <FieldRow label="Reason" required>
            <Input
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Customer changed mind"
            />
          </FieldRow>
          <div className="flex items-center justify-end gap-3">
            <FormStatus state={submit} />
            <Button
              variant="destructive"
              onClick={onSubmit}
              disabled={submit.status === "submitting"}
            >
              Cancel order
            </Button>
          </div>
        </div>
      </Modal>
    </>
  );
}
