import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Filter, Download } from "lucide-react";
import clsx from "clsx";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { FilterPanel, useFieldFilters, type FilterField } from "@/components/ui/FilterPanel";
import { downloadCsv } from "@/lib/csv";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";

interface WorkOrderBoardRow {
  workOrderId: string;
  workOrderNumber: string;
  salesOrderHeaderId: string | null;
  finishedProductSku: string;
  finishedProductName: string;
  plannedQuantity: string;
  completedQuantity: string;
  workOrderStatus: string;
  materialStatus: string;
  shortageMaterialsCount: number;
  shortageSummary: string | null;
  priority: string;
  updatedAt: string;
}

/**
 * Linda's main screen — every active and recent work order. Reads from
 * reporting's production_planning_board projection. Click-through to
 * detail with operation actions (complete / skip / setPriority) wired
 * via the BFF -cmd alias.
 */
export function WorkOrders() {
  const navigate = useNavigate();

  const { data, isLoading, error } = useQuery({
    queryKey: ["work-orders"],
    queryFn: () => apiGet<WorkOrderBoardRow[]>("/api/work-orders"),
  });

  const filterFields: FilterField<WorkOrderBoardRow>[] = [
    { key: "number", label: "WO #", get: (r) => r.workOrderNumber },
    { key: "product", label: "Product", get: (r) => `${r.finishedProductName} ${r.finishedProductSku}` },
    { key: "status", label: "Status", type: "select", get: (r) => r.workOrderStatus, optionLabel: (v) => v.replace(/_/g, " ") },
    { key: "materials", label: "Materials", type: "select", get: (r) => r.materialStatus, optionLabel: (v) => v.replace(/_/g, " ") },
    { key: "priority", label: "Priority", type: "select", get: (r) => r.priority },
  ];
  const filter = useFieldFilters(data ?? [], filterFields);

  const columns: Column<WorkOrderBoardRow>[] = [
    {
      key: "number",
      header: "WO #",
      width: "150px",
      sortAccessor: (r) => r.workOrderNumber,
      render: (r) => <span className="font-medium tabular-nums">{r.workOrderNumber}</span>,
    },
    {
      key: "product",
      header: "Product",
      sortAccessor: (r) => r.finishedProductName,
      render: (r) => (
        <div>
          <div className="text-text-primary">{r.finishedProductName}</div>
          <div className="text-[11px] text-text-muted tabular-nums">{r.finishedProductSku}</div>
        </div>
      ),
    },
    {
      key: "status",
      header: "Status",
      width: "140px",
      sortAccessor: (r) => r.workOrderStatus,
      render: (r) => {
        const s = statusForOrder(r.workOrderStatus);
        return <StatusPill label={s.label} tone={s.tone} />;
      },
    },
    {
      key: "material",
      header: "Materials",
      width: "150px",
      sortAccessor: (r) => r.materialStatus,
      render: (r) => {
        const tone = r.materialStatus === "reserved" ? "success"
          : r.materialStatus === "shortage" ? "error"
          : r.materialStatus === "partially_reserved" ? "warn"
          : "neutral";
        return <StatusPill label={r.materialStatus.replace(/_/g, " ")} tone={tone} />;
      },
    },
    {
      key: "progress",
      header: "Progress",
      width: "180px",
      sortAccessor: (r) => {
        const p = Math.max(0, Number(r.plannedQuantity));
        return p === 0 ? 0 : Math.min(1, Math.max(0, Number(r.completedQuantity)) / p);
      },
      render: (r) => <ProgressBar planned={r.plannedQuantity} completed={r.completedQuantity} />,
    },
    {
      key: "priority",
      header: "Priority",
      width: "110px",
      sortAccessor: (r) => priorityRank(r.priority),
      render: (r) => <PriorityPill priority={r.priority} />,
    },
    {
      key: "shortage",
      header: "Shortages",
      width: "100px",
      numeric: true,
      sortAccessor: (r) => r.shortageMaterialsCount,
      render: (r) => r.shortageMaterialsCount > 0
        ? <span className="text-status-error">{r.shortageMaterialsCount}</span>
        : <span className="text-text-muted">—</span>,
    },
    {
      key: "updated",
      header: "Updated",
      width: "110px",
      sortAccessor: (r) => new Date(r.updatedAt).getTime(),
      render: (r) => <span className="text-text-muted">{formatRelative(r.updatedAt)}</span>,
    },
  ];

  return (
    <>
      <PageHeader
        title="Work Orders"
        description="Production work orders. Reservation status and shortages drive Linda's daily triage."
        trail={[
          { label: "Manufacturing" },
          { label: "Work Orders" },
        ]}
        actions={
          <>
            <ActionButton
              icon={<Filter className="h-4 w-4" />}
              variant={filter.open ? "primary" : "secondary"}
              onClick={filter.toggle}
            >
              Filter
            </ActionButton>
            <ActionButton
              icon={<Download className="h-4 w-4" />}
              onClick={() => downloadCsv("work-orders.csv", filter.filtered)}
              disabled={filter.filtered.length === 0}
            >
              Export
            </ActionButton>
          </>
        }
      />

      <FilterPanel
        open={filter.open}
        rows={data ?? []}
        fields={filterFields}
        values={filter.values}
        onChange={filter.set}
        onClear={filter.clear}
        onClose={filter.close}
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load work orders: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={filter.filtered}
            rowKey={(r) => r.workOrderId}
            onRowClick={(r) => navigate(`/work-orders/${r.workOrderId}`)}
            loading={isLoading}
            emptyState={filter.active ? "No work orders match the filter." : "No work orders yet."}
          />
        )}

        {data && (
          <div className="mt-3 text-xs text-text-muted">
            Showing {filter.filtered.length} of {data.length} work orders.
          </div>
        )}
      </div>
    </>
  );
}

