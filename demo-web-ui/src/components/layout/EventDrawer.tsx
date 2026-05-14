import { ChevronUp, ChevronDown, Pause, Play, Trash2 } from "lucide-react";
import { cn, formatTime, truncateUuid } from "@/lib/utils";
import { useEventStream, type ServiceKey } from "@/events/EventStreamContext";

// Bottom drawer rendering of the shared event stream. The SSE subscription
// + buffer live in EventStreamContext (wired at AppShell level) so this
// component and the full-screen /event-log page see the same events without
// double-subscribing — and so navigating away from /event-log no longer
// resets the buffer.

const DRAWER_DISPLAY_CAP = 50;

const SERVICE_PERSONA: Record<ServiceKey, string> = {
  product:       "var(--color-persona-emma)",
  sales:         "var(--color-persona-sarah)",
  inventory:     "var(--color-persona-mike)",
  manufacturing: "var(--color-persona-linda)",
  purchasing:    "var(--color-persona-tom)",
  finance:       "var(--color-persona-daniel)",
};

interface EventDrawerProps {
  open: boolean;
  onToggle: () => void;
}

export function EventDrawer({ open, onToggle }: EventDrawerProps) {
  const { events, paused, setPaused, clear } = useEventStream();
  const display = events.slice(0, DRAWER_DISPLAY_CAP);

  return (
    <div
      className={cn(
        "shrink-0 border-t border-border-subtle bg-bg-elevated transition-[height] duration-200",
        open ? "h-56" : "h-9"
      )}
    >
      <div className="flex h-9 items-center justify-between px-4 text-xs">
        <button
          type="button"
          onClick={onToggle}
          className="flex items-center gap-2 text-text-muted hover:text-text-primary"
        >
          {open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronUp className="h-3.5 w-3.5" />}
          <span className="font-semibold uppercase tracking-wider">Event stream</span>
          {!open && display[0] && (
            <span className="ml-2 text-text-faint">
              latest: <span className="font-mono">{display[0].eventType}</span>
            </span>
          )}
          <span className="ml-2 rounded bg-bg-hover px-1.5 py-0.5 text-[10px] tabular-nums text-text-muted">
            {events.length}
          </span>
        </button>
        {open && (
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setPaused(!paused)}
              className="flex items-center gap-1 rounded border border-border-subtle px-2 py-0.5 text-[11px] text-text-muted hover:text-text-primary"
            >
              {paused ? <Play className="h-3 w-3" /> : <Pause className="h-3 w-3" />}
              {paused ? "resume" : "pause"}
            </button>
            <button
              type="button"
              onClick={clear}
              className="flex items-center gap-1 rounded border border-border-subtle px-2 py-0.5 text-[11px] text-text-muted hover:text-text-primary"
            >
              <Trash2 className="h-3 w-3" />
              clear
            </button>
          </div>
        )}
      </div>

      {open && (
        <div className="h-[calc(100%-2.25rem)] overflow-y-auto scrollbar-thin">
          <table className="w-full text-xs">
            <tbody>
              {display.map((e) => (
                /* React keys by eventId, so old rows are reused (no
                   re-animation) when a new event is prepended — only the
                   freshly-mounted top row plays the slide-in. */
                <tr
                  key={e.eventId}
                  className="northwood-slide-in border-l-2 hover:bg-bg-hover"
                  style={{ borderLeftColor: SERVICE_PERSONA[e.service] }}
                >
                  <td className="w-28 px-3 py-1.5 font-mono tabular-nums text-text-muted">
                    {formatTime(e.occurredAt)}
                  </td>
                  <td className="w-28 px-3 py-1.5 text-text-muted">{e.service}</td>
                  <td className="px-3 py-1.5 font-mono">
                    <EventTypeLabel value={e.eventType} />
                  </td>
                  <td className="w-40 px-3 py-1.5 font-mono text-text-faint">
                    aggr={truncateUuid(e.aggregateId)}
                  </td>
                </tr>
              ))}
              {display.length === 0 && (
                <tr>
                  <td colSpan={4} className="px-3 py-6 text-center text-text-faint">
                    No events yet — drive a flow (place an order, post a receipt, etc.) to see them stream in.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
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
