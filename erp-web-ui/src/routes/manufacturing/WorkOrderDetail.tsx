import { useState } from "react";
import { useParams } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, SkipForward, ArrowUp } from "lucide-react";
import clsx from "clsx";
import { apiGet, apiPost, ApiError } from "@/lib/api";
import { DetailLayout } from "@/components/ui/DetailLayout";
import { ActionButton } from "@/components/ui/ActionButton";
import { AuditTab } from "@/components/ui/AuditTab";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { FormSection, ReadOnlyField, Field } from "@/components/ui/FormSection";
import { NumberInput, TextArea, Select } from "@/components/ui/Form";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";
import { DataGrid, type Column } from "@/components/ui/DataGrid";

interface Material {
  id: string;
  componentSku: string;
  componentName: string;
  requiredQuantity: string;
  unitCost: string;
  status: string;
}

interface Operation {
  id: string;
  operationSequence: number;
  operationCode: string;
  description: string;
  plannedSetupMinutes: string;
  plannedRunMinutes: string;
  status: string;            // 'planned' | 'in_progress' | 'completed' | 'skipped'
  actualMinutes: string;
  startedAt: string | null;
  completedAt: string | null;
}

interface WorkOrder {
  workOrderId: string;
  workOrderNumber: string;
  parentWorkOrderId: string | null;
  finishedProductSku: string;
  finishedProductName: string;
  plannedQuantity: string;
  status: string;
  materialStatus: string;
  completedQuantity: string;
  actualStartAt: string | null;
  actualCompletedAt: string | null;
  version: number;
  materials: Material[];
  operations: Operation[];
}

type DialogKind =
  | { kind: "complete"; op: Operation }
  | { kind: "skip"; op: Operation }
  | { kind: "priority" }
  | null;

const PRIORITIES = ["low", "normal", "high", "urgent"];

/**
 * Linda's WO detail. Tabs: Overview, Materials, Operations, Audit.
 * Operation rows have inline Complete + Skip buttons, gated on the
 * operation's status (only planned / in_progress are actionable).
 * SetPriority sits in the page header — pure CQRS read-side, doesn't
 * touch the WO aggregate.
 */
