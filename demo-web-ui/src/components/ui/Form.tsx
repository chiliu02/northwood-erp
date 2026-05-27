import { type ReactNode, type InputHTMLAttributes, type SelectHTMLAttributes, type ButtonHTMLAttributes } from "react";
import { cn } from "@/lib/utils";

const FIELD_BASE =
  "w-full rounded-md border border-border-subtle bg-bg-base px-3 py-1.5 text-sm text-text-primary " +
  "placeholder:text-text-faint focus:outline-none focus:ring-1 focus:ring-state-active focus:border-state-active " +
  "disabled:opacity-50";

export function FieldRow({ label, hint, error, required, children }: {
  label: string;
  hint?: string;
  error?: string;
  required?: boolean;
  children: ReactNode;
}) {
  return (
    <label className="block space-y-1.5">
      <span className="flex items-baseline gap-1.5 text-xs font-medium uppercase tracking-wider text-text-muted">
        {label}
        {required && <span style={{ color: "var(--color-state-error)" }}>*</span>}
      </span>
      {children}
      {error
        ? <span className="block text-[11px]" style={{ color: "var(--color-state-error)" }}>{error}</span>
        : hint
          ? <span className="block text-[11px] text-text-faint">{hint}</span>
          : null}
    </label>
  );
}

export function Input({ className, ...rest }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn(FIELD_BASE, "tabular-nums", className)} {...rest} />;
}

export function Select({ className, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement> & { children: ReactNode }) {
  return (
    <select className={cn(FIELD_BASE, className)} {...rest}>
      {children}
    </select>
  );
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: "primary" | "secondary" | "ghost" | "destructive";
}

export function Button({ variant = "primary", className, ...rest }: ButtonProps) {
  const styles = {
    primary:     "bg-bg-base border-state-active text-text-primary hover:bg-bg-hover",
    secondary:   "border-border-subtle text-text-primary hover:bg-bg-hover",
    ghost:       "border-transparent text-text-muted hover:text-text-primary hover:bg-bg-hover",
    destructive: "border-state-error text-text-primary hover:bg-bg-hover",
  }[variant];
  const accentStyle =
    variant === "primary"     ? { borderColor: "var(--color-state-active)" } :
    variant === "destructive" ? { borderColor: "var(--color-state-error)"  } :
    undefined;
  return (
    <button
      type="button"
      className={cn(
        "inline-flex items-center justify-center gap-1.5 rounded-md border px-3 py-1.5 text-sm font-medium transition-colors",
        "disabled:cursor-not-allowed disabled:opacity-50",
        styles,
        className
      )}
      style={accentStyle}
      {...rest}
    />
  );
}

export function FormCard({ title, persona, children }: { title: string; persona?: ReactNode; children: ReactNode }) {
  return (
    <div className="space-y-4">
      <div className="flex items-baseline gap-3">
        <h1 className="text-[28px] font-semibold tracking-tight">{title}</h1>
        {persona}
      </div>
      <div className="rounded-lg border border-border-subtle bg-bg-elevated p-5">
        {children}
      </div>
    </div>
  );
}

export function FormStatus({ state }: { state: { status: "idle" | "submitting" | "success" | "error"; message?: string } }) {
  if (state.status === "idle") return null;
  if (state.status === "submitting") {
    return <p className="text-xs text-text-muted">submitting…</p>;
  }
  if (state.status === "success") {
    return (
      <p
        className="rounded-md border px-3 py-2 text-xs"
        style={{
          borderColor: "var(--color-state-success)",
          background: "color-mix(in srgb, var(--color-state-success) 12%, transparent)",
          color: "var(--color-state-success)",
        }}
      >
        {state.message ?? "saved"}
      </p>
    );
  }
  return (
    <p
      className="rounded-md border px-3 py-2 text-xs"
      style={{
        borderColor: "var(--color-state-error)",
        background: "color-mix(in srgb, var(--color-state-error) 12%, transparent)",
        color: "var(--color-state-error)",
      }}
    >
      {state.message ?? "failed"}
    </p>
  );
}

export type SubmitState =
  | { status: "idle" }
  | { status: "submitting" }
  | { status: "success"; message?: string }
  | { status: "error";   message?: string };

export function PersonaTag({ name, role, accentVar }: { name: string; role: string; accentVar: string }) {
  return (
    <span className="flex items-center gap-2 text-sm text-text-muted">
      <span className="h-1.5 w-1.5 rounded-full" style={{ background: accentVar }} aria-hidden />
      {name} · {role}
    </span>
  );
}
