import clsx from "clsx";
import type { ReactNode } from "react";

interface FormSectionProps {
  title?: string;
  description?: string;
  /** Override the column count of the field grid. Default 2-up on md+. */
  columns?: 1 | 2 | 3;
  className?: string;
  children: ReactNode;
}

/**
 * Labelled card grouping related form fields. Solid white surface, 1px
 * border, optional title + description block at the top. Children are
 * laid out in a CSS grid (default 2 columns on md+ screens).
 */
export function FormSection({
  title,
  description,
  columns = 2,
  className,
  children,
}: FormSectionProps) {
  return (
    <section className={clsx("rounded-md border border-border-default bg-bg-surface", className)}>
      {(title || description) && (
        <header className="border-b border-border-default px-5 py-3">
          {title && <h2 className="text-sm font-semibold text-text-primary">{title}</h2>}
          {description && <p className="mt-0.5 text-xs text-text-muted">{description}</p>}
        </header>
      )}
      <div className={clsx("grid gap-4 px-5 py-4", GRID_CLASSES[columns])}>{children}</div>
    </section>
  );
}

const GRID_CLASSES: Record<1 | 2 | 3, string> = {
  1: "grid-cols-1",
  2: "grid-cols-1 md:grid-cols-2",
  3: "grid-cols-1 md:grid-cols-3",
};

interface FieldProps {
  label: string;
  /** Span the field across all grid columns (e.g. for a textarea). */
  fullWidth?: boolean;
  required?: boolean;
  hint?: string;
  error?: string;
  children: ReactNode;
}

/**
 * Standard form field wrapper — label, required marker, child input,
 * hint or error text. Grid-aware via the `fullWidth` prop.
 */
export function Field({ label, fullWidth, required, hint, error, children }: FieldProps) {
  return (
    <div className={clsx(fullWidth && "md:col-span-full")}>
      <label className="mb-1 flex items-center gap-1 text-xs font-medium text-text-secondary">
        {label}
        {required && <span className="text-status-error">*</span>}
      </label>
      {children}
      {error ? (
        <p className="mt-1 text-xs text-status-error">{error}</p>
      ) : hint ? (
        <p className="mt-1 text-xs text-text-muted">{hint}</p>
      ) : null}
    </div>
  );
}

/**
 * Read-only labelled field — renders a value, not an input. Used on
 * detail pages where DetailLayout's tab content is mostly read state.
 */
export function ReadOnlyField({
  label,
  value,
  fullWidth,
  className,
}: {
  label: string;
  value: ReactNode;
  fullWidth?: boolean;
  className?: string;
}) {
  return (
    <div className={clsx(fullWidth && "md:col-span-full", className)}>
      <div className="mb-1 text-xs font-medium text-text-secondary">{label}</div>
      <div className="text-sm text-text-primary">{value}</div>
    </div>
  );
}
