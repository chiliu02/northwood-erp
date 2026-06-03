import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { RefreshCw, AlertTriangle } from "lucide-react";
import clsx from "clsx";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";

interface BoardRow {
  workOrderId: string;
  workOrderNumber: string;
  finishedProductSku: string;
  finishedProductName: string;
  plannedQuantity: string;
  completedQuantity: string;
  workOrderStatus: string;
  materialStatus: string;
  shortageMaterialsCount: number;
  shortageSummary: string | null;
  priority: string;
}

const COLUMNS: Array<{ status: string; label: string }> = [
  { status: "released",     label: "Released" },
  { status: "in_progress",  label: "In Progress" },
  { status: "completed",    label: "Completed" },
];

/**
 * Linda's at-a-glance view. Three swim lanes (released / in progress /
 * completed) of WO cards — same data as /work-orders but optimised for
 * scanning rather than acting. Click-through goes to the same detail
 * page where operations are completed.
 */
export function ProductionBoard() {
  const navigate = useNavigate();
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["work-orders"],
    queryFn: () => apiGet<BoardRow[]>("/api/work-orders"),
  });

  const grouped = COLUMNS.map(({ status, label }) => ({
    status,
    label,
    rows: (data ?? []).filter((r) => r.workOrderStatus === status),
  }));

  return (
    <>
      <PageHeader
        title="Production Board"
        description="Scan view of all open work orders. Click a card to drill into operations."
        trail={[
          { label: "Manufacturing" },
          { label: "Production Board" },
        ]}
        actions={
          <ActionButton icon={<RefreshCw className="h-4 w-4" />} onClick={() => refetch()}>
            Refresh
          </ActionButton>
        }
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load: {(error as Error).message}
          </div>
        ) : isLoading ? (
          <div className="rounded-md border border-border-default bg-bg-surface px-4 py-12 text-center text-sm text-text-muted">
            Loading work orders…
          </div>
        ) : (
          <div className="grid gap-4 lg:grid-cols-3">
            {grouped.map((col) => (
              <div key={col.status} className="rounded-md border border-border-default bg-bg-surface">
                <div className="flex items-center justify-between border-b border-border-default px-4 py-2">
                  <h3 className="text-xs font-semibold uppercase tracking-wide text-text-muted">
                    {col.label}
                  </h3>
                  <span className="rounded-full bg-bg-subtle px-2 py-0.5 text-[11px] font-medium text-text-muted">
                    {col.rows.length}
                  </span>
                </div>
                <div className="flex flex-col gap-2 p-3 max-h-[calc(100vh-220px)] overflow-y-auto scrollbar-thin">
                  {col.rows.length === 0 ? (
                    <div className="py-8 text-center text-xs text-text-faint">No WOs in this state.</div>
                  ) : (
                    col.rows.map((r) => <BoardCard key={r.workOrderId} row={r} onClick={() => navigate(`/work-orders/${r.workOrderId}`)} />)
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </>
  );
}

function BoardCard({ row, onClick }: { row: BoardRow; onClick: () => void }) {
  const matTone =
    row.materialStatus === "reserved" ? "border-status-success/30 bg-status-success-soft text-status-success" :
    row.materialStatus === "shortage" ? "border-status-error/30 bg-status-error-soft text-status-error" :
    row.materialStatus === "partially_reserved" ? "border-status-warn/30 bg-status-warn-soft text-status-warn" :
    "border-border-default bg-bg-subtle text-text-muted";
  const priorityTone =
    row.priority === "urgent" ? "border-status-error/30 bg-status-error-soft text-status-error" :
    row.priority === "high" ? "border-status-warn/30 bg-status-warn-soft text-status-warn" :
    "border-border-default bg-bg-subtle text-text-muted";
  const planned = Math.max(0, Number(row.plannedQuantity));
  const completed = Math.max(0, Math.min(planned, Number(row.completedQuantity)));
  const pct = planned === 0 ? 0 : Math.round((completed / planned) * 100);

  return (
    <button
      type="button"
      onClick={onClick}
      className="group flex flex-col rounded-md border border-border-default bg-bg-surface p-3 text-left transition hover:border-border-strong"
    >
      <div className="flex items-start justify-between gap-2">
        <div>
          <div className="text-sm font-semibold text-text-primary">{row.workOrderNumber}</div>
          <div className="mt-0.5 text-xs text-text-muted tabular-nums">{row.finishedProductSku}</div>
        </div>
        <span className={clsx(
          "rounded-full border px-1.5 py-0.5 text-[10px] font-medium",
          priorityTone
        )}>
          {row.priority}
        </span>
      </div>

      <div className="mt-2 line-clamp-2 text-xs text-text-secondary">{row.finishedProductName}</div>

      <div className="mt-3 flex items-center gap-2">
        <div className="h-1 flex-1 overflow-hidden rounded-full bg-bg-subtle">
          <div className="h-full bg-status-success" style={{ width: `${pct}%` }} />
        </div>
        <span className="w-16 shrink-0 text-right text-[11px] tabular-nums text-text-muted">
          {formatQty(completed)}/{formatQty(planned)}
        </span>
      </div>

      <div className="mt-3 flex items-center justify-between gap-2">
        <span className={clsx(
          "rounded-full border px-2 py-0.5 text-[10px] font-medium",
          matTone
        )}>
          {row.materialStatus.replace(/_/g, " ")}
        </span>
        {row.shortageMaterialsCount > 0 && (
          <span className="flex items-center gap-1 text-[11px] text-status-error">
            <AlertTriangle className="h-3 w-3" />
            {row.shortageMaterialsCount}
          </span>
        )}
      </div>
    </button>
  );
}

function formatQty(v: number | string): string {
  const n = typeof v === "string" ? Number(v) : v;
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 3 });
}
