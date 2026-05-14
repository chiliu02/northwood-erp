import { useQuery } from "@tanstack/react-query";
import { fetchStockItems } from "@/api/fetchers";
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
    </div>
  );
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
