import { useQuery } from "@tanstack/react-query";
import { fetchReplenishmentHistory, fetchStockItems } from "@/api/fetchers";
import type { ReplenishmentHistoryRow } from "@/api/types";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { truncateUuid } from "@/lib/utils";
import { PERSONAS } from "@/personas";

export function StockItems() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["stock-items"],
    queryFn: fetchStockItems,
    refetchInterval: 5_000,
  });
  const p = PERSONAS.mike;

  return (
    <div className="space-y-4">
      <div className="flex items-baseline gap-3">
        <h1 className="text-[28px] font-semibold tracking-tight">Stock items</h1>
        <span className="flex items-center gap-2 text-sm text-text-muted">
          <span className="h-1.5 w-1.5 rounded-full" style={{ background: p.accentVar }} aria-hidden />
          {p.name} · {p.role}
        </span>
        <span className="ml-auto text-xs text-text-faint">
          {isLoading ? "loading…" : `${data?.length ?? 0} item${data?.length === 1 ? "" : "s"}`}
        </span>
      </div>

      <p className="text-sm text-text-muted">
        Inventory's projection of the product master. Reorder thresholds are owned by product-service
        (Story 1.3) — this view is read-only.
      </p>

      {error && (
        <ErrorBanner context="inventory-service on :8083" message={String(error)} />
      )}

      <div className="overflow-hidden rounded-lg border border-border-subtle bg-bg-elevated">
        <table className="w-full text-sm">
          <thead className="border-b border-border-subtle bg-bg-base/50 text-left text-[11px] uppercase tracking-wider text-text-muted">
            <tr>
              <th className="px-4 py-2 font-semibold">SKU</th>
              <th className="px-4 py-2 font-semibold">Name</th>
              <th className="px-4 py-2 font-semibold">Type</th>
              <th className="px-4 py-2 font-semibold">UoM</th>
              <th className="px-4 py-2 text-right font-semibold">On hand</th>
              <th className="px-4 py-2 text-right font-semibold">Reserved</th>
              <th className="px-4 py-2 text-right font-semibold">Available</th>
              <th className="px-4 py-2 text-right font-semibold">Reorder pt</th>
              <th className="px-4 py-2 text-right font-semibold">Reorder qty</th>
              <th className="px-4 py-2 font-semibold">Tracking</th>
              <th className="px-4 py-2 font-semibold">Product id</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border-subtle">
            {(data ?? []).map((s) => (
              <tr key={s.stockItemId} className="hover:bg-bg-hover">
                <td className="px-4 py-2 font-mono">{s.productSku}</td>
                <td className="px-4 py-2">{s.productName}</td>
                <td className="px-4 py-2">
                  <StatusBadge kind="neutral">{s.productType}</StatusBadge>
                </td>
                <td className="px-4 py-2 text-text-muted">{s.baseUomCode}</td>
                <td className="px-4 py-2 text-right tabular-nums">{Number(s.onHand)}</td>
                <td className="px-4 py-2 text-right tabular-nums text-text-muted">{Number(s.reserved)}</td>
                <td className="px-4 py-2 text-right tabular-nums font-medium">{Number(s.available)}</td>
                <td className="px-4 py-2 text-right tabular-nums">{s.reorderPoint}</td>
                <td className="px-4 py-2 text-right tabular-nums">{s.reorderQuantity}</td>
                <td className="px-4 py-2">
                  <StatusBadge kind={s.trackingMode === "tracked" ? "success" : "neutral"}>
                    {s.trackingMode}
                  </StatusBadge>
                </td>
                <td className="px-4 py-2 font-mono text-xs text-text-faint">{truncateUuid(s.productId)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {!isLoading && (data?.length ?? 0) === 0 && !error && (
          <p className="px-4 py-6 text-center text-sm text-text-faint">No stock items projected yet.</p>
        )}
      </div>

      <ReplenishmentActivity />
    </div>
  );
}

/**
 * §2.35 Slice F: "Replenishment activity" widget. Lists the most recent
 * auto-replenishment requests system-wide with their state. Driven by the
 * reporting.replenishment_history_view projection.
 */
function ReplenishmentActivity() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["replenishment-history"],
    queryFn: () => fetchReplenishmentHistory(20),
    refetchInterval: 5_000,
  });

  return (
    <section className="space-y-2">
      <div className="flex items-baseline gap-3">
        <h2 className="text-[18px] font-semibold tracking-tight">Replenishment activity</h2>
        <span className="text-xs text-text-faint">
          {isLoading ? "loading…" : `${data?.length ?? 0} recent`}
        </span>
        <span className="ml-auto text-xs text-text-muted">
          §2.35 — auto-raised when on-hand drops below the reorder point or a WO finds short raw materials.
        </span>
      </div>

      {error && <ErrorBanner context="reporting-service on :8087" message={String(error)} />}

      <div className="overflow-hidden rounded-lg border border-border-subtle bg-bg-elevated">
        <table className="w-full text-sm">
          <thead className="border-b border-border-subtle bg-bg-base/50 text-left text-[11px] uppercase tracking-wider text-text-muted">
            <tr>
              <th className="px-4 py-2 font-semibold">Request id</th>
              <th className="px-4 py-2 font-semibold">SKU</th>
              <th className="px-4 py-2 text-right font-semibold">Qty</th>
              <th className="px-4 py-2 font-semibold">Reason</th>
              <th className="px-4 py-2 font-semibold">Target</th>
              <th className="px-4 py-2 font-semibold">Status</th>
              <th className="px-4 py-2 font-semibold">Dispatched to</th>
              <th className="px-4 py-2 font-semibold">Requested</th>
              <th className="px-4 py-2 font-semibold">Dispatched</th>
              <th className="px-4 py-2 font-semibold">Fulfilled</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border-subtle">
            {(data ?? []).map((r) => (
              <ReplenishmentRow key={r.replenishmentRequestId} r={r} />
            ))}
          </tbody>
        </table>
        {!isLoading && (data?.length ?? 0) === 0 && !error && (
          <p className="px-4 py-6 text-center text-sm text-text-faint">
            No replenishments yet. Ship goods past a SKU's reorder point to see one appear here.
          </p>
        )}
      </div>
    </section>
  );
}

