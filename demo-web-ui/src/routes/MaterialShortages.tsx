import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { fetchMaterialShortages } from "@/api/fetchers";
import type { MaterialShortageRow } from "@/api/types";
import { MasterDetail } from "@/components/MasterDetail";
import { StatusBadge, inferStatusKind } from "@/components/ui/StatusBadge";
import { cn, truncateUuid } from "@/lib/utils";

export function MaterialShortages() {
  const [includeResolved, setIncludeResolved] = useState(false);
  const { data, isLoading, error } = useQuery({
    queryKey: ["material-shortages", includeResolved],
    queryFn: () => fetchMaterialShortages(includeResolved),
    refetchInterval: 5_000,
  });

  return (
    <div>
      <div className="mb-3 flex items-center gap-2">
        <Toggle
          active={!includeResolved}
          onClick={() => setIncludeResolved(false)}
          label="Active"
        />
        <Toggle
          active={includeResolved}
          onClick={() => setIncludeResolved(true)}
          label="All"
        />
      </div>
      <MasterDetail
        title="Material shortages"
        persona="linda"
        items={data}
        isLoading={isLoading}
        error={error}
        errorContext="reporting-service on :8087"
        emptyMessage={includeResolved ? "No shortages on record." : "No active shortages — everything is covered."}
        rowKey={(s) => s.materialProductId}
        renderRow={(s) => (
          <div className="space-y-1">
            <div className="flex items-center justify-between">
              <span className="font-mono text-text-primary">{s.materialSku}</span>
              <StatusBadge kind={inferStatusKind(s.status)}>{s.status}</StatusBadge>
            </div>
            <div className="flex items-center justify-between text-xs text-text-muted">
              <span className="truncate">{s.materialName}</span>
              <span className="tabular-nums">short {s.shortageQuantity}</span>
            </div>
          </div>
        )}
        renderDetail={(s) => <ShortageDetail row={s} />}
      />
    </div>
  );
}

function Toggle({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "rounded-md border px-3 py-1 text-xs font-medium uppercase tracking-wider",
        active
          ? "border-state-active bg-bg-elevated text-text-primary"
          : "border-border-subtle text-text-muted hover:bg-bg-hover"
      )}
      style={active ? { borderColor: "var(--color-state-active)" } : undefined}
    >
      {label}
    </button>
  );
}

function ShortageDetail({ row }: { row: MaterialShortageRow }) {
  return (
    <div className="space-y-5">
      <header className="space-y-1">
        <div className="flex items-center gap-3">
          <h2 className="font-mono text-xl font-semibold">{row.materialSku}</h2>
          <StatusBadge kind={inferStatusKind(row.status)}>{row.status}</StatusBadge>
        </div>
        <p className="text-sm text-text-muted">{row.materialName}</p>
      </header>

      <StatusWalk current={row.status} />

      <section className="grid grid-cols-2 gap-2 text-sm lg:grid-cols-3">
        <Field label="required"  value={row.requiredQuantity} />
        <Field label="available" value={row.availableQuantity} />
        <Field label="shortage"  value={row.shortageQuantity} kind="warn" />
        <Field label="WOs affected" value={String(row.affectedWorkOrdersCount)} />
        <Field label="SOs affected" value={String(row.affectedSalesOrdersCount)} />
        <Field label="open POs"  value={String(row.openPurchaseOrdersCount)} />
        <Field label="incoming"  value={row.incomingPurchaseQuantity} />
        <Field label="ETA"       value={row.expectedReceiptDate ?? "—"} />
        <Field label="updated"   value={row.updatedAt ?? "—"} mono />
      </section>

      <section className="text-xs text-text-faint">
        <p>product id: <span className="font-mono">{truncateUuid(row.materialProductId)}</span></p>
      </section>
    </div>
  );
}

function StatusWalk({ current }: { current: string }) {
  const stages = ["open", "purchase_requested", "purchase_ordered", "resolved"];
  const currentIdx = stages.indexOf(current);
  return (
    <div className="flex items-center gap-1">
      {stages.map((s, i) => {
        const reached = i <= currentIdx;
        const isCurrent = i === currentIdx;
        const colour = isCurrent ? "var(--color-state-active)" :
                       reached ? "var(--color-state-success)" : "var(--color-state-pending)";
        return (
          <div key={s} className="flex flex-1 flex-col items-center gap-1.5">
            <div className="flex w-full items-center gap-1">
              <span
                className="h-2.5 w-2.5 shrink-0 rounded-full border-2"
                style={{ borderColor: colour, background: reached ? colour : "transparent" }}
              />
              {i < stages.length - 1 && (
                <span className="h-px flex-1" style={{ background: colour, opacity: 0.5 }} />
              )}
            </div>
            <span className="text-[10px] uppercase tracking-wider text-text-muted">{s}</span>
          </div>
        );
      })}
    </div>
  );
}

function Field({ label, value, mono, kind }: { label: string; value: string; mono?: boolean; kind?: "warn" }) {
  return (
    <div className="rounded-md border border-border-subtle px-3 py-2">
      <p className="text-xs uppercase tracking-wider text-text-muted">{label}</p>
      <p className={cn("mt-0.5 font-medium tabular-nums", mono && "font-mono text-xs")}
         style={kind === "warn" ? { color: "var(--color-state-warn)" } : undefined}>
        {value}
      </p>
    </div>
  );
}
