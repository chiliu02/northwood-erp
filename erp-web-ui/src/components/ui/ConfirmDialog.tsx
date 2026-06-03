import { useEffect } from "react";
import { X } from "lucide-react";
import type { ReactNode } from "react";
import { ActionButton } from "./ActionButton";

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message?: ReactNode;
  /** Render extra inputs below the message (e.g. reason textarea). */
  body?: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  variant?: "primary" | "danger";
  onConfirm: () => void;
  onCancel: () => void;
  /** Mutation in flight: relabels the confirm button "Working…" and disables both buttons. */
  busy?: boolean;
  /** Disable the confirm button without the "Working…" label (e.g. an invalid / unchanged form). Cancel stays enabled. */
  confirmDisabled?: boolean;
}

/**
 * Modal confirmation dialog for destructive or sensitive actions
 * (Approve, Reject, Reverse, Cancel Order). Standard ERP shape: 1px-
 * bordered card centered over a translucent backdrop, focus trapped on
 * Cancel by default. The `body` slot lets callers add reason inputs
 * inline rather than building a separate dialog per command.
 */
export function ConfirmDialog({
  open,
  title,
  message,
  body,
  confirmLabel,
  cancelLabel = "Cancel",
  variant = "primary",
  onConfirm,
  onCancel,
  busy,
  confirmDisabled,
}: ConfirmDialogProps) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCancel();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onCancel]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-text-primary/30 px-4">
      <div className="w-full max-w-md rounded-md border border-border-default bg-bg-surface shadow-lg">
        <header className="flex items-start justify-between border-b border-border-default px-5 py-3">
          <h2 className="text-sm font-semibold text-text-primary">{title}</h2>
          <button
            type="button"
            onClick={onCancel}
            className="-mr-1 -mt-0.5 flex h-7 w-7 items-center justify-center rounded-md text-text-muted hover:bg-bg-subtle"
            aria-label="Close"
          >
            <X className="h-4 w-4" />
          </button>
        </header>
        <div className="px-5 py-4">
          {message && <div className="text-sm text-text-secondary">{message}</div>}
          {body && <div className="mt-3">{body}</div>}
        </div>
        <footer className="flex items-center justify-end gap-2 border-t border-border-default bg-bg-subtle px-5 py-3">
          <ActionButton onClick={onCancel} disabled={busy}>
            {cancelLabel}
          </ActionButton>
          <ActionButton variant={variant} onClick={onConfirm} disabled={busy || confirmDisabled}>
            {busy ? "Working…" : confirmLabel}
          </ActionButton>
        </footer>
      </div>
    </div>
  );
}
