import { Check, ChevronRight, Circle, FastForward, Loader2, Play, Square, X, AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/Form";
import { cn, truncateUuid } from "@/lib/utils";
import type { ScenarioRunnerActions, ScenarioRunnerState } from "@/scenarios/runner";

interface ScenarioRunnerModalProps {
  runner: ScenarioRunnerState & ScenarioRunnerActions;
  open: boolean;
  onClose: () => void;
}

export function ScenarioRunnerModal({ runner, open, onClose }: ScenarioRunnerModalProps) {
  if (!open || !runner.scenario) return null;
  const { scenario, status, activeStepIndex, stepStatuses, stepErrors, ctx } = runner;

  const overallProgress = stepStatuses.filter((s) => s === "completed" || s === "skipped").length;

  return (
    <div
      className="fixed inset-0 z-40 flex items-start justify-center bg-bg-base/85 backdrop-blur-sm p-6"
      onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="mt-12 w-full max-w-3xl overflow-hidden rounded-lg border border-border-subtle bg-bg-elevated shadow-2xl">
        <header className="flex items-start justify-between border-b border-border-subtle px-5 py-3">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-lg font-semibold">{scenario.title}</h2>
              <StatusPill status={status} />
            </div>
            <p className="mt-0.5 text-xs text-text-muted">{scenario.description}</p>
          </div>
          <Button variant="ghost" onClick={onClose} aria-label="close">
            <X className="h-4 w-4" />
          </Button>
        </header>

        <div className="max-h-[60vh] overflow-y-auto p-5 scrollbar-thin">
          <ol className="space-y-2">
            {scenario.steps.map((step, idx) => {
              const stepStatus = stepStatuses[idx];
              const isActive = idx === activeStepIndex && status !== "completed";
              const error = stepErrors[step.id];
              return (
                <li
                  key={step.id}
                  className={cn(
                    "flex gap-3 rounded-md border px-3 py-2 transition-colors",
                    isActive
                      ? "border-state-active bg-bg-base"
                      : stepStatus === "completed"
                        ? "border-border-subtle opacity-70"
                        : stepStatus === "failed"
                          ? "border-state-error"
                          : stepStatus === "skipped"
                            ? "border-border-subtle opacity-50"
                            : "border-border-subtle"
                  )}
                  style={
                    isActive ? { borderColor: "var(--color-state-active)" } :
                    stepStatus === "failed" ? { borderColor: "var(--color-state-error)" } :
                    undefined
                  }
                >
                  <StepIcon status={stepStatus} active={isActive} />
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <span className={cn(
                        "text-sm font-medium",
                        stepStatus === "completed" && "line-through text-text-muted"
                      )}>
                        {step.title}
                      </span>
                      {step.kind === "human-pause" && (
                        <span className="rounded bg-bg-hover px-1.5 py-0.5 text-[10px] uppercase tracking-wider text-text-muted">
                          human
                        </span>
                      )}
                    </div>
                    {(isActive || stepStatus === "failed") && step.hint && (
                      <p className="mt-1 text-xs text-text-muted">{step.hint}</p>
                    )}
                    {error && (
                      <p
                        className="mt-1 flex items-center gap-1 text-xs"
                        style={{ color: "var(--color-state-error)" }}
                      >
                        <AlertTriangle className="h-3 w-3" /> {error}
                      </p>
                    )}
                  </div>
                </li>
              );
            })}
          </ol>

          <ContextPanel ctx={ctx} />
        </div>

        <footer className="flex items-center justify-between gap-3 border-t border-border-subtle bg-bg-base/30 px-5 py-3">
          <span className="text-xs text-text-faint tabular-nums">
            step {Math.min(overallProgress + 1, scenario.steps.length)} / {scenario.steps.length}
          </span>
          <div className="flex items-center gap-2">
            {status === "verifying" && (
              <span
                className="flex items-center gap-1.5 text-xs"
                style={{ color: "var(--color-state-active)" }}
              >
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
                verifying manual step…
              </span>
            )}
            {status === "paused" && (
              <Button variant="primary" onClick={runner.resume}>
                <Play className="-ml-0.5 mr-1 inline h-3.5 w-3.5" /> Run step
              </Button>
            )}
            {(status === "running" || status === "paused") && (
              <Button variant="secondary" onClick={runner.skipCurrent}>
                <FastForward className="-ml-0.5 mr-1 inline h-3.5 w-3.5" /> Skip
              </Button>
            )}
            {status === "verifying" && (
              <Button variant="secondary" onClick={runner.skipCurrent}>
                <FastForward className="-ml-0.5 mr-1 inline h-3.5 w-3.5" /> Skip past verification
              </Button>
            )}
            {status === "failed" && (
              <Button variant="secondary" onClick={runner.skipCurrent}>
                <FastForward className="-ml-0.5 mr-1 inline h-3.5 w-3.5" /> Skip past failure
              </Button>
            )}
            <Button variant="destructive" onClick={runner.abort}>
              <Square className="-ml-0.5 mr-1 inline h-3.5 w-3.5" /> Abort
            </Button>
          </div>
        </footer>
      </div>
    </div>
  );
}

function StatusPill({ status }: { status: ScenarioRunnerState["status"] }) {
  const map: Record<ScenarioRunnerState["status"], { label: string; colour: string }> = {
    idle:      { label: "idle",      colour: "var(--color-text-faint)" },
    running:   { label: "running",   colour: "var(--color-state-active)" },
    paused:    { label: "paused",    colour: "var(--color-state-warn)" },
    verifying: { label: "verifying", colour: "var(--color-state-active)" },
    completed: { label: "completed", colour: "var(--color-state-terminal)" },
    failed:    { label: "failed",    colour: "var(--color-state-error)" },
  };
  const { label, colour } = map[status];
  return (
    <span
      className="rounded border px-2 py-0.5 text-[10px] uppercase tracking-wider"
      style={{ color: colour, borderColor: colour }}
    >
      {label}
    </span>
  );
}

function StepIcon({ status, active }: { status: ScenarioRunnerState["stepStatuses"][number]; active: boolean }) {
  if (status === "completed") {
    return <Check className="mt-0.5 h-4 w-4 shrink-0" style={{ color: "var(--color-state-success)" }} />;
  }
  if (status === "failed") {
    return <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" style={{ color: "var(--color-state-error)" }} />;
  }
  if (status === "skipped") {
    return <ChevronRight className="mt-0.5 h-4 w-4 shrink-0 text-text-faint" />;
  }
  if (status === "running" || status === "verifying") {
    return <Loader2 className="mt-0.5 h-4 w-4 shrink-0 animate-spin" style={{ color: "var(--color-state-active)" }} />;
  }
  if (active) {
    return <Play className="mt-0.5 h-4 w-4 shrink-0" style={{ color: "var(--color-state-warn)" }} />;
  }
  return <Circle className="mt-0.5 h-4 w-4 shrink-0 text-text-faint" />;
}

function ContextPanel({ ctx }: { ctx: ScenarioRunnerState["ctx"] }) {
  const entries = Object.entries(ctx).filter(([, v]) => v != null && v !== "");
  if (entries.length === 0) return null;
  return (
    <details className="mt-4 rounded-md border border-border-subtle p-3">
      <summary className="cursor-pointer list-none text-[11px] uppercase tracking-wider text-text-muted">
        captured context ({entries.length})
      </summary>
      <dl className="mt-2 grid grid-cols-1 gap-1.5 text-xs">
        {entries.map(([k, v]) => (
          <div key={k} className="flex justify-between gap-3">
            <dt className="text-text-muted">{k}</dt>
            <dd className="font-mono text-text-faint">{renderValue(v)}</dd>
          </div>
        ))}
      </dl>
    </details>
  );
}

function renderValue(v: unknown): string {
  if (Array.isArray(v)) {
    if (v.length === 0) return "[]";
    if (typeof v[0] === "string") return `[${v.map((s) => truncateUuid(String(s))).join(", ")}]`;
    return JSON.stringify(v);
  }
  const s = String(v);
  // Prefer truncated UUID rendering.
  if (/^[0-9a-f]{8}-/.test(s)) return truncateUuid(s);
  return s;
}
