import clsx from "clsx";

type Tone = "info" | "success" | "warn" | "error" | "neutral";

interface StatusPillProps {
  label: string;
  tone?: Tone;
}

/**
 * Pill-shaped status chip with a 1px border + tinted background. Same
 * tone-to-color mapping is reused across every module — a `cancelled`
 * order and a `cancelled` PO render identically.
 */
export function StatusPill({ label, tone = "neutral" }: StatusPillProps) {
  return (
    <span
      className={clsx(
        "inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-medium leading-tight",
        TONE_CLASSES[tone]
      )}
    >
      {label}
    </span>
  );
}

const TONE_CLASSES: Record<Tone, string> = {
  info: "border-status-info/30 bg-status-info-soft text-status-info",
  success: "border-status-success/30 bg-status-success-soft text-status-success",
  warn: "border-status-warn/30 bg-status-warn-soft text-status-warn",
  error: "border-status-error/30 bg-status-error-soft text-status-error",
  neutral: "border-status-neutral/30 bg-status-neutral-soft text-status-neutral",
};

/**
 * Maps a domain status string to a pill (label + tone). Centralised so
 * every list renders the same status the same way.
 */
export function statusForOrder(status: string | null | undefined): { label: string; tone: Tone } {
  switch ((status ?? "").toLowerCase()) {
    case "draft":
    case "submitted":
    case "pending":
      return { label: titleCase(status!), tone: "info" };
    case "in_fulfilment":
    case "manufacturing":
    case "manufacturing_in_progress":
    case "ready_to_ship":
    case "partially_received":
    case "partially_invoiced":
    case "partially_paid":
    case "partially_reserved":
      return { label: titleCase(status!), tone: "warn" };
    case "shipped":
    case "completed":
    case "received":
    case "invoiced":
    case "paid":
    case "reserved":
      return { label: titleCase(status!), tone: "success" };
    case "not_required":
      return { label: titleCase(status!), tone: "neutral" };
    case "cancelled":
    case "rejected":
    case "failed":
      return { label: titleCase(status!), tone: "error" };
    case "":
    case undefined:
    case null:
      return { label: "—", tone: "neutral" };
    default:
      return { label: titleCase(status!), tone: "neutral" };
  }
}

function titleCase(s: string): string {
  return s.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}
