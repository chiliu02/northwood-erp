import { Bell, Play, ChevronDown, Activity } from "lucide-react";
import { PERSONAS, PERSONA_ORDER, type PersonaKey } from "@/personas";
import { useState } from "react";
import { cn } from "@/lib/utils";
import { SCENARIOS } from "@/scenarios/scenarios";
import type { Scenario } from "@/scenarios/types";

interface TopBarProps {
  activePersona: PersonaKey;
  onPersonaChange: (key: PersonaKey) => void;
  onToggleDrawer: () => void;
  onStartScenario: (scenario: Scenario) => void;
  scenarioRunning: boolean;
  onOpenScenarioModal: () => void;
}

export function TopBar({
  activePersona, onPersonaChange, onToggleDrawer,
  onStartScenario, scenarioRunning, onOpenScenarioModal,
}: TopBarProps) {
  const persona = PERSONAS[activePersona];
  const [open, setOpen] = useState(false);
  const [scenariosOpen, setScenariosOpen] = useState(false);

  return (
    <header className="flex h-14 shrink-0 items-center justify-between border-b border-border-subtle bg-bg-elevated px-6">
      <div className="flex items-center gap-3">
        <div className="h-6 w-6 rounded-sm" style={{ background: "linear-gradient(135deg, var(--color-persona-sarah), var(--color-persona-olivia))" }} />
        <span className="font-semibold tracking-tight">Northwood ERP</span>
        <span className="ml-2 text-xs uppercase tracking-wider text-text-faint">demo</span>
      </div>

      <div className="flex items-center gap-2">
        {/* Persona switcher */}
        <div className="relative">
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            className="flex items-center gap-2 rounded-md border border-border-subtle px-3 py-1.5 text-sm hover:bg-bg-hover"
          >
            <span
              className="h-2 w-2 rounded-full"
              style={{ background: persona.accentVar }}
              aria-hidden
            />
            <span className="text-text-muted">Persona:</span>
            <span className="font-medium">{persona.name}</span>
            <span className="text-text-faint">· {persona.role}</span>
            <ChevronDown className="h-3.5 w-3.5 text-text-faint" />
          </button>
          {open && (
            <div className="absolute right-0 top-full z-50 mt-1 w-56 overflow-hidden rounded-md border border-border-subtle bg-bg-elevated shadow-xl">
              {PERSONA_ORDER.map((key) => {
                const p = PERSONAS[key];
                return (
                  <button
                    key={key}
                    type="button"
                    onClick={() => { onPersonaChange(key); setOpen(false); }}
                    className={cn(
                      "flex w-full items-center gap-3 px-3 py-2 text-left text-sm hover:bg-bg-hover",
                      activePersona === key && "bg-bg-hover"
                    )}
                  >
                    <span
                      className="h-2 w-2 rounded-full"
                      style={{ background: p.accentVar }}
                      aria-hidden
                    />
                    <span className="font-medium">{p.name}</span>
                    <span className="ml-auto text-xs text-text-faint">{p.role}</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Scenarios */}
        <div className="relative">
          {scenarioRunning ? (
            <button
              type="button"
              onClick={onOpenScenarioModal}
              className="flex items-center gap-2 rounded-md border px-3 py-1.5 text-sm hover:bg-bg-hover"
              style={{ borderColor: "var(--color-state-active)" }}
            >
              <Activity className="h-3.5 w-3.5 animate-pulse" style={{ color: "var(--color-state-active)" }} />
              Scenario running
            </button>
          ) : (
            <button
              type="button"
              onClick={() => setScenariosOpen((v) => !v)}
              className="flex items-center gap-2 rounded-md border border-border-subtle px-3 py-1.5 text-sm hover:bg-bg-hover"
            >
              <Play className="h-3.5 w-3.5" />
              Scenarios
              <ChevronDown className="h-3.5 w-3.5" />
            </button>
          )}
          {scenariosOpen && !scenarioRunning && (
            <div className="absolute right-0 top-full z-50 mt-1 w-80 overflow-hidden rounded-md border border-border-subtle bg-bg-elevated shadow-xl">
              {SCENARIOS.map((s) => (
                <button
                  key={s.id}
                  type="button"
                  onClick={() => { onStartScenario(s); setScenariosOpen(false); }}
                  className="block w-full border-b border-border-subtle px-3 py-2.5 text-left last:border-b-0 hover:bg-bg-hover"
                >
                  <div className="text-sm font-medium">{s.title}</div>
                  <div className="mt-0.5 text-xs text-text-muted">{s.description}</div>
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Events drawer toggle */}
        <button
          type="button"
          onClick={onToggleDrawer}
          className="flex items-center gap-2 rounded-md border border-border-subtle px-3 py-1.5 text-sm hover:bg-bg-hover"
        >
          <Bell className="h-3.5 w-3.5" />
          Events
        </button>
      </div>
    </header>
  );
}
