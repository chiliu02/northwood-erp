import { useEffect, useState } from "react";
import { Activity, AlertTriangle, CheckCircle2, Clock, ExternalLink, RotateCw, WifiOff } from "lucide-react";
import { useSagaStream, type SagaRow } from "@/sagas/stream";
import {
  SAGA_CATALOGS,
  isSideRail,
  isTerminal,
  stageIndex,
  type SagaCatalog,
} from "@/sagas/catalog";
import { PERSONAS } from "@/personas";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { cn, truncateUuid } from "@/lib/utils";
import { traceExploreUrl } from "@/lib/tracing";

export function SagaConsole() {
  // Single aggregated stream from the BFF — one EventSource for all three
  // saga types. Each column filters by sagaType.
  const { rows, isLive, error } = useSagaStream({ basePath: "/api/sagas" });

  return (
    <div className="space-y-4">
      <header className="flex items-baseline gap-3">
        <h1 className="text-[28px] font-semibold tracking-tight">Saga console</h1>
        <p className="text-sm text-text-muted">
          Live state of every running saga across the three services. Rows pulse on update.
        </p>
        <div className="ml-auto">
          <ConnectionPill isLive={isLive} error={error} />
        </div>
      </header>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        {SAGA_CATALOGS.map((catalog) => (
          <SagaColumn
            key={catalog.type}
            catalog={catalog}
            rows={rows.filter((r) => r.sagaType === catalog.type)}
          />
        ))}
      </div>
    </div>
  );
}

function SagaColumn({ catalog, rows }: { catalog: SagaCatalog; rows: SagaRow[] }) {
  const persona = PERSONAS[catalog.persona];

  const open = rows.filter((r) => !isTerminal(catalog, r.state));
  const done = rows.filter((r) => isTerminal(catalog, r.state));

  return (
    <section className="flex flex-col rounded-lg border border-border-subtle bg-bg-elevated">
      <header
        className="flex items-center justify-between border-b border-border-subtle px-4 py-3"
        style={{ borderTop: `2px solid ${persona.accentVar}` }}
      >
        <div className="flex items-center gap-2">
          <span className="h-1.5 w-1.5 rounded-full" style={{ background: persona.accentVar }} aria-hidden />
          <h2 className="text-sm font-semibold uppercase tracking-wider">{catalog.label}</h2>
          <span className="text-xs text-text-faint">{catalog.service}-service</span>
        </div>
        <span className="text-xs text-text-faint tabular-nums">{rows.length}</span>
      </header>

      <div className="max-h-[calc(100vh-14rem)] flex-1 overflow-y-auto scrollbar-thin">
        {rows.length === 0 && (
          <div className="px-4 py-8 text-center text-sm text-text-faint">
            No sagas yet — drive a scenario to see this column light up.
          </div>
        )}

        {open.map((row) => (
          <SagaCard key={row.sagaId} row={row} catalog={catalog} />
        ))}

        {done.length > 0 && (
          <details className="border-t border-border-subtle">
            <summary className="cursor-pointer list-none px-4 py-2.5 text-xs uppercase tracking-wider text-text-muted hover:bg-bg-hover">
              {done.length} completed
            </summary>
            {done.map((row) => (
              <SagaCard key={row.sagaId} row={row} catalog={catalog} faded />
            ))}
          </details>
        )}
      </div>
    </section>
  );
}

function ConnectionPill({ isLive, error }: { isLive: boolean; error: string | null }) {
  if (error && !isLive) {
    return (
      <span className="flex items-center gap-1 text-[11px] uppercase tracking-wider" style={{ color: "var(--color-state-error)" }}>
        <WifiOff className="h-3 w-3" /> bff offline
      </span>
    );
  }
  if (isLive) {
    return (
      <span className="flex items-center gap-1 text-[11px] uppercase tracking-wider" style={{ color: "var(--color-state-success)" }}>
        <Activity className="h-3 w-3 animate-pulse" /> live · single stream
      </span>
    );
  }
  return (
    <span className="flex items-center gap-1 text-[11px] uppercase tracking-wider text-text-muted">
      <Clock className="h-3 w-3" /> polling · bff
    </span>
  );
}

