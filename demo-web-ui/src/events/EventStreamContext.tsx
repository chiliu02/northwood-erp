import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

// Shared event-stream state for the whole SPA. One EventSource subscription
// (BFF /api/events SSE), one buffer, multiple consumers. Lives at AppShell
// level so route components mount/unmount without losing the buffer — the
// EventLog page used to reset to empty on every re-navigation because it
// owned its own state.

export type ServiceKey = "sales" | "inventory" | "manufacturing" | "purchasing" | "finance" | "product";

export interface EventRow {
  eventId: string;
  eventType: string;
  sourceService: string;
  aggregateType: string | null;
  aggregateId: string | null;
  /** §1D.4: 32-char W3C trace ID extracted from EventEnvelope.headers.traceparent (§1D.2). Null on legacy events. */
  traceId: string | null;
  occurredAt: string | null;
  receivedAt: string;
}

export interface DemoEvent {
  eventId: string;
  service: ServiceKey;
  eventType: string;
  aggregateType: string | null;
  aggregateId: string;
  /** Mirror of EventRow.traceId so consumers don't have to dig into raw. */
  traceId: string | null;
  occurredAt: string;
  raw: EventRow;
}

interface EventStreamContextValue {
  events: DemoEvent[];
  paused: boolean;
  setPaused: (p: boolean) => void;
  clear: () => void;
}

const KNOWN_SERVICES: ServiceKey[] = ["sales", "inventory", "manufacturing", "purchasing", "finance", "product"];

function asServiceKey(s: string): ServiceKey {
  return (KNOWN_SERVICES as string[]).includes(s) ? (s as ServiceKey) : "product";
}

// Cap matches the deepest consumer (EventLog). The drawer slices for display.
const MAX_EVENTS = 500;

const Ctx = createContext<EventStreamContextValue | null>(null);

export function EventStreamProvider({ children }: { children: ReactNode }) {
  const [events, setEvents] = useState<DemoEvent[]>([]);
  const [paused, setPaused] = useState(false);

  useEffect(() => {
    if (paused) return;
    const source = new EventSource("/api/events");
    source.addEventListener("event", (msg) => {
      try {
        const row: EventRow = JSON.parse((msg as MessageEvent<string>).data);
        const e: DemoEvent = {
          eventId: row.eventId,
          service: asServiceKey(row.sourceService),
          eventType: row.eventType,
          aggregateType: row.aggregateType,
          aggregateId: row.aggregateId ?? "",
          traceId: row.traceId,
          occurredAt: row.occurredAt ?? row.receivedAt,
          raw: row,
        };
        setEvents((prev) => [e, ...prev.slice(0, MAX_EVENTS - 1)]);
      } catch {
        // BFF logs malformed records on its side.
      }
    });
    return () => source.close();
  }, [paused]);

  return (
    <Ctx.Provider value={{ events, paused, setPaused, clear: () => setEvents([]) }}>
      {children}
    </Ctx.Provider>
  );
}

export function useEventStream(): EventStreamContextValue {
  const v = useContext(Ctx);
  if (!v) throw new Error("useEventStream must be used inside EventStreamProvider");
  return v;
}