export function WorkOrderDetail() {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<DialogKind>(null);

  const { data, isLoading, error: fetchError } = useQuery({
    queryKey: ["work-order-aggregate", id],
    queryFn: () => apiGet<WorkOrder>(`/api/work-orders-cmd/${id}`),
    enabled: !!id,
  });

  function close() { setDialog(null); }
  function refresh() {
    queryClient.invalidateQueries({ queryKey: ["work-order-aggregate", id] });
    queryClient.invalidateQueries({ queryKey: ["work-orders"] });
  }

  if (isLoading) {
    return <div className="flex h-full items-center justify-center text-sm text-text-muted">Loading work order…</div>;
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
  const isOpen = !["completed", "closed", "cancelled"].includes(data.status);

  const materialColumns: Column<Material>[] = [
    { key: "sku", header: "SKU", width: "200px", render: (m) => <span className="font-medium tabular-nums">{m.componentSku}</span> },
    { key: "name", header: "Component", render: (m) => m.componentName },
    { key: "qty", header: "Required Qty", numeric: true, width: "130px", render: (m) => formatQty(m.requiredQuantity) },
    { key: "cost", header: "Unit Cost", numeric: true, width: "110px", render: (m) => formatMoney(m.unitCost) },
    { key: "status", header: "Status", width: "140px", render: (m) => <StatusPill label={m.status.replace(/_/g, " ")} tone="neutral" /> },
  ];

  const operationColumns: Column<Operation>[] = [
    { key: "seq", header: "#", width: "50px", numeric: true, render: (o) => <span className="font-medium tabular-nums">{o.operationSequence}</span> },
    { key: "code", header: "Code", width: "110px", render: (o) => <span className="tabular-nums">{o.operationCode}</span> },
    { key: "desc", header: "Description", render: (o) => o.description || "—" },
    { key: "planned", header: "Planned (min)", numeric: true, width: "110px", render: (o) => (
      <span className="text-text-muted">
        {formatMins(Number(o.plannedSetupMinutes) + Number(o.plannedRunMinutes))}
      </span>
    ) },
    { key: "actual", header: "Actual (min)", numeric: true, width: "110px", render: (o) => (
      Number(o.actualMinutes) > 0
        ? <span className="tabular-nums">{formatMins(Number(o.actualMinutes))}</span>
        : <span className="text-text-faint">—</span>
    ) },
    { key: "status", header: "Status", width: "120px", render: (o) => <OperationStatus status={o.status} /> },
    {
      key: "actions",
      header: "",
      width: "180px",
      render: (o) => {
        const actionable = isOpen && (o.status === "planned" || o.status === "in_progress");
        if (!actionable) return null;
        return (
          <div className="flex justify-end gap-1.5">
            <ActionButton
              variant="primary"
              icon={<Check className="h-4 w-4" />}
              onClick={(e) => { e.stopPropagation(); setDialog({ kind: "complete", op: o }); }}
              className="!h-8 !px-2 text-xs"
            >
              Complete
            </ActionButton>
            <ActionButton
              icon={<SkipForward className="h-4 w-4" />}
              onClick={(e) => { e.stopPropagation(); setDialog({ kind: "skip", op: o }); }}
              requiresRole="production_supervisor"
              className="!h-8 !px-2 text-xs"
            >
              Skip
            </ActionButton>
          </div>
        );
      },
    },
  ];

  return (
    <>
      <DetailLayout
        trail={[
          { label: "Manufacturing" },
          { label: "Work Orders", to: "/work-orders" },
          { label: data.workOrderNumber },
        ]}
        title={data.workOrderNumber}
        subtitle={`${data.finishedProductSku} — ${data.finishedProductName}${data.parentWorkOrderId ? " (sub-assembly)" : ""}`}
        status={status}
        actions={
          isOpen && (
            <ActionButton
              icon={<ArrowUp className="h-4 w-4" />}
              onClick={() => setDialog({ kind: "priority" })}
              requiresRole="production_planner"
            >
              Set priority
            </ActionButton>
          )
        }
        tabs={[
          {
            key: "overview",
            label: "Overview",
            content: (
              <div className="grid gap-4 lg:grid-cols-2">
                <FormSection title="Work order">
                  <ReadOnlyField label="WO #" value={<span className="font-medium tabular-nums">{data.workOrderNumber}</span>} />
                  <ReadOnlyField label="Status" value={<StatusPill label={status.label} tone={status.tone} />} />
                  <ReadOnlyField label="Planned qty" value={<span className="tabular-nums">{formatQty(data.plannedQuantity)}</span>} />
                  <ReadOnlyField label="Completed qty" value={<span className="tabular-nums">{formatQty(data.completedQuantity)}</span>} />
                  <ReadOnlyField label="Started" value={data.actualStartAt ? formatTimestamp(data.actualStartAt) : "—"} />
                  <ReadOnlyField label="Completed" value={data.actualCompletedAt ? formatTimestamp(data.actualCompletedAt) : "—"} />
                </FormSection>
                <FormSection title="Product">
                  <ReadOnlyField label="SKU" value={<span className="font-medium tabular-nums">{data.finishedProductSku}</span>} />
                  <ReadOnlyField label="Name" value={data.finishedProductName} />
                  <ReadOnlyField
                    label="Hierarchy"
                    value={data.parentWorkOrderId ? "Sub-assembly child" : "Top-level WO"}
                    fullWidth
                  />
                </FormSection>
                <FormSection title="Materials">
                  <ReadOnlyField label="Reservation" value={<StatusPill label={data.materialStatus.replace(/_/g, " ")} tone={
                    data.materialStatus === "reserved" ? "success"
                    : data.materialStatus === "shortage" ? "error"
                    : data.materialStatus === "partially_reserved" ? "warn"
                    : "neutral"
                  } />} />
                  <ReadOnlyField label="Required components" value={<span className="tabular-nums">{data.materials.length}</span>} />
                </FormSection>
                <FormSection title="Operations">
                  <ReadOnlyField label="Total" value={<span className="tabular-nums">{data.operations.length}</span>} />
                  <ReadOnlyField
                    label="Completed"
                    value={<span className="tabular-nums">
                      {data.operations.filter((o) => o.status === "completed" || o.status === "skipped").length}
                      <span className="text-text-faint"> / {data.operations.length}</span>
                    </span>}
                  />
                </FormSection>
              </div>
            ),
          },
          {
            key: "materials",
            label: "Materials",
            badge: data.materials.length,
            content: <DataGrid columns={materialColumns} rows={data.materials} rowKey={(m) => m.id} />,
          },
          {
            key: "operations",
            label: "Operations",
            badge: data.operations.length,
            content: <DataGrid columns={operationColumns} rows={data.operations} rowKey={(o) => o.id} />,
          },
          {
            key: "audit",
            label: "Audit",
            content: <AuditTab aggregateId={id} />,
          },
        ]}
      />

      <CompleteOperationDialog
        open={dialog?.kind === "complete"}
        op={dialog?.kind === "complete" ? dialog.op : null}
        wo={data}
        onClose={close}
        onSuccess={() => { close(); refresh(); }}
      />
      <SkipOperationDialog
        open={dialog?.kind === "skip"}
        op={dialog?.kind === "skip" ? dialog.op : null}
        wo={data}
        onClose={close}
        onSuccess={() => { close(); refresh(); }}
      />
      <SetPriorityDialog
        open={dialog?.kind === "priority"}
        wo={data}
        onClose={close}
        onSuccess={() => { close(); refresh(); }}
      />
    </>
  );
}

// ----- dialogs -----

function CompleteOperationDialog({ open, op, wo, onClose, onSuccess }: {
  open: boolean; op: Operation | null; wo: WorkOrder; onClose: () => void; onSuccess: () => void;
}) {
  const [actualMinutes, setActualMinutes] = useState("");
  const [error, setError] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: () => apiPost(
      `/api/work-orders-cmd/${wo.workOrderId}/operations/${op!.operationSequence}/complete`,
      { actualMinutes: Number(actualMinutes) }
    ),
    onSuccess,
    onError: (err) => setError(err instanceof ApiError ? err.message : "Failed."),
  });

  function submit() {
    if (!actualMinutes || Number(actualMinutes) < 0) {
      setError("Actual minutes is required.");
      return;
    }
    setError(null);
    mutation.mutate();
  }

  if (!op) return null;
  const planned = Number(op.plannedSetupMinutes) + Number(op.plannedRunMinutes);

  return (
    <ConfirmDialog
      open={open}
      title={`Complete operation ${op.operationSequence}: ${op.operationCode}`}
      message={
        <>
          Marks operation <strong>{op.operationSequence}</strong> ({op.operationCode}) on{" "}
          <strong>{wo.workOrderNumber}</strong> as completed. If this is the last operation
          and no sub-assembly children are still pending, the WO transitions to{" "}
          <code>completed</code> in the same transaction.
        </>
      }
      confirmLabel="Complete operation"
      busy={mutation.isPending}
      onCancel={onClose}
      onConfirm={submit}
      body={
        <div className="space-y-3">
          <Field label="Actual minutes" required hint={`Planned: ${formatMins(planned)} minutes`}>
            <NumberInput
              min="0"
              step="0.1"
              value={actualMinutes}
              onChange={(e) => setActualMinutes(e.target.value)}
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
  );
}

