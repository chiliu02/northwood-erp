import { useQuery } from "@tanstack/react-query";
import { fetchAtp } from "@/api/fetchers";
import type { AvailableToPromiseRow } from "@/api/types";
import { MasterDetail } from "@/components/MasterDetail";
import { StatusBadge, inferStatusKind } from "@/components/ui/StatusBadge";
import { truncateUuid } from "@/lib/utils";

export function Atp() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["atp"],
    queryFn: fetchAtp,
    refetchInterval: 5_000,
  });

  return (
    <MasterDetail
      title="Available-to-promise"
      persona="mike"
      items={data}
      isLoading={isLoading}
      error={error}
      errorContext="reporting-service on :8087"
      rowKey={(r) => r.productId}
      renderRow={(r) => (
        <div className="space-y-1">
          <div className="flex items-center justify-between">
            <span className="font-mono text-text-primary">{r.productSku}</span>
            <StatusBadge kind={inferStatusKind(r.stockStatus)}>{r.stockStatus}</StatusBadge>
          </div>
          <div className="flex items-center justify-between text-xs text-text-muted">
            <span className="truncate">{r.productName}</span>
            <span className="tabular-nums">avail {r.availableQuantity}</span>
          </div>
        </div>
      )}
      renderDetail={(r) => <AtpDetail atp={r} />}
    />
  );
}

function AtpDetail({ atp }: { atp: AvailableToPromiseRow }) {
  return (
    <div className="space-y-5">
      <header className="space-y-1">
        <div className="flex items-center gap-3">
          <h2 className="font-mono text-xl font-semibold">{atp.productSku}</h2>
          <StatusBadge kind={inferStatusKind(atp.stockStatus)}>{atp.stockStatus}</StatusBadge>
        </div>
        <p className="text-sm text-text-muted">{atp.productName}</p>
      </header>

      <section>
        <h3 className="mb-3 text-sm font-semibold text-text-primary">Composition</h3>
        <div className="space-y-2 text-sm">
          <Cell label="On hand"                value={atp.onHandQuantity}        kind="success" />
          <Cell label="Reserved (sales)"       value={atp.reservedForSales}      kind="warn" subtract />
          <Cell label="Reserved (production)"  value={atp.reservedForProduction} kind="warn" subtract />
          <Cell label="Available to promise"   value={atp.availableQuantity}     kind="terminal" emphasize />
          <Cell label="Incoming (production)"  value={atp.incomingFromProduction} kind="active" />
          <Cell label="Incoming (purchase)"    value={atp.incomingFromPurchase}   kind="active" />
        </div>
      </section>

      <section className="grid grid-cols-2 gap-2 text-sm">
        <Field label="earliest available" value={atp.earliestAvailableDate} />
        <Field label="updated" value={atp.updatedAt} mono />
      </section>

      <section className="text-xs text-text-faint">
        <p>product id: <span className="font-mono">{truncateUuid(atp.productId)}</span></p>
      </section>
    </div>
  );
}

function Cell({ label, value, kind, subtract, emphasize }: {
  label: string; value: string;
  kind: "success" | "warn" | "active" | "terminal";
  subtract?: boolean; emphasize?: boolean;
}) {
  const colour = `var(--color-state-${kind})`;
  return (
    <div className={`flex items-center justify-between rounded-md border px-3 py-2 ${emphasize ? "" : "border-border-subtle"}`}
         style={emphasize ? { borderColor: colour, background: `color-mix(in srgb, ${colour} 8%, transparent)` } : undefined}>
      <span className={`text-xs uppercase tracking-wider ${emphasize ? "font-semibold" : "text-text-muted"}`}
            style={emphasize ? { color: colour } : undefined}>
        {label}
      </span>
      <span className={`tabular-nums ${emphasize ? "text-lg font-semibold" : "font-medium"}`} style={emphasize ? { color: colour } : undefined}>
        {subtract ? "−" : ""}{value}
      </span>
    </div>
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
