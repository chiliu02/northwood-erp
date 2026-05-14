import { createContext, useCallback, useContext, useState, useEffect } from "react";
import { CheckCircle2, AlertTriangle, XCircle, X } from "lucide-react";
import clsx from "clsx";
import type { ReactNode } from "react";

type ToastTone = "success" | "warn" | "error";

interface Toast {
  id: number;
  tone: ToastTone;
  message: string;
}

interface ToastContextValue {
  show: (tone: ToastTone, message: string) => void;
  success: (message: string) => void;
  warn: (message: string) => void;
  error: (message: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

/**
 * Lightweight transient notification system. Toasts auto-dismiss after
 * 5s; success and warn lean transient, errors persist until dismissed.
 * Stacks bottom-right of the viewport.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const dismiss = useCallback((id: number) => {
    setToasts((ts) => ts.filter((t) => t.id !== id));
  }, []);

  const show = useCallback((tone: ToastTone, message: string) => {
    const id = Date.now() + Math.random();
    setToasts((ts) => [...ts, { id, tone, message }]);
    if (tone !== "error") {
      setTimeout(() => dismiss(id), 5000);
    }
  }, [dismiss]);

  const value: ToastContextValue = {
    show,
    success: (m) => show("success", m),
    warn: (m) => show("warn", m),
    error: (m) => show("error", m),
  };

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex max-w-sm flex-col gap-2">
        {toasts.map((t) => (
          <ToastItem key={t.id} toast={t} onDismiss={() => dismiss(t.id)} />
        ))}
      </div>
    </ToastContext.Provider>
  );
}

function ToastItem({ toast, onDismiss }: { toast: Toast; onDismiss: () => void }) {
  const Icon = ICONS[toast.tone];
  // Slide-in animation triggered by mount.
  const [visible, setVisible] = useState(false);
  useEffect(() => { setVisible(true); }, []);
  return (
    <div
      role="status"
      aria-live="polite"
      className={clsx(
        "pointer-events-auto flex items-start gap-3 rounded-md border px-4 py-3 shadow-lg transition-all",
        visible ? "translate-x-0 opacity-100" : "translate-x-2 opacity-0",
        TONE_CLASSES[toast.tone]
      )}
    >
      <Icon className="h-4 w-4 shrink-0 mt-0.5" />
      <div className="flex-1 text-sm">{toast.message}</div>
      <button
        type="button"
        onClick={onDismiss}
        className="-mr-1 -mt-0.5 flex h-6 w-6 items-center justify-center rounded text-current opacity-60 hover:bg-bg-subtle hover:opacity-100"
        aria-label="Dismiss"
      >
        <X className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}

const TONE_CLASSES: Record<ToastTone, string> = {
  success: "border-status-success/30 bg-bg-surface text-text-primary",
  warn: "border-status-warn/30 bg-bg-surface text-text-primary",
  error: "border-status-error/30 bg-bg-surface text-text-primary",
};

const ICONS: Record<ToastTone, typeof CheckCircle2> = {
  success: CheckCircle2,
  warn: AlertTriangle,
  error: XCircle,
};

/** Hook for raising toasts. Throws if used outside <ToastProvider>. */
export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used inside <ToastProvider>");
  return ctx;
}
