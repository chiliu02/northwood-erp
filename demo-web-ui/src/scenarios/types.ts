// Scenario runner data model. A Scenario is a sequence of Steps; each Step is
// either an action (fires an API call) or a wait (polls until a condition
// matches). Steps share a mutable ScenarioContext so a downstream step can
// reference what an upstream step produced (e.g. "place order" stamps
// salesOrderId, "post shipment" reads it).

export interface ScenarioContext {
  salesOrderHeaderId?: string;
  customerCode?: string;
  productId?: string;
  productSku?: string;
  productName?: string;
  unitPrice?: string;
  unitCost?: string;
  orderedQuantity?: string;
  workOrderIds?: string[];
  purchaseOrderHeaderId?: string;
}

export type StepStatus = "pending" | "running" | "verifying" | "completed" | "skipped" | "failed";

export interface ScenarioStep {
  id: string;
  /** Short label rendered in the stepper. */
  title: string;
  /** Longer hint shown under the title when the step is active. */
  hint?: string;
  /** Auto = runner fires immediately on its turn; human-pause = wait for the user to click "Run". */
  kind: "auto" | "human-pause";
  /**
   * Runs the step. Returns a partial context patch merged into the scenario's running ctx.
   * Throw to fail the step; the runner catches and surfaces the error.
   */
  run: (ctx: ScenarioContext, signal: AbortSignal) => Promise<Partial<ScenarioContext> | void>;
  /**
   * Optional gating predicate for {@code kind: "human-pause"} steps. When set,
   * the runner polls this predicate after the user clicks "Run step" — the
   * step only advances once it returns {@code true}. The "Skip" button
   * bypasses the predicate. Auto-kind steps ignore this field.
   *
   * <p>Use to catch "user clicked Run before actually doing the manual
   * action" — e.g. all operations on a work order are still {@code planned}
   * because the operator didn't actually mark them completed. Without verify
   * the saga timeout 60s later surfaces an opaque error; with verify the
   * runner stays on the step and reports "verifying..." until the predicate
   * matches reality.
   */
  verify?: (ctx: ScenarioContext, signal: AbortSignal) => Promise<boolean>;
}

export interface Scenario {
  id: string;
  title: string;
  /** One-line elevator pitch shown in the dropdown. */
  description: string;
  /** Initial-context overrides — usually seed UUIDs and quantities. */
  initialContext?: Partial<ScenarioContext>;
  steps: ScenarioStep[];
}
