import clsx from "clsx";
import type { ReactNode } from "react";

export interface Column<T> {
  key: string;
  header: string;
  /** Right-align numeric columns. Adds tabular-nums automatically. */
  numeric?: boolean;
  /** Fixed pixel width for narrow columns (status, dates). */
  width?: string;
  render: (row: T) => ReactNode;
}

interface DataGridProps<T> {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  /** Click handler when a row is selected. Hides the cursor change if undefined. */
  onRowClick?: (row: T) => void;
  /** State to render when rows.length === 0. Defaults to a generic message. */
  emptyState?: ReactNode;
  /** Renders skeleton rows when true. */
  loading?: boolean;
}

/**
 * Standard ERP data grid. Solid white surface, 1px borders, dense rows
 * (~36px), zebra-striped on hover. Click-to-navigate when {@code
 * onRowClick} is provided. Sorting + filtering are wired by the caller
 * (kept out of the grid so each list can manage its own URL state).
 */
export function DataGrid<T>({
  columns,
  rows,
  rowKey,
  onRowClick,
  emptyState,
  loading,
}: DataGridProps<T>) {
  return (
    <div className="overflow-hidden rounded-md border border-border-default bg-bg-surface">
      <table className="w-full text-left text-[13px]">
        <thead className="border-b border-border-default bg-bg-subtle">
          <tr>
            {columns.map((c) => (
              <th
                key={c.key}
                style={c.width ? { width: c.width } : undefined}
                className={clsx(
                  "px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-muted",
                  c.numeric && "text-right"
                )}
              >
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <SkeletonRows columns={columns.length} />
          ) : rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="px-3 py-12 text-center text-sm text-text-muted">
                {emptyState ?? "No records to display."}
              </td>
            </tr>
          ) : (
            rows.map((row) => (
              <tr
                key={rowKey(row)}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                className={clsx(
                  "border-b border-border-default last:border-b-0",
                  onRowClick && "cursor-pointer hover:bg-bg-subtle"
                )}
              >
                {columns.map((c) => (
                  <td
                    key={c.key}
                    className={clsx(
                      "px-3 py-2 align-middle text-text-primary",
                      c.numeric && "text-right tabular-nums"
                    )}
                  >
                    {c.render(row)}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}

function SkeletonRows({ columns }: { columns: number }) {
  return (
    <>
      {Array.from({ length: 6 }).map((_, i) => (
        <tr key={i} className="border-b border-border-default last:border-b-0">
          {Array.from({ length: columns }).map((_, j) => (
            <td key={j} className="px-3 py-2.5">
              <div className="h-3 w-3/4 rounded bg-bg-subtle" />
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}
