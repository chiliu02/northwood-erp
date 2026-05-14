import { useCallback, useEffect, useRef, useState } from "react";
import type { Scenario, ScenarioContext, StepStatus } from "./types";

export interface ScenarioRunnerState {
  scenario: Scenario | null;
  status: "idle" | "running" | "paused" | "verifying" | "completed" | "failed";
  activeStepIndex: number;
  stepStatuses: StepStatus[];
  stepErrors: Record<string, string>;
  ctx: ScenarioContext;
}

export interface ScenarioRunnerActions {
  start: (scenario: Scenario) => void;
  resume: () => void;
  skipCurrent: () => void;
  abort: () => void;
}

const INITIAL_STATE: ScenarioRunnerState = {
  scenario: null,
  status: "idle",
  activeStepIndex: -1,
  stepStatuses: [],
  stepErrors: {},
  ctx: {},
};

// How long the verify poll loop waits before failing the step. Mirrors the
// 60s the saga-state waits use, since a human step typically completes
// well inside that window — anything longer is a sign the manual action
// genuinely hasn't happened and the user should use "Skip past verification".
const VERIFY_TIMEOUT_MS = 60_000;
const VERIFY_POLL_INTERVAL_MS = 2000;

/**
 * Drives a scenario through its step list. Auto steps fire when active;
 * human-pause steps pause the runner until the user calls `resume()`. If a
 * human-pause step has a `verify` predicate, "Run step" enters a verifying
 * loop that polls the predicate until satisfied (or 60s timeout); "Skip"
 * bypasses verification.
 */
export function useScenarioRunner(): ScenarioRunnerState & ScenarioRunnerActions {
  const [state, setState] = useState<ScenarioRunnerState>(INITIAL_STATE);
  const abortRef = useRef<AbortController | null>(null);
  const stateRef = useRef(state);
  stateRef.current = state;

  const advance = useCallback(async () => {
    const cur = stateRef.current;
    if (!cur.scenario || cur.status !== "running") return;
    const idx = cur.activeStepIndex;
    if (idx < 0 || idx >= cur.scenario.steps.length) return;
    const step = cur.scenario.steps[idx];

    // human-pause: don't auto-fire; wait for user to resume.
    if (step.kind === "human-pause") {
      setState((s) => ({ ...s, status: "paused" }));
      return;
    }

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setState((s) => ({
      ...s,
      stepStatuses: s.stepStatuses.map((st, i) => (i === idx ? "running" : st)),
    }));

    try {
      const patch = await step.run(stateRef.current.ctx, controller.signal);
      const next = idx + 1;
      const completed = next >= cur.scenario.steps.length;
      setState((s) => ({
        ...s,
        ctx: { ...s.ctx, ...(patch ?? {}) },
        stepStatuses: s.stepStatuses.map((st, i) => (i === idx ? "completed" : st)),
        activeStepIndex: completed ? idx : next,
        status: completed ? "completed" : "running",
      }));
    } catch (e) {
      if ((e as DOMException)?.name === "AbortError") {
        return; // controlled abort; state already updated by abort()
      }
      setState((s) => ({
        ...s,
        stepStatuses: s.stepStatuses.map((st, i) => (i === idx ? "failed" : st)),
        stepErrors: { ...s.stepErrors, [step.id]: String(e) },
        status: "failed",
      }));
    }
  }, []);

  const runVerify = useCallback(async () => {
    const cur = stateRef.current;
    if (!cur.scenario || cur.status !== "verifying") return;
    const idx = cur.activeStepIndex;
    if (idx < 0 || idx >= cur.scenario.steps.length) return;
    const step = cur.scenario.steps[idx];
    if (!step.verify) return;

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const verify = step.verify;

    const started = Date.now();
    try {
      while (Date.now() - started < VERIFY_TIMEOUT_MS) {
        if (controller.signal.aborted) return;
        const ok = await verify(stateRef.current.ctx, controller.signal);
        if (controller.signal.aborted) return;
        if (ok) {
          const next = idx + 1;
          const completed = next >= cur.scenario.steps.length;
          setState((s) => ({
            ...s,
            stepStatuses: s.stepStatuses.map((st, i) => (i === idx ? "completed" : st)),
            activeStepIndex: completed ? idx : next,
            status: completed ? "completed" : "running",
          }));
          return;
        }
        await new Promise((resolve) => setTimeout(resolve, VERIFY_POLL_INTERVAL_MS));
      }
      // Timed out — flip back to paused so the user can try again or Skip.
      setState((s) => ({
        ...s,
        stepStatuses: s.stepStatuses.map((st, i) => (i === idx ? "pending" : st)),
        stepErrors: {
          ...s.stepErrors,
          [step.id]: `Verification timed out after ${VERIFY_TIMEOUT_MS / 1000}s. Re-do the manual step, then Run again — or Skip to bypass.`,
        },
        status: "paused",
      }));
    } catch (e) {
      if ((e as DOMException)?.name === "AbortError") return;
      setState((s) => ({
        ...s,
        stepStatuses: s.stepStatuses.map((st, i) => (i === idx ? "failed" : st)),
        stepErrors: { ...s.stepErrors, [step.id]: String(e) },
        status: "failed",
      }));
    }
  }, []);

  // Whenever activeStepIndex / status flips into running or verifying, drive.
  useEffect(() => {
    if (state.status === "running") {
      void advance();
    } else if (state.status === "verifying") {
      void runVerify();
    }
  }, [state.status, state.activeStepIndex, advance, runVerify]);

  const start = useCallback((scenario: Scenario) => {
    abortRef.current?.abort();
    setState({
      scenario,
      status: "running",
      activeStepIndex: 0,
      stepStatuses: scenario.steps.map(() => "pending"),
      stepErrors: {},
      ctx: { ...(scenario.initialContext ?? {}) },
    });
  }, []);

  const resume = useCallback(() => {
    const cur = stateRef.current;
    if (!cur.scenario) return;
    if (cur.status !== "paused") return;
    const idx = cur.activeStepIndex;
    const step = cur.scenario.steps[idx];

    // Verify-gated: enter verifying mode; the useEffect picks it up.
    if (step.verify) {
      setState((s) => ({
        ...s,
        stepStatuses: s.stepStatuses.map((st, i) => (i === idx ? "verifying" : st)),
        stepErrors: Object.fromEntries(
          Object.entries(s.stepErrors).filter(([k]) => k !== step.id)
        ),
        status: "verifying",
      }));
      return;
    }

    // Fast path — no verify; mark complete + advance immediately.
    const next = idx + 1;
    const completed = next >= cur.scenario.steps.length;
    setState((s) => ({
      ...s,
      stepStatuses: s.stepStatuses.map((st, i) => (i === idx ? "completed" : st)),
      activeStepIndex: completed ? idx : next,
      status: completed ? "completed" : "running",
    }));
  }, []);

  const skipCurrent = useCallback(() => {
    abortRef.current?.abort();
    const cur = stateRef.current;
    if (!cur.scenario) return;
    const idx = cur.activeStepIndex;
    const next = idx + 1;
    const completed = next >= cur.scenario.steps.length;
    setState((s) => ({
      ...s,
      stepStatuses: s.stepStatuses.map((st, i) => (i === idx ? "skipped" : st)),
      activeStepIndex: completed ? idx : next,
      status: completed ? "completed" : "running",
    }));
  }, []);

  const abort = useCallback(() => {
    abortRef.current?.abort();
    setState(INITIAL_STATE);
  }, []);

  // Cleanup on unmount.
  useEffect(() => {
    return () => abortRef.current?.abort();
  }, []);

  return { ...state, start, resume, skipCurrent, abort };
}
