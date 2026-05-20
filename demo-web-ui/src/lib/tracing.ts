// §1D.4 — Grafana Tempo Explore deep-link helper.
//
// Each saga row and event row carries a W3C trace ID (32 hex chars) once
// §1D.2 + §1D.3 are in place. The Saga Console and Event Log render a
// `↗ trace` affordance that calls traceExploreUrl(traceId) and opens the
// returned URL in a new tab — Grafana stays off-BFF on :3000, no proxy.

const GRAFANA_BASE = "http://localhost:3000";
const TEMPO_DATASOURCE_UID = "northwood-tempo";

/**
 * Build the Grafana Explore URL for one trace. Returns null when the
 * traceId is missing or doesn't look like a 32-char hex string — callers
 * should render the button as disabled in that case.
 */
export function traceExploreUrl(traceId: string | null | undefined): string | null {
  if (!traceId) return null;
  if (!/^[0-9a-f]{32}$/i.test(traceId)) return null;
  const left = {
    datasource: TEMPO_DATASOURCE_UID,
    queries: [{ refId: "A", queryType: "traceId", query: traceId }],
    range: { from: "now-1h", to: "now" },
  };
  return `${GRAFANA_BASE}/explore?orgId=1&left=${encodeURIComponent(JSON.stringify(left))}`;
}
