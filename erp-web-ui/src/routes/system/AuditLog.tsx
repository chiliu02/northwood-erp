import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { History, Search } from "lucide-react";
import { PageHeader } from "@/components/ui/PageHeader";
import { apiGet } from "@/lib/api";

interface AuditEntry {
  outboxMessageId: string;
  sequenceNumber: number;
  sourceService: string;
  aggregateType: string;
  aggregateId: string;
  eventType: string;
  actorUserId: string | null;
  correlationId: string | null;
  occurredAt: string;
}

/**
 * Cross-service audit-log viewer. Reads the BFF's
 * {@code /api/audit} aggregator which fans out to every Northwood service's
 * outbox in parallel and returns a single timeline ordered by
 * {@code occurredAt} desc.
 *
 * <p>Filter by aggregate id via the {@code ?aggregateId=} query param —
 * detail pages link here with the aggregate's id pre-filled. Without a
 * filter the screen shows the most-recent global activity, capped at the
 * BFF's per-service limit.
 */
export function AuditLog() {
  const [params, setParams] = useSearchParams();
  const aggregateId = params.get("aggregateId") ?? "";

  const query = useQuery({
    queryKey: ["audit-log", aggregateId],
    queryFn: () => {
      const search = aggregateId ? `?aggregateId=${aggregateId}` : "";
      return apiGet<AuditEntry[]>(`/api/audit${search}`);
    },
  });

  return (
    <div>
      <PageHeader
        title="Audit Log"
        trail={[{ label: "Home", to: "/" }, { label: "System" }, { label: "Audit Log" }]}
      />

      <div className="px-8 py-6">
        <div className="mb-4 flex items-center gap-3">
          <div className="relative flex-1 max-w-xl">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-text-faint" />
            <input
              type="search"
              placeholder="Filter by aggregate id (UUID)…"
              defaultValue={aggregateId}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  const value = (e.target as HTMLInputElement).value.trim();
                  if (value) setParams({ aggregateId: value });
                  else setParams({});
                }
              }}
              className="h-9 w-full rounded-md border border-border-default bg-bg-surface pl-9 pr-3 text-sm placeholder:text-text-faint focus:border-border-focus focus:outline-none"
            />
          </div>
          {aggregateId && (
            <button
              type="button"
              onClick={() => setParams({})}
              className="text-sm text-text-secondary hover:text-text-primary"
            >
              Clear filter
            </button>
          )}
        </div>

        <div className="rounded-md border border-border-default bg-bg-surface">
          <table className="w-full text-sm">
            <thead className="border-b border-border-default text-left text-xs uppercase tracking-wide text-text-muted">
              <tr>
                <th className="px-4 py-2 font-medium">When</th>
                <th className="px-4 py-2 font-medium">Service</th>
                <th className="px-4 py-2 font-medium">Event</th>
                <th className="px-4 py-2 font-medium">Aggregate</th>
                <th className="px-4 py-2 font-medium">Actor</th>
              </tr>
            </thead>
            <tbody>
              {query.isLoading && (
                <tr>
                  <td className="px-4 py-6 text-center text-text-muted" colSpan={5}>Loading audit log…</td>
                </tr>
              )}
              {query.isError && (
                <tr>
                  <td className="px-4 py-6 text-center text-status-error" colSpan={5}>
                    Failed to load audit log
                  </td>
                </tr>
              )}
              {query.data && query.data.length === 0 && (
                <tr>
                  <td className="px-4 py-6 text-center text-text-muted" colSpan={5}>
                    {aggregateId ? "No events for this aggregate" : "No events recorded"}
                  </td>
                </tr>
              )}
              {query.data?.map((row) => (
                <tr key={row.outboxMessageId} className="border-b border-border-default/60 last:border-0">
                  <td className="px-4 py-2 font-mono text-xs text-text-secondary">
                    {formatInstant(row.occurredAt)}
                  </td>
                  <td className="px-4 py-2">
                    <span className="rounded bg-bg-subtle px-1.5 py-0.5 text-xs font-medium text-text-secondary">
                      {row.sourceService}
                    </span>
                  </td>
                  <td className="px-4 py-2 font-mono text-xs">{row.eventType}</td>
                  <td className="px-4 py-2 font-mono text-xs text-text-muted">
                    <div>{row.aggregateType}</div>
                    <div className="truncate">{row.aggregateId}</div>
                  </td>
                  <td className="px-4 py-2">
                    {row.actorUserId ? (
                      <span className="font-medium text-text-primary">{row.actorUserId}</span>
                    ) : (
                      <span className="inline-flex items-center gap-1 text-xs text-text-muted">
                        <History className="h-3 w-3" />
                        system
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function formatInstant(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString();
}
