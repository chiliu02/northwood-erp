import { useState } from "react";
import { cn } from "@/lib/utils";
import { PERSONAS, type PersonaKey } from "@/personas";

interface MasterDetailProps<T> {
  title: string;
  persona?: PersonaKey;
  items: T[] | undefined;
  isLoading: boolean;
  error: unknown;
  rowKey: (item: T) => string;
  renderRow: (item: T, isActive: boolean) => React.ReactNode;
  renderDetail: (item: T) => React.ReactNode;
  emptyMessage?: string;
  errorContext?: string;        // e.g. "reporting-service on :8087"
}

export function MasterDetail<T>({
  title, persona, items, isLoading, error,
  rowKey, renderRow, renderDetail, emptyMessage = "No data yet.", errorContext,
}: MasterDetailProps<T>) {
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const list = items ?? [];

  // Auto-select the first row once data lands.
  if (selectedKey === null && list.length > 0) {
    queueMicrotask(() => setSelectedKey(rowKey(list[0])));
  }

  const selected = list.find((it) => rowKey(it) === selectedKey) ?? null;
  const p = persona ? PERSONAS[persona] : null;

  return (
    <div className="space-y-4">
      <div className="flex items-baseline gap-3">
        <h1 className="text-[28px] font-semibold tracking-tight">{title}</h1>
        {p && (
          <span className="flex items-center gap-2 text-sm text-text-muted">
            <span
              className="h-1.5 w-1.5 rounded-full"
              style={{ background: p.accentVar }}
              aria-hidden
            />
            {p.name} · {p.role}
          </span>
        )}
        <span className="ml-auto text-xs text-text-faint">
          {isLoading ? "loading…" : `${list.length} row${list.length === 1 ? "" : "s"}`}
        </span>
      </div>

      {error ? (
        <div
          className="rounded-md border px-4 py-3 text-sm"
          style={{
            borderColor: "var(--color-state-error)",
            background: "color-mix(in srgb, var(--color-state-error) 12%, transparent)",
          }}
        >
          <p className="font-medium" style={{ color: "var(--color-state-error)" }}>
            {errorContext ? `Couldn't reach ${errorContext}` : "Fetch failed"}
          </p>
          <p className="mt-1 text-text-muted">{String(error)}</p>
        </div>
      ) : null}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <div className="rounded-lg border border-border-subtle bg-bg-elevated">
          <div className="max-h-[calc(100vh-18rem)] overflow-y-auto scrollbar-thin">
            {list.length === 0 && !isLoading && !error && (
              <div className="px-5 py-8 text-center text-sm text-text-faint">{emptyMessage}</div>
            )}
            {list.map((item) => {
              const k = rowKey(item);
              const active = k === selectedKey;
              return (
                <button
                  key={k}
                  type="button"
                  onClick={() => setSelectedKey(k)}
                  className={cn(
                    "block w-full border-b border-border-subtle px-4 py-3 text-left text-sm transition-colors last:border-b-0",
                    active ? "bg-bg-hover" : "hover:bg-bg-hover/60"
                  )}
                >
                  {renderRow(item, active)}
                </button>
              );
            })}
          </div>
        </div>

        <div className="rounded-lg border border-border-subtle bg-bg-elevated">
          {selected ? (
            <div className="p-5">{renderDetail(selected)}</div>
          ) : (
            <div className="p-8 text-center text-sm text-text-faint">
              {list.length > 0 ? "Select a row to see the detail." : "No row selected."}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
