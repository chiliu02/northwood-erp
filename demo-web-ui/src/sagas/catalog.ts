// Static catalog of saga state machines. Mirrors the CHECK constraints in
// db/northwood_erp.sql and the comments in CLAUDE.md § "Saga / process
// manager". Used by SagaConsole to render the step indicator.
//
// The visible-stages list is curated to the *forward path*, not every state.
// Side rails (compensating / compensated / failed) render as pulled-out
// badges instead of inline steps.

import type { PersonaKey } from "@/personas";

export interface SagaCatalog {
  type: string;
  label: string;
  service: string;
  servicePort: number;
  persona: PersonaKey;
  domainKeyLabel: string;
  /** Forward-path stages, in order. Indexes into this list drive the step indicator. */
  forwardStages: string[];
  /** Side-rail states — render as a single badge, not a step. */
  sideRailStates: string[];
  /** Terminal states (saga is finished). */
  terminalStates: string[];
}

export const SAGA_CATALOGS: SagaCatalog[] = [
  {
    type: "sales_order_fulfilment",
    label: "Sales fulfilment",
    service: "sales",
    servicePort: 8082,
    persona: "sarah",
    domainKeyLabel: "sales_order_header_id",
    forwardStages: [
      "started",
      "stock_reservation_requested",
      "stock_reserved",
      "manufacturing_requested",
      "manufacturing_in_progress",
      "manufacturing_completed",
      "ready_to_ship",
      "goods_shipped",
      "invoice_requested",
      "invoice_created",
      "invoice_paid",
      "completed",
    ],
    sideRailStates: ["stock_reservation_failed", "compensating", "compensated", "failed"],
    terminalStates: ["completed", "compensated", "failed"],
  },
  {
    type: "make_to_order",
    label: "Make-to-order",
    service: "manufacturing",
    servicePort: 8084,
    persona: "linda",
    domainKeyLabel: "work_order_id",
    forwardStages: [
      "started",
      "work_order_created",
      "bom_exploded",
      "raw_material_reservation_requested",
      "raw_materials_reserved",
      "production_released",
      "production_started",
      "production_completed",
      "finished_goods_received",
      "completed",
    ],
    sideRailStates: [
      "raw_material_shortage",
      "purchase_requisition_requested",
      "waiting_for_purchased_materials",
      "compensating",
      "compensated",
      "failed",
    ],
    terminalStates: ["completed", "compensated", "failed"],
  },
  {
    type: "purchase_to_pay",
    label: "Purchase-to-pay",
    service: "purchasing",
    servicePort: 8085,
    persona: "tom",
    domainKeyLabel: "purchase_order_header_id",
    forwardStages: [
      "started",
      "purchase_order_approved",
      "waiting_for_goods",
      "goods_received",
      "supplier_invoice_approved",
      "supplier_payment_made",
      "completed",
    ],
    sideRailStates: ["failed"],
    terminalStates: ["completed", "failed"],
  },
];

export function findCatalog(type: string): SagaCatalog | undefined {
  return SAGA_CATALOGS.find((c) => c.type === type);
}

/** Returns the index of the given state in the forward path, or -1 if it's a side rail. */
export function stageIndex(catalog: SagaCatalog, state: string): number {
  return catalog.forwardStages.indexOf(state);
}

export function isSideRail(catalog: SagaCatalog, state: string): boolean {
  return catalog.sideRailStates.includes(state);
}

export function isTerminal(catalog: SagaCatalog, state: string): boolean {
  return catalog.terminalStates.includes(state);
}