function SkipOperationDialog({ open, op, wo, onClose, onSuccess }: {
  open: boolean; op: Operation | null; wo: WorkOrder; onClose: () => void; onSuccess: () => void;
}) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: () => apiPost(
      `/api/work-orders-cmd/${wo.workOrderId}/operations/${op!.operationSequence}/skip`,
      { reason: reason.trim() }
    ),
    onSuccess,
    onError: (err) => setError(err instanceof ApiError ? err.message : "Failed."),
  });

  if (!op) return null;

  return (
    <ConfirmDialog
      open={open}
      title={`Skip operation ${op.operationSequence}: ${op.operationCode}?`}
      message={
        <>
          Skips operation <strong>{op.operationSequence}</strong> on <strong>{wo.workOrderNumber}</strong>.
          From the WO state machine's perspective skipped == completed: the next op can run, and if
          this is the last op the WO closes. No <code>OperationCompleted</code> event fires for
          skipped ops.
        </>
      }
      confirmLabel="Skip operation"
      variant="danger"
      busy={mutation.isPending}
      onCancel={onClose}
      onConfirm={() => mutation.mutate()}
      body={
        <div className="space-y-3">
          <Field label="Reason" hint="Why is this op being skipped? Helps the audit log later.">
            <TextArea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. inspection step covered by upstream sub-assembly"
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
  );
}

function SetPriorityDialog({ open, wo, onClose, onSuccess }: {
  open: boolean; wo: WorkOrder; onClose: () => void; onSuccess: () => void;
}) {
  const [priority, setPriority] = useState("normal");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: () => apiPost(
      `/api/work-orders-cmd/${wo.workOrderId}/priority`,
      { priority, reason: reason.trim() }
    ),
    onSuccess,
    onError: (err) => setError(err instanceof ApiError ? err.message : "Failed."),
  });

  return (
    <ConfirmDialog
      open={open}
      title="Set priority"
      message={
        <>
          Updates the priority on <strong>{wo.workOrderNumber}</strong>. Pure read-side change — emits
          <code> manufacturing.WorkOrderPriorityChanged</code>; reporting's production board projects
          it onto the WO row.
        </>
      }
      confirmLabel="Save"
      busy={mutation.isPending}
      onCancel={onClose}
      onConfirm={() => mutation.mutate()}
      body={
        <div className="space-y-3">
          <Field label="Priority" required>
            <Select value={priority} onChange={(e) => setPriority(e.target.value)}>
              {PRIORITIES.map((p) => <option key={p} value={p}>{p}</option>)}
            </Select>
          </Field>
          <Field label="Reason">
            <TextArea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. customer escalation; date pulled forward"
              rows={2}
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
  );
}

function OperationStatus({ status }: { status: string }) {
  const tone =
    status === "completed" ? "success" :
    status === "skipped" ? "neutral" :
    status === "in_progress" ? "warn" :
    "info";
  return (
    <span
      className={clsx(
        "inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-medium",
        tone === "success" && "border-status-success/30 bg-status-success-soft text-status-success",
        tone === "warn" && "border-status-warn/30 bg-status-warn-soft text-status-warn",
        tone === "info" && "border-status-info/30 bg-status-info-soft text-status-info",
        tone === "neutral" && "border-status-neutral/30 bg-status-neutral-soft text-status-neutral",
      )}
    >
      {status}
    </span>
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
function formatMins(v: number): string {
  if (Number.isNaN(v)) return "—";
  return v.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 1 });
}
function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleString("en-AU");
}
