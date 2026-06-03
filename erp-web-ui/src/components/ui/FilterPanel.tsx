import { useState } from "react";
import { X } from "lucide-react";
import { TextInput, Select } from "@/components/ui/Form";

/**
 * One filterable field on a list page. {@code get} pulls the value to match
 * (and, for selects, to derive the dropdown options) from a row.
 *
 * - {@code type: "text"} (default) → case-insensitive substring match.
 * - {@code type: "select"} → exact match; options are the distinct row values
 *   unless {@code options} is given. {@code optionLabel} prettifies the label.
 */
export interface FilterField<T> {
  key: string;
  label: string;
  type?: "text" | "select";
  get: (row: T) => string | null | undefined;
  options?: string[];
  optionLabel?: (value: string) => string;
}

/**
 * Per-field list filter. Holds one value per field and ANDs them together: a
 * row passes only if it matches every field that has a value set (empty fields
 * are ignored). Pair with {@link FilterPanel} for the UI and wire the Filter
 * button to {@link toggle}. Lists are a single projection page (tens of rows),
 * so it re-filters on every render rather than memoising.
 */
export function useFieldFilters<T>(rows: T[], fields: FilterField<T>[]) {
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState<Record<string, string>>({});

  const active = fields.some((f) => (values[f.key] ?? "").trim() !== "");
  const filtered = rows.filter((row) =>
    fields.every((f) => {
      const needle = (values[f.key] ?? "").trim().toLowerCase();
      if (!needle) return true;
      const hay = (f.get(row) ?? "").toString().toLowerCase();
      return (f.type ?? "text") === "select" ? hay === needle : hay.includes(needle);
    }),
  );

  return {
    open,
    values,
    filtered,
    active,
    set: (key: string, value: string) => setValues((v) => ({ ...v, [key]: value })),
    clear: () => setValues({}),
    toggle: () => setOpen((o) => !o),
    close: () => setOpen(false),
  };
}

interface FilterPanelProps<T> {
  open: boolean;
  rows: T[];
  fields: FilterField<T>[];
  values: Record<string, string>;
  onChange: (key: string, value: string) => void;
  onClear: () => void;
  onClose: () => void;
}

/**
 * Filter panel rendered between the PageHeader and the grid. One labelled
 * control per field; nothing rendered when {@code open} is false.
 */
export function FilterPanel<T>({
  open,
  rows,
  fields,
  values,
  onChange,
  onClear,
  onClose,
}: FilterPanelProps<T>) {
  if (!open) return null;
  return (
    <div className="border-b border-border-default bg-bg-subtle px-8 py-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {fields.map((f) => (
          <label key={f.key} className="flex flex-col gap-1">
            <span className="text-[11px] font-semibold uppercase tracking-wide text-text-muted">
              {f.label}
            </span>
            {(f.type ?? "text") === "select" ? (
              <Select
                className="!h-8"
                value={values[f.key] ?? ""}
                onChange={(e) => onChange(f.key, e.target.value)}
              >
                <option value="">Any</option>
                {distinctValues(rows, f).map((v) => (
                  <option key={v} value={v}>
                    {f.optionLabel ? f.optionLabel(v) : v}
                  </option>
                ))}
              </Select>
            ) : (
              <TextInput
                className="!h-8"
                value={values[f.key] ?? ""}
                onChange={(e) => onChange(f.key, e.target.value)}
                placeholder={`Filter ${f.label.toLowerCase()}…`}
              />
            )}
          </label>
        ))}
      </div>
      <div className="mt-3 flex items-center justify-between">
        <button
          type="button"
          onClick={onClear}
          className="text-xs font-medium text-text-muted hover:text-text-primary"
        >
          Clear all
        </button>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close filter"
          className="flex items-center gap-1 text-xs font-medium text-text-muted hover:text-text-primary"
        >
          <X className="h-3.5 w-3.5" /> Close
        </button>
      </div>
    </div>
  );
}

function distinctValues<T>(rows: T[], field: FilterField<T>): string[] {
  if (field.options) return field.options;
  const set = new Set<string>();
  for (const row of rows) {
    const v = field.get(row);
    if (v != null && String(v).trim() !== "") set.add(String(v));
  }
  return Array.from(set).sort();
}
