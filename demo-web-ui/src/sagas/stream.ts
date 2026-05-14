// SSE consumer for one saga service's /api/sagas/stream endpoint. Falls back
// to polling /api/sagas if the EventSource fails.

import { useEffect, useState, useRef } from "react";

export interface SagaRow {
  sagaId: string;
  domainKey: string;
  domainKeyLabel: string;
  sagaType: string;
  state: string;
  currentStep: string | null;
  lastError: string | null;
  retryCount: number;
  version: number;
  createdAt: string | null;
  updatedAt: string | null;
  completedAt: string | null;
  /** Local-only — set by useSagaStream when this row was just updated. Drives the flash animation. */
  _justUpdated?: number;     // timestamp the SPA observed the change
}

interface UseSagaStreamOptions {
  /** Path mounted by Vite proxy, e.g. /api/sagas-sales */
  basePath: string;
  /** Polling fallback interval if SSE drops. Default 3s. */
  pollMs?: number;
}

interface UseSagaStreamResult {
  rows: SagaRow[];
  isLive: boolean;
  error: string | null;
}

export function useSagaStream({ basePath, pollMs = 3000 }: UseSagaStreamOptions): UseSagaStreamResult {
  const [rows, setRows] = useState<SagaRow[]>([]);
  const [isLive, setIsLive] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const indexRef = useRef<Map<string, SagaRow>>(new Map());

  useEffect(() => {
    let cancelled = false;
    let pollTimer: number | null = null;
    let eventSource: EventSource | null = null;

    const upsert = (incoming: SagaRow, observedAt: number) => {
      const existing = indexRef.current.get(incoming.sagaId);
      const justUpdated = !existing || existing.version !== incoming.version ? observedAt : existing._justUpdated;
      indexRef.current.set(incoming.sagaId, { ...incoming, _justUpdated: justUpdated });
      // updated_at desc
      const next = Array.from(indexRef.current.values())
        .sort((a, b) => (b.updatedAt ?? "").localeCompare(a.updatedAt ?? ""));
      if (!cancelled) setRows(next);
    };

    const replaceAll = (incoming: SagaRow[], observedAt: number) => {
      const idx = new Map<string, SagaRow>();
      for (const r of incoming) {
        const old = indexRef.current.get(r.sagaId);
        const justUpdated = !old || old.version !== r.version ? observedAt : old._justUpdated;
        idx.set(r.sagaId, { ...r, _justUpdated: justUpdated });
      }
      indexRef.current = idx;
      const next = Array.from(idx.values())
        .sort((a, b) => (b.updatedAt ?? "").localeCompare(a.updatedAt ?? ""));
      if (!cancelled) setRows(next);
    };

    const fetchList = async () => {
      try {
        const res = await fetch(basePath);
        if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
        const list = (await res.json()) as SagaRow[];
        replaceAll(list, Date.now());
        if (!cancelled) setError(null);
      } catch (e) {
        if (!cancelled) setError(String(e));
      }
    };

    const startPolling = () => {
      if (pollTimer !== null) return;
      pollTimer = window.setInterval(fetchList, pollMs);
    };

    const stopPolling = () => {
      if (pollTimer !== null) {
        window.clearInterval(pollTimer);
        pollTimer = null;
      }
    };

    const startStream = () => {
      try {
        eventSource = new EventSource(`${basePath}/stream`);
      } catch (e) {
        setError(`SSE init failed: ${String(e)}`);
        startPolling();
        return;
      }
      eventSource.addEventListener("saga", (ev) => {
        try {
          const row = JSON.parse((ev as MessageEvent).data) as SagaRow;
          upsert(row, Date.now());
        } catch {
          // ignore malformed
        }
      });
      eventSource.onopen = () => {
        if (!cancelled) {
          setIsLive(true);
          stopPolling();
        }
      };
      eventSource.onerror = () => {
        if (cancelled) return;
        setIsLive(false);
        // EventSource will auto-reconnect; switch to polling in the meantime.
        startPolling();
      };
    };

    // Always seed via the list endpoint first, then attach the stream.
    fetchList().then(() => {
      if (!cancelled) startStream();
    });

    return () => {
      cancelled = true;
      eventSource?.close();
      stopPolling();
    };
  }, [basePath, pollMs]);

  return { rows, isLive, error };
}