function ProgressBar({ planned, completed }: { planned: string; completed: string }) {
  const p = Math.max(0, Number(planned));
  const c = Math.max(0, Math.min(p, Number(completed)));
  const pct = p === 0 ? 0 : Math.round((c / p) * 100);
  return (
    <div className="flex items-center gap-2">
      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-bg-subtle">
        <div className="h-full bg-status-success" style={{ width: `${pct}%` }} />
      </div>
      <span className="w-16 text-right text-[11px] tabular-nums text-text-muted">
        {formatQty(completed)}/{formatQty(planned)}
      </span>
    </div>
  );
}

/** Sort rank for the priority column — urgent highest. */
function priorityRank(priority: string): number {
  switch (priority) {
    case "urgent": return 3;
    case "high": return 2;
    case "normal": return 1;
    case "low": return 0;
    default: return 1;
  }
}

function PriorityPill({ priority }: { priority: string }) {
  const tone = priority === "urgent" ? "error"
    : priority === "high" ? "warn"
    : priority === "low" ? "neutral"
    : "info";
  return (
    <span
      className={clsx(
        "inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-medium",
        tone === "error" && "border-status-error/30 bg-status-error-soft text-status-error",
        tone === "warn" && "border-status-warn/30 bg-status-warn-soft text-status-warn",
        tone === "neutral" && "border-status-neutral/30 bg-status-neutral-soft text-status-neutral",
        tone === "info" && "border-status-neutral/30 bg-bg-subtle text-text-muted",
      )}
    >
      {priority}
    </span>
  );
}

function formatQty(v: string | number | null | undefined): string {
  if (v == null) return "—";
  const n = typeof v === "string" ? Number(v) : v;
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 3 });
}

function formatRelative(iso: string): string {
  if (!iso) return "—";
  const then = new Date(iso).getTime();
  const diff = Math.max(0, Date.now() - then);
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  return `${Math.floor(hr / 24)}d ago`;
}
