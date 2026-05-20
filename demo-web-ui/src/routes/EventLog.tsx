import { useMemo, useRef, useState } from "react";
import { ExternalLink, Pause, Play, Trash2 } from "lucide-react";
import { Button, Input, Select } from "@/components/ui/Form";
import { cn, formatTime, truncateUuid } from "@/lib/utils";
import { traceExploreUrl } from "@/lib/tracing";
import { useEventStream, type DemoEvent, type ServiceKey } from "@/events/EventStreamContext";

// Full-screen variant of the bottom EventDrawer. Both read from the same
// EventStreamContext — the SSE subscription + buffer live at AppShell level
// so this page survives navigation (used to reset to empty on every remount
// because it owned its own state).

const KNOWN_SERVICES: ServiceKey[] = ["sales", "inventory", "manufacturing", "purchasing", "finance", "product"];

const SERVICE_PERSONA: Record<ServiceKey, string> = {
  product:       "var(--color-persona-emma)",
  sales:         "var(--color-persona-sarah)",
  inventory:     "var(--color-persona-mike)",
  manufacturing: "var(--color-persona-linda)",
  purchasing:    "var(--color-persona-tom)",
  finance:       "var(--color-persona-daniel)",
};

const MAX_EVENTS = 500;

export function EventLog() {
  const { events, paused, setPaused, clear } = useEventStream();
  const [serviceFilter, setServiceFilter] = useState<ServiceKey | "">("");
  const [typeFilter, setTypeFilter] = useState("");
  const [aggrFilter, setAggrFilter] = useState("");
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const filtered = useMemo(() => {
    const t = typeFilter.trim().toLowerCase();
    const a = aggrFilter.trim().toLowerCase();
    return events.filter((e) => {
      if (serviceFilter && e.service !== serviceFilter) return false;
      if (t && !e.eventType.toLowerCase().includes(t)) return false;
      if (a && !e.aggregateId.toLowerCase().includes(a)) return false;
      return true;
    });
  }, [events, serviceFilter, typeFilter, aggrFilter]);

  return (
    <div className="space-y-4">
      <div className="flex items-baseline gap-3">
        <h1 className="text-[28px] font-semibold tracking-tight">Event log</h1>
        <span className="ml-auto flex items-center gap-2 text-xs text-text-faint">
          <span className="rounded bg-bg-hover px-1.5 py-0.5 tabular-nums">{events.length} buffered</span>
          {filtered.length !== events.length && (
            <span className="rounded bg-bg-hover px-1.5 py-0.5 tabular-nums">{filtered.length} shown</span>
          )}
        </span>
      </div>

      <p className="text-sm text-text-muted">
        Live feed of every event flowing through the bus, newest first. Same SSE source as the bottom
        drawer, with persistent filters and deeper buffer (cap {MAX_EVENTS}). Click a row to reveal the
        full envelope.
      </p>

      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-border-subtle bg-bg-elevated p-3">
        <Select
          value={serviceFilter}
          onChange={(e) => setServiceFilter(e.target.value as ServiceKey | "")}
          className="max-w-[180px]"
        >
          <option value="">All services</option>
          {KNOWN_SERVICES.map((s) => <option key={s} value={s}>{s}</option>)}
        </Select>
        <Input
          placeholder="event type contains…"
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value)}
          className="max-w-[280px]"
        />
        <Input
          placeholder="aggregate id contains…"
          value={aggrFilter}
          onChange={(e) => setAggrFilter(e.target.value)}
          className="max-w-[220px]"
        />
        <span className="ml-auto flex items-center gap-2">
          <Button variant="secondary" onClick={() => setPaused(!paused)}>
            {paused
              ? <><Play className="-ml-0.5 mr-1 inline h-3.5 w-3.5" /> resume</>
              : <><Pause className="-ml-0.5 mr-1 inline h-3.5 w-3.5" /> pause</>}
          </Button>
          <Button variant="ghost" onClick={clear}>
            <Trash2 className="-ml-0.5 mr-1 inline h-3.5 w-3.5" /> clear
          </Button>
        </span>
      </div>

      <div className="overflow-hidden rounded-lg border border-border-subtle bg-bg-elevated">
        <table className="w-full text-sm">
          <thead className="border-b border-border-subtle bg-bg-base/50 text-left text-[11px] uppercase tracking-wider text-text-muted">
            <tr>
              <th className="px-3 py-2 font-semibold">Time</th>
              <th className="px-3 py-2 font-semibold">Service</th>
              <th className="px-3 py-2 font-semibold">Event type</th>
              <th className="px-3 py-2 font-semibold">Aggregate</th>
              <th className="px-3 py-2 font-semibold">Trace</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border-subtle">
            {filtered.map((e) => (
              <EventLogRow
                key={e.eventId}
                event={e}
                expanded={expandedId === e.eventId}
                onToggle={() => setExpandedId(expandedId === e.eventId ? null : e.eventId)}
              />
            ))}
            {filtered.length === 0 && (
              <tr>
                <td colSpan={5} className="px-3 py-10 text-center text-text-faint">
                  {events.length === 0
                    ? "Waiting for events. Drive a flow (place an order, post a receipt, etc.) to see them stream in."
                    : "No events match the current filters."}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function EventLogRow({ event, expanded, onToggle }: {
  event: DemoEvent;
  expanded: boolean;
  onToggle: () => void;
}) {
  const rowRef = useRef<HTMLTableRowElement>(null);
  return (
    <>
      <tr
        ref={rowRef}
        onClick={onToggle}
        className={cn("cursor-pointer border-l-2 hover:bg-bg-hover", expanded && "bg-bg-hover")}
        style={{ borderLeftColor: SERVICE_PERSONA[event.service] }}
      >
        <td className="w-32 px-3 py-1.5 font-mono tabular-nums text-text-muted">
          {formatTime(event.occurredAt)}
        </td>
        <td className="w-32 px-3 py-1.5 text-text-muted">{event.service}</td>
        <td className="px-3 py-1.5 font-mono">
          <EventTypeLabel value={event.eventType} />
        </td>
        <td className="w-56 px-3 py-1.5 font-mono text-text-faint">
          {event.aggregateType ?? "?"} · {truncateUuid(event.aggregateId)}
        </td>
        <td className="w-20 px-3 py-1.5">
          <TraceLink traceId={event.traceId} />
        </td>
      </tr>
      {expanded && (
        <tr>
          <td colSpan={5} className="bg-bg-base/40 px-6 py-3">
            <pre className="overflow-x-auto whitespace-pre-wrap text-[11px] text-text-muted">
              {JSON.stringify(event.raw, null, 2)}
            </pre>
          </td>
        </tr>
      )}
    </>
  );
}

function EventTypeLabel({ value }: { value: string }) {
  const dot = value.indexOf(".");
  if (dot < 0) return <>{value}</>;
  return (
    <>
      <span className="text-text-faint">{value.slice(0, dot + 1)}</span>
      <span>{value.slice(dot + 1)}</span>
    </>
  );
}

// §1D.4: ↗ trace affordance. Opens Grafana Tempo Explore in a new tab.
// Falls back to a dimmed em-dash for legacy events without a trace ID.
function TraceLink({ traceId }: { traceId: string | null }) {
  const url = traceExploreUrl(traceId);
  if (!url) {
    return <span className="text-text-faint" title="no trace id">—</span>;
  }
  return (
    <a
      href={url}
      target="_blank"
      rel="noreferrer"
      onClick={(e) => e.stopPropagation()}
      title={`Open trace ${traceId} in Grafana Tempo`}
      className="inline-flex items-center gap-0.5 text-xs text-text-muted hover:text-text-default"
    >
      <ExternalLink className="h-3 w-3" /> trace
    </a>
  );
}
