import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Loader2 } from "lucide-react";
import { fetchWorkOrders, fetchWorkOrderDetail } from "@/api/fetchers";
import { completeOperation } from "@/api/commands";
import type { ProductionPlanningRow, WorkOrderOperationView, WorkOrderView } from "@/api/types";
import { MasterDetail } from "@/components/MasterDetail";
import { Button } from "@/components/ui/Form";
import { StatusBadge, inferStatusKind } from "@/components/ui/StatusBadge";
import { cn, truncateUuid } from "@/lib/utils";

export function ProductionBoard() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["work-orders"],
    queryFn: fetchWorkOrders,
    refetchInterval: 5_000,
  });

  return (
    <MasterDetail
      title="Production board"
      persona="linda"
      items={data}
      isLoading={isLoading}
      error={error}
      errorContext="reporting-service on :8087"
      rowKey={(w) => w.workOrderId}
      renderRow={(w) => (
        <div className="space-y-1">
          <div className="flex items-center justify-between">
            <span className="font-mono text-text-primary">{w.workOrderNumber}</span>
            <StatusBadge kind={inferStatusKind(w.workOrderStatus)}>{w.workOrderStatus}</StatusBadge>
          </div>
          <div className="flex items-center justify-between text-xs text-text-muted">
            <span>{w.finishedProductSku} · {w.finishedProductName}</span>
            <span className="tabular-nums">
              {w.completedQuantity}/{w.plannedQuantity}
            </span>
          </div>
          {w.shortageMaterialsCount > 0 && (
            <div className="text-[11px]">
              <StatusBadge kind="warn">{w.shortageMaterialsCount} short</StatusBadge>
            </div>
          )}
        </div>
      )}
      renderDetail={(w) => <BoardDetail wo={w} />}
    />
  );
}

function BoardDetail({ wo }: { wo: ProductionPlanningRow }) {
  // The reporting board row carries the high-level WO status + FG-quantity
  // progress; per-operation status lives on the manufacturing-side WO
  // aggregate, so we drill in via /api/work-orders-cmd/{id} for it.
  const { data: detail, isLoading } = useQuery({
    queryKey: ["work-order-detail", wo.workOrderId],
    queryFn: () => fetchWorkOrderDetail(wo.workOrderId),
    refetchInterval: 3_000,
  });

  const completed = Number(wo.completedQuantity);
  const planned   = Number(wo.plannedQuantity);
  const pct = planned > 0 ? Math.min((completed / planned) * 100, 100) : 0;

  return (
    <div className="space-y-5">
      <header className="space-y-1">
        <div className="flex items-center gap-3">
          <h2 className="font-mono text-xl font-semibold">{wo.workOrderNumber}</h2>
          <StatusBadge kind={inferStatusKind(wo.workOrderStatus)}>{wo.workOrderStatus}</StatusBadge>
          <StatusBadge kind={inferStatusKind(wo.materialStatus)}>{wo.materialStatus ?? "?"}</StatusBadge>
        </div>
        <p className="text-sm text-text-muted">
          {wo.finishedProductSku} · {wo.finishedProductName}
          {wo.orderNumber && <> · for sales order <span className="font-mono">{wo.orderNumber}</span></>}
        </p>
      </header>

      <section>
        <h3 className="mb-2 text-sm font-semibold text-text-primary">Finished goods progress</h3>
        <div className="flex items-center gap-3">
          <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-bg-hover">
            <div
              className="h-full transition-all"
              style={{ width: `${pct}%`, background: "var(--color-state-success)" }}
            />
          </div>
          <span className="text-sm font-medium tabular-nums">
            {wo.completedQuantity} / {wo.plannedQuantity}
          </span>
        </div>
      </section>

      <OperationsSection wo={wo} detail={detail} detailLoading={isLoading} />

      {detail && detail.materials.length > 0 && (
        <MaterialsSection materials={detail.materials} />
      )}

      {wo.shortageMaterialsCount > 0 && (
        <section className="rounded-md border border-border-subtle p-3">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-text-primary">Shortage</h3>
            <StatusBadge kind="warn">{wo.shortageMaterialsCount} material{wo.shortageMaterialsCount === 1 ? "" : "s"}</StatusBadge>
          </div>
          {wo.shortageSummary && <p className="mt-2 text-sm text-text-muted">{wo.shortageSummary}</p>}
          {wo.openPurchaseOrdersCount > 0 && (
            <p className="mt-2 text-xs text-text-faint">
              {wo.openPurchaseOrdersCount} open PO{wo.openPurchaseOrdersCount === 1 ? "" : "s"} covering shortage
              {wo.expectedMaterialAvailableDate && <> · expected {wo.expectedMaterialAvailableDate}</>}
            </p>
          )}
        </section>
      )}

      <section className="grid grid-cols-2 gap-2 text-sm">
        <Field label="planned start" value={wo.plannedStartDate} />
        <Field label="planned end"   value={wo.plannedEndDate} />
        <Field label="priority"      value={wo.priority} />
        <Field label="updated"       value={wo.updatedAt} mono />
      </section>

      <section className="text-xs text-text-faint">
        <p>id: <span className="font-mono">{truncateUuid(wo.workOrderId)}</span></p>
      </section>
    </div>
  );
}