function SagaCard({ row, catalog, faded }: {
  row: SagaRow;
  catalog: SagaCatalog;
  faded?: boolean;
}) {
  const flashing = useFlash(row._justUpdated);
  const sideRail = isSideRail(catalog, row.state);
  const idx = stageIndex(catalog, row.state);

  return (
    <div
      className={cn(
        "border-b border-border-subtle px-4 py-3 transition-colors duration-700 last:border-b-0",
        faded && "opacity-60"
      )}
      style={
        flashing
          ? { background: "color-mix(in srgb, var(--color-state-active) 16%, transparent)" }
          : undefined
      }
    >
      <div className="mb-2 flex items-center justify-between gap-2">
        <span className="flex items-center gap-2">
          <span className="font-mono text-xs text-text-muted">{truncateUuid(row.domainKey)}</span>
          <TraceLink traceId={row.traceId} />
        </span>
        {/* Pulse the badge when the saga state transitions; the existing
            tint-fade on the card background reinforces the same moment. */}
        <span
          key={row.state}
          className={cn("inline-flex origin-center", flashing && "northwood-pulse")}
        >
          <StateBadge state={row.state} sideRail={sideRail} terminal={isTerminal(catalog, row.state)} />
        </span>
      </div>

      {sideRail || idx < 0 ? (
        <SideRailNotice state={row.state} />
      ) : (
        <StageDots stages={catalog.forwardStages} currentIndex={idx} />
      )}

      <div className="mt-2 flex items-center justify-between text-[11px] text-text-faint">
        <span className="truncate">
          {row.currentStep ? <span className="font-mono">{row.currentStep}</span> : "—"}
        </span>
        <span className="flex items-center gap-2">
          {row.retryCount > 0 && (
            <span className="flex items-center gap-1" style={{ color: "var(--color-state-warn)" }}>
              <RotateCw className="h-3 w-3" /> retry {row.retryCount}
            </span>
          )}
          <span title={row.updatedAt ?? ""}>v{row.version}</span>
        </span>
      </div>

      {row.lastError && (
        <p
          className="mt-2 truncate text-[11px]"
          style={{ color: "var(--color-state-error)" }}
          title={row.lastError}
        >
          <AlertTriangle className="inline h-3 w-3" /> {row.lastError}
        </p>
      )}
    </div>
  );
}

function StateBadge({ state, sideRail, terminal }: {
  state: string;
  sideRail: boolean;
  terminal: boolean;
}) {
  if (terminal) {
    if (state === "completed") return <StatusBadge kind="terminal"><CheckCircle2 className="-ml-0.5 inline h-3 w-3" /> {state}</StatusBadge>;
    if (state === "failed")    return <StatusBadge kind="error">{state}</StatusBadge>;
    return <StatusBadge kind="neutral">{state}</StatusBadge>;
  }
  if (sideRail) {
    return <StatusBadge kind="warn">{state}</StatusBadge>;
  }
  return <StatusBadge kind="active">{state}</StatusBadge>;
}

function StageDots({ stages, currentIndex }: { stages: string[]; currentIndex: number }) {
  return (
    <div className="flex items-center gap-0.5">
      {stages.map((stage, i) => {
        const reached = i <= currentIndex;
        const isCurrent = i === currentIndex;
        const colour = isCurrent
          ? "var(--color-state-active)"
          : reached
            ? "var(--color-state-success)"
            : "var(--color-border-subtle)";
        return (
          <div
            key={stage}
            className="flex flex-1 items-center"
            title={stage}
          >
            <span
              className={cn("h-1.5 w-1.5 shrink-0 rounded-full", isCurrent && "animate-pulse")}
              style={{
                background: colour,
                boxShadow: isCurrent ? `0 0 6px ${colour}` : undefined,
              }}
              aria-hidden
            />
            {i < stages.length - 1 && (
              <span
                className="h-px flex-1"
                style={{ background: colour, opacity: reached ? 0.7 : 0.3 }}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}

function SideRailNotice({ state }: { state: string }) {
  return (
    <p className="text-[11px] text-text-muted">
      Off the forward path · waiting on external event ·
      <span className="ml-1 font-mono">{state}</span>
    </p>
  );
}

// §1D.4: ↗ trace affordance per saga row. Opens Grafana Tempo Explore in a
// new tab on the row's W3C trace ID (captured at saga INSERT in §1D.3).
function TraceLink({ traceId }: { traceId: string | null }) {
  const url = traceExploreUrl(traceId);
  if (!url) return null;
  return (
    <a
      href={url}
      target="_blank"
      rel="noreferrer"
      title={`Open trace ${traceId} in Grafana Tempo`}
      className="inline-flex items-center gap-0.5 text-[11px] text-text-faint hover:text-text-default"
    >
      <ExternalLink className="h-3 w-3" /> trace
    </a>
  );
}

function useFlash(updatedAt: number | undefined): boolean {
  const [flashing, setFlashing] = useState(false);
  useEffect(() => {
    if (!updatedAt) return;
    if (Date.now() - updatedAt > 1500) return;     // ignore initial seed
    setFlashing(true);
    const t = window.setTimeout(() => setFlashing(false), 800);
    return () => window.clearTimeout(t);
  }, [updatedAt]);
  return flashing;
}
