import clsx from "clsx";
import { useState, type ReactNode } from "react";
import { ChevronDown, ChevronUp, ChevronsUpDown } from "lucide-react";

export interface Column<T> {
  key: string;
  header: string;
  /** Right-align numeric columns. Adds tabular-nums automatically. */
  numeric?: boolean;
  /** Fixed pixel width for narrow columns (status, dates). */
  width?: string;
  render: (row: T) => ReactNode;
  /**
   * Provide to make the column sortable: returns a comparable value (string or
   * number) for the row. Clicking the header cycles asc → desc → unsorted.
   * Columns without an accessor render a plain, non-clickable header.
   */
  sortAccessor?: (row: T) => string | number | null | undefined;
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

type SortState = { key: string; dir: "asc" | "desc" } | null;

/**
 * Standard ERP data grid. Solid white surface, 1px borders, dense rows
 * (~36px), zebra-striped on hover. Click-to-navigate when {@code
 * onRowClick} is provided. Filtering is wired by the caller (kept out of the
 * grid so each list manages its own URL state); sorting is handled here for
 * any column that supplies a {@code sortAccessor}.
 */
export function DataGrid<T>({
  columns,
  rows,
  rowKey,
  onRowClick,
  emptyState,
  loading,
}: DataGridProps<T>) {
  const [sort, setSort] = useState<SortState>(null);

  const sortColumn = sort ? columns.find((c) => c.key === sort.key) : undefined;
  const sortedRows =
    sort && sortColumn?.sortAccessor
      ? [...rows].sort((a, b) => {
          const cmp = compareValues(sortColumn.sortAccessor!(a), sortColumn.sortAccessor!(b));
          return sort.dir === "asc" ? cmp : -cmp;
        })
      : rows;

  const toggleSort = (key: string) =>
    setSort((s) =>
      s && s.key === key
        ? s.dir === "asc"
          ? { key, dir: "desc" }
          : null
        : { key, dir: "asc" },
    );

  return (
    <div className="overflow-hidden rounded-md border border-border-default bg-bg-surface">
      <table className="w-full text-left text-[13px]">
        <thead className="border-b border-border-default bg-bg-subtle">
          <tr>
            {columns.map((c) => {
              const sortable = Boolean(c.sortAccessor);
              const activeDir = sort && sort.key === c.key ? sort.dir : null;
              return (
                <th
                  key={c.key}
                  style={c.width ? { width: c.width } : undefined}
                  className={clsx(
                    "px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-muted",
                    c.numeric && "text-right",
                  )}
                >
                  {sortable ? (
                    <button
                      type="button"
                      onClick={() => toggleSort(c.key)}
                      className={clsx(
                        "inline-flex items-center gap-1 uppercase tracking-wide hover:text-text-primary",
                        c.numeric && "flex-row-reverse",
                        activeDir && "text-text-primary",
                      )}
                    >
                      {c.header}
                      {activeDir === "asc" ? (
                        <ChevronUp className="h-3 w-3" />
                      ) : activeDir === "desc" ? (
                        <ChevronDown className="h-3 w-3" />
                      ) : (
                        <ChevronsUpDown className="h-3 w-3 text-text-faint" />
                      )}
                    </button>
                  ) : (
                    c.header
                  )}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <SkeletonRows columns={columns.length} />
          ) : sortedRows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="px-3 py-12 text-center text-sm text-text-muted">
                {emptyState ?? "No records to display."}
              </td>
            </tr>
          ) : (
            sortedRows.map((row) => (
              <tr
                key={rowKey(row)}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                className={clsx(
                  "border-b border-border-default last:border-b-0",
                  onRowClick && "cursor-pointer hover:bg-bg-subtle",
                )}
              >
                {columns.map((c) => (
                  <td
                    key={c.key}
                    className={clsx(
                      "px-3 py-2 align-middle text-text-primary",
                      c.numeric && "text-right tabular-nums",
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

/** Empties sort last; numbers numerically; strings naturally + case-insensitively. */
function compareValues(a: string | number | null | undefined, b: string | number | null | undefined): number {
  const aEmpty = a == null || a === "";
  const bEmpty = b == null || b === "";
  if (aEmpty && bEmpty) return 0;
  if (aEmpty) return 1;
  if (bEmpty) return -1;
  if (typeof a === "number" && typeof b === "number") return a - b;
  return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: "base" });
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