function OperationsSection({ wo, detail, detailLoading }: {
  wo: ProductionPlanningRow;
  detail: WorkOrderView | undefined;
  detailLoading: boolean;
}) {
  const ops = (detail?.operations ?? []).slice().sort((a, b) => a.operationSequence - b.operationSequence);
  const nextPlanned = ops.find((o) => o.status === "planned");

  return (
    <section>
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-text-primary">
          Operations
          {ops.length > 0 && (
            <span className="ml-2 text-xs font-normal text-text-faint">
              {ops.filter((o) => o.status === "completed" || o.status === "skipped").length} of {ops.length} done
            </span>
          )}
        </h3>
      </div>

      {detailLoading && !detail && (
        <p className="px-2 py-3 text-xs text-text-faint">loading operations…</p>
      )}

      {detail && ops.length === 0 && (
        <p className="px-2 py-3 text-xs text-text-faint">No operations on this work order.</p>
      )}

      {ops.length > 0 && (
        <ul className="divide-y divide-border-subtle rounded-md border border-border-subtle">
          {ops.map((op) => (
            <OperationRow
              key={op.id}
              workOrderId={wo.workOrderId}
              op={op}
              isNext={nextPlanned?.id === op.id}
              parentDisabled={wo.workOrderStatus === "completed" || wo.workOrderStatus === "cancelled"}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

function OperationRow({ workOrderId, op, isNext, parentDisabled }: {
  workOrderId: string;
  op: WorkOrderOperationView;
  isNext: boolean;
  parentDisabled: boolean;
}) {
  const queryClient = useQueryClient();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isDone = op.status === "completed" || op.status === "skipped";
  // The aggregate enforces sequential completion (you can't complete op 30 while
  // op 20 is still planned), so disable buttons on rows that aren't the next
  // planned op. Already-done rows show the actual minutes instead of a button.
  const canClick = !parentDisabled && !isDone && isNext;

  async function onComplete() {
    setPending(true);
    setError(null);
    try {
      // actualMinutes defaults to plannedRunMinutes — sensible no-think default
      // for the demo. The completeOperation endpoint requires the field.
      const minutes = op.plannedRunMinutes && Number(op.plannedRunMinutes) > 0
        ? op.plannedRunMinutes
        : "30";
      await completeOperation(workOrderId, op.operationSequence, { actualMinutes: minutes });
      queryClient.invalidateQueries({ queryKey: ["work-order-detail", workOrderId] });
      queryClient.invalidateQueries({ queryKey: ["work-orders"] });
    } catch (e) {
      setError(String(e));
    } finally {
      setPending(false);
    }
  }

  return (
    <li className={cn(
      "flex items-center gap-3 px-3 py-2",
      isNext && !isDone && "bg-bg-hover/50"
    )}>
      <span className="w-10 text-right font-mono text-xs tabular-nums text-text-muted">
        {op.operationSequence}
      </span>
      <span className="font-mono text-sm">{op.operationCode}</span>
      <span className="flex-1 text-xs text-text-muted">
        {op.description ?? <span className="text-text-faint">—</span>}
      </span>
      <span className="text-[11px] text-text-faint tabular-nums">
        plan {op.plannedRunMinutes}m
        {isDone && op.actualMinutes && Number(op.actualMinutes) > 0 && (
          <> · actual {op.actualMinutes}m</>
        )}
      </span>
      <StatusBadge kind={inferStatusKind(op.status)}>{op.status}</StatusBadge>
      {!isDone && (
        canClick ? (
          <Button variant="primary" onClick={onComplete} disabled={pending}>
            {pending
              ? <><Loader2 className="-ml-0.5 mr-1 inline h-3.5 w-3.5 animate-spin" /> completing…</>
              : <><CheckCircle2 className="-ml-0.5 mr-1 inline h-3.5 w-3.5" /> complete</>}
          </Button>
        ) : (
          <Button variant="ghost" disabled>waits on prior</Button>
        )
      )}
      {error && (
        <span className="text-[11px]" style={{ color: "var(--color-state-error)" }}>
          {error}
        </span>
      )}
    </li>
  );
}

function MaterialsSection({ materials }: { materials: WorkOrderView["materials"] }) {
  return (
    <section>
      <h3 className="mb-2 text-sm font-semibold text-text-primary">Materials</h3>
      <table className="w-full text-xs">
        <thead className="border-b border-border-subtle text-left text-[10px] uppercase tracking-wider text-text-muted">
          <tr>
            <th className="px-2 py-1.5 font-semibold">SKU</th>
            <th className="px-2 py-1.5 font-semibold">Component</th>
            <th className="px-2 py-1.5 text-right font-semibold">Required</th>
            <th className="px-2 py-1.5 font-semibold">Status</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border-subtle">
          {materials.map((m) => (
            <tr key={m.id}>
              <td className="px-2 py-1 font-mono text-text-faint">{m.componentSku}</td>
              <td className="px-2 py-1">{m.componentName}</td>
              <td className="px-2 py-1 text-right tabular-nums">{m.requiredQuantity}</td>
              <td className="px-2 py-1">
                <StatusBadge kind={inferStatusKind(m.status)}>{m.status}</StatusBadge>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function Field({ label, value, mono }: { label: string; value: string | null; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between rounded-md border border-border-subtle px-3 py-1.5">
      <span className="text-xs uppercase tracking-wider text-text-muted">{label}</span>
      <span className={mono ? "font-mono text-xs" : ""}>{value ?? "—"}</span>
    </div>
  );
}
