import { useState } from "react";
import { Outlet } from "react-router-dom";
import { TopBar } from "./TopBar";
import { Sidebar } from "./Sidebar";
import { EventDrawer } from "./EventDrawer";
import { ScenarioRunnerModal } from "@/components/ScenarioRunnerModal";
import { useScenarioRunner } from "@/scenarios/runner";
import { EventStreamProvider } from "@/events/EventStreamContext";
import { type PersonaKey } from "@/personas";

export function AppShell() {
  const [activePersona, setActivePersona] = useState<PersonaKey>("sarah");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [scenarioModalOpen, setScenarioModalOpen] = useState(false);
  const runner = useScenarioRunner();

  function startScenario(s: Parameters<typeof runner.start>[0]) {
    runner.start(s);
    setScenarioModalOpen(true);
  }

  return (
    <EventStreamProvider>
      <div className="flex h-screen flex-col bg-bg-base text-text-primary">
        <TopBar
          activePersona={activePersona}
          onPersonaChange={setActivePersona}
          onToggleDrawer={() => setDrawerOpen((v) => !v)}
          onStartScenario={startScenario}
          scenarioRunning={runner.status === "running" || runner.status === "paused" || runner.status === "verifying"}
          onOpenScenarioModal={() => setScenarioModalOpen(true)}
        />
        <div className="flex flex-1 overflow-hidden">
          <Sidebar activePersona={activePersona} />
          <main className="flex-1 overflow-y-auto px-8 py-6 scrollbar-thin">
            <Outlet />
          </main>
        </div>
        <EventDrawer open={drawerOpen} onToggle={() => setDrawerOpen((v) => !v)} />
        <ScenarioRunnerModal
          runner={runner}
          open={scenarioModalOpen}
          onClose={() => setScenarioModalOpen(false)}
        />
      </div>
    </EventStreamProvider>
  );
}
