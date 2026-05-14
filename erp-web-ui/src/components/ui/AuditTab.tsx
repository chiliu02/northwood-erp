import { useQuery } from "@tanstack/react-query";
import { History } from "lucide-react";
import { Link } from "react-router-dom";
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

interface AuditTabProps {
  aggregateId: string | undefined;
}

/**
 * Per-aggregate audit timeline rendered inside a detail page's "Audit" tab.
 * Same data source as the system-wide /system/audit-log page (the BFF's
 * /api/audit aggregator), scoped to a single aggregate id. Drops the
 * Aggregate column from the system table since every row shares the
 * same aggregate.
 */
export function AuditTab({ aggregateId }: AuditTabProps) {
  const query = useQuery({
    queryKey: ["audit-log", aggregateId],
    queryFn: () => apiGet<AuditEntry[]>(`/api/audit?aggregateId=${aggregateId}`),
    enabled: !!aggregateId,
  });

  return (
    <div className="rounded-md border border-border-default bg-bg-surface">
      <div className="flex items-center justify-between border-b border-border-default px-4 py-2 text-xs text-text-muted">
        <span>Events emitted by this aggregate, newest first.</span>
        {aggregateId && (
          <Link
            to={`/system/audit-log?aggregateId=${aggregateId}`}
            className="text-text-secondary hover:text-text-primary"
          >
            Open in audit-log viewer →
          </Link>
        )}
      </div>
      <table className="w-full text-sm">
        <thead className="border-b border-border-default text-left text-xs uppercase tracking-wide text-text-muted">
          <tr>
            <th className="px-4 py-2 font-medium">When</th>
            <th className="px-4 py-2 font-medium">Service</th>
            <th className="px-4 py-2 font-medium">Event</th>
            <th className="px-4 py-2 font-medium">Actor</th>
          </tr>
        </thead>
        <tbody>
          {query.isLoading && (
            <tr>
              <td className="px-4 py-6 text-center text-text-muted" colSpan={4}>
                Loading audit log…
              </td>
            </tr>
          )}
          {query.isError && (
            <tr>
              <td className="px-4 py-6 text-center text-status-error" colSpan={4}>
                Failed to load audit log
              </td>
            </tr>
          )}
          {query.data && query.data.length === 0 && (
            <tr>
              <td className="px-4 py-6 text-center text-text-muted" colSpan={4}>
                No events recorded for this aggregate
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
  );
}

function formatInstant(iso: string): string {
  return new Date(iso).toLocaleString();
}