function ReplenishmentRow({ r }: { r: ReplenishmentHistoryRow }) {
  return (
    <tr className="hover:bg-bg-hover">
      <td className="px-4 py-2 font-mono text-xs text-text-faint">{truncateUuid(r.replenishmentRequestId)}</td>
      <td className="px-4 py-2 font-mono">{r.productSku ?? truncateUuid(r.productId)}</td>
      <td className="px-4 py-2 text-right tabular-nums">{Number(r.requestedQuantity)}</td>
      <td className="px-4 py-2 text-text-muted">{r.reason.replace(/_/g, " ")}</td>
      <td className="px-4 py-2">
        <StatusBadge kind="neutral">{r.targetService}</StatusBadge>
      </td>
      <td className="px-4 py-2">
        <StatusBadge kind={statusKind(r.status)}>{r.status}</StatusBadge>
      </td>
      <td className="px-4 py-2 font-mono text-xs text-text-faint">
        {r.dispatchedAggregateId ? truncateUuid(r.dispatchedAggregateId) : "—"}
      </td>
      <td className="px-4 py-2 text-xs text-text-muted">{formatTs(r.requestedAt)}</td>
      <td className="px-4 py-2 text-xs text-text-muted">{formatTs(r.dispatchedAt)}</td>
      <td className="px-4 py-2 text-xs text-text-muted">{formatTs(r.fulfilledAt)}</td>
    </tr>
  );
}

function statusKind(s: string): "neutral" | "success" | "warn" {
  if (s === "fulfilled") return "success";
  if (s === "dispatched") return "warn";
  return "neutral";
}

function formatTs(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

function ErrorBanner({ context, message }: { context: string; message: string }) {
  return (
    <div
      className="rounded-md border px-4 py-3 text-sm"
      style={{
        borderColor: "var(--color-state-error)",
        background: "color-mix(in srgb, var(--color-state-error) 12%, transparent)",
      }}
    >
      <p className="font-medium" style={{ color: "var(--color-state-error)" }}>
        Couldn't reach {context}
      </p>
      <p className="mt-1 text-text-muted">{message}</p>
    </div>
  );
}
