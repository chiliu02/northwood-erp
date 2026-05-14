import { useEffect, type ReactNode } from "react";
import { X } from "lucide-react";
import { Button } from "./Form";

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: string;
  subtitle?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
}

export function Modal({ open, onClose, title, subtitle, children, footer }: ModalProps) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-bg-base/80 backdrop-blur-sm"
      onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="w-full max-w-2xl overflow-hidden rounded-lg border border-border-subtle bg-bg-elevated shadow-2xl">
        <header className="flex items-start justify-between border-b border-border-subtle px-5 py-3">
          <div>
            <h2 className="text-lg font-semibold">{title}</h2>
            {subtitle && <div className="mt-0.5 text-xs text-text-muted">{subtitle}</div>}
          </div>
          <Button variant="ghost" onClick={onClose} aria-label="close">
            <X className="h-4 w-4" />
          </Button>
        </header>
        <div className="max-h-[70vh] overflow-y-auto p-5 scrollbar-thin">{children}</div>
        {footer && <footer className="flex items-center justify-end gap-3 border-t border-border-subtle bg-bg-base/30 px-5 py-3">{footer}</footer>}
      </div>
    </div>
  );
}
