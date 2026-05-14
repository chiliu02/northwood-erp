import { cn } from "@/lib/utils";

export type StatusKind = "pending" | "active" | "success" | "warn" | "error" | "terminal" | "neutral";

const COLOUR_VAR: Record<StatusKind, string> = {
  pending:  "var(--color-state-pending)",
  active:   "var(--color-state-active)",
  success:  "var(--color-state-success)",
  warn:     "var(--color-state-warn)",
  error:    "var(--color-state-error)",
  terminal: "var(--color-state-terminal)",
  neutral:  "var(--color-text-faint)",
};

export function StatusBadge({ kind = "neutral", children, className }: {
  kind?: StatusKind;
  children: React.ReactNode;
  className?: string;
}) {
  const colour = COLOUR_VAR[kind];
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wider",
        className
      )}
      style={{
        color: colour,
        borderColor: colour,
        background: `color-mix(in srgb, ${colour} 12%, transparent)`,
      }}
    >
      <span className="h-1 w-1 rounded-full" style={{ background: colour }} aria-hidden />
      {children}
    </span>
  );
}

/** Map a saga / projection status string to a status kind. Null / unknown → neutral. */
export function inferStatusKind(value: string | null | undefined): StatusKind {
  if (!value) return "neutral";
  const v = value.toLowerCase();
  if (v === "completed" || v === "paid" || v === "matched" || v === "settled" || v === "active" || v === "resolved") return "terminal";
  if (v === "approved" || v === "received" || v === "reserved" || v === "shipped" || v === "posted" || v === "ok" || v === "in_stock") return "success";
  if (v.includes("partial") || v === "warn" || v === "low_stock" || v === "purchase_requested" || v === "purchase_ordered") return "warn";
  if (v === "failed" || v === "rejected" || v === "cancelled" || v === "out_of_stock" || v === "shortage") return "error";
  if (v === "pending" || v === "open" || v === "draft" || v === "started") return "pending";
  if (v === "discontinued" || v === "reversed") return "neutral";
  return "active";   // anything in-flight
}
