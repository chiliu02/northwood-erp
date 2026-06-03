import { Link } from "react-router-dom";
import {
  ArrowRight,
  ShoppingCart,
  Package,
  Warehouse,
  Factory,
  Receipt,
  BookOpen,
  BarChart3,
  Settings,
  type LucideIcon,
} from "lucide-react";
import { Breadcrumb } from "@/components/layout/Breadcrumb";
import { useCurrentUser } from "@/lib/UserContext";

type ModuleName =
  | "Master Data" | "Sales" | "Purchasing" | "Inventory"
  | "Manufacturing" | "Finance" | "Reporting" | "System";

const MODULE_ICON: Record<ModuleName, LucideIcon> = {
  "Master Data": BookOpen,
  Sales: ShoppingCart,
  Purchasing: Package,
  Inventory: Warehouse,
  Manufacturing: Factory,
  Finance: Receipt,
  Reporting: BarChart3,
  System: Settings,
};

interface Action {
  to: string;
  module: ModuleName;
  title: string;
  description: string;
}

// What each role does day-to-day → the screens it lands on. A persona maps to a
// single Keycloak realm role (see system/Users), but a user with several roles
// gets the union of their actions (deduped by destination, first-seen order).
const ROLE_ACTIONS: Record<string, Action[]> = {
  catalog_manager: [
    { to: "/products", module: "Master Data", title: "Products", description: "Maintain pricing, reorder policy, make-vs-buy, and discontinue SKUs." },
  ],
  sales_clerk: [
    { to: "/sales-orders", module: "Sales", title: "Sales Orders", description: "Place and track customer orders." },
    { to: "/customers", module: "Sales", title: "Customers", description: "Register and manage customers." },
  ],
  sales_manager: [
    { to: "/sales-orders", module: "Sales", title: "Sales Orders", description: "Oversee and cancel customer orders." },
    { to: "/atp", module: "Reporting", title: "Available-to-Promise", description: "Check what stock can be promised." },
  ],
  warehouse_clerk: [
    { to: "/goods-receipts", module: "Inventory", title: "Goods Receipts", description: "Post inbound stock against purchase orders." },
    { to: "/shipments", module: "Inventory", title: "Shipments", description: "Post outbound stock against sales orders." },
    { to: "/stock-movements", module: "Inventory", title: "Stock Movements", description: "Trace every stock in/out." },
  ],
  warehouse_manager: [
    { to: "/stock-items", module: "Inventory", title: "Stock Balances", description: "Review on-hand and adjust balances." },
    { to: "/stock-reservations", module: "Inventory", title: "Reservations", description: "See what stock is committed." },
  ],
  production_planner: [
    { to: "/production-board", module: "Manufacturing", title: "Production Board", description: "Plan and prioritise work orders." },
    { to: "/work-orders", module: "Manufacturing", title: "Work Orders", description: "Release and track production." },
    { to: "/boms", module: "Manufacturing", title: "Bill of Materials", description: "Review the active BOM per product." },
    { to: "/material-shortages", module: "Reporting", title: "Material Shortage", description: "See what's short for production." },
  ],
  production_supervisor: [
    { to: "/production-board", module: "Manufacturing", title: "Production Board", description: "Drive work orders through the floor." },
    { to: "/work-orders", module: "Manufacturing", title: "Work Orders", description: "Complete operations and sign off." },
  ],
  purchasing_clerk: [
    { to: "/purchase-requisitions", module: "Purchasing", title: "Purchase Requisitions", description: "Raise requisitions for purchased products." },
    { to: "/goods-receipts", module: "Inventory", title: "Goods Receipts", description: "Process inbound receipts." },
  ],
  purchasing_manager: [
    { to: "/purchase-orders", module: "Purchasing", title: "Purchase Orders", description: "Approve and track purchase orders." },
    { to: "/supplier-prices", module: "Purchasing", title: "Supplier Prices", description: "Maintain supplier price lists." },
    { to: "/purchase-orders/tracking", module: "Reporting", title: "PO Tracking", description: "Follow the full procure-to-pay trajectory." },
  ],
  accountant: [
    { to: "/supplier-invoices", module: "Finance", title: "Supplier Invoices", description: "Record invoices and run 3-way match." },
    { to: "/payments", module: "Finance", title: "Payments", description: "Process AP and AR payments." },
    { to: "/ar-ap", module: "Finance", title: "AR / AP Dashboard", description: "Watch receivables and payables." },
  ],
  finance_manager: [
    { to: "/supplier-invoices/pending-review", module: "Finance", title: "Pending Review", description: "Approve 3-way-match-failed invoices." },
    { to: "/journal-entries", module: "Finance", title: "Journal Entries", description: "Review and reverse GL postings." },
    { to: "/financial-dashboard", module: "Reporting", title: "Financial Dashboard", description: "Watch the GL and inventory value." },
  ],
  auditor: [
    { to: "/system/audit-log", module: "System", title: "Audit Log", description: "Trace every change across the system." },
    { to: "/journal-entries", module: "Finance", title: "Journal Entries", description: "Inspect the general ledger." },
    { to: "/financial-dashboard", module: "Reporting", title: "Financial Dashboard", description: "Review the financial position." },
  ],
  sysadmin: [
    { to: "/system/users", module: "System", title: "Users", description: "Realm administration — the demo personas + roles." },
  ],
};

// Shown when the user has no role we recognise (or before /api/me resolves).
const GENERIC_ACTIONS: Action[] = [
  { to: "/sales-orders", module: "Sales", title: "Sales Orders", description: "View, place, and manage customer orders." },
  { to: "/work-orders", module: "Manufacturing", title: "Work Orders", description: "Track production and complete operations." },
  { to: "/purchase-orders", module: "Purchasing", title: "Purchase Orders", description: "Approve and track purchase orders." },
  { to: "/supplier-invoices/pending-review", module: "Finance", title: "Pending Review", description: "Resolve 3-way-match-failed invoices." },
];

function humaniseRole(role: string): string {
  return role.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}

function actionsForRoles(roles: string[]): Action[] {
  const seen = new Set<string>();
  const out: Action[] = [];
  for (const role of roles) {
    for (const a of ROLE_ACTIONS[role] ?? []) {
      if (!seen.has(a.to)) {
        seen.add(a.to);
        out.push(a);
      }
    }
  }
  return out;
}

/**
 * Landing page — a role-aware launcher. Greets the signed-in persona and shows
 * the quick actions for their role(s) (read from {@code /api/me} via
 * {@link useCurrentUser}). Falls back to a generic action set when no role is
 * recognised or before the user resolves. Sign out / back in as another persona
 * (top-right) to see a different home.
 */
export function Home() {
  const { me } = useCurrentUser();

  const roleActions = me ? actionsForRoles(me.roles) : [];
  const actions = roleActions.length > 0 ? roleActions : GENERIC_ACTIONS;
  const personalised = me != null && roleActions.length > 0;

  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-border-default bg-bg-surface px-8 py-5">
        <Breadcrumb trail={[{ label: "Home" }]} />
        <h1 className="mt-2 text-xl font-semibold text-text-primary">
          {me ? `Welcome, ${me.fullName ?? me.username}` : "Welcome to Northwood ERP"}
        </h1>
        <div className="mt-1 flex items-center gap-2">
          <p className="text-sm text-text-muted">
            {personalised ? "Your common tasks are below." : "Pick a module from the sidebar, or jump to a common task below."}
          </p>
          {me && me.roles.length > 0 && (
            <span className="flex flex-wrap gap-1">
              {me.roles.map((r) => (
                <span key={r} className="rounded bg-bg-subtle px-1.5 py-0.5 text-[11px] font-medium text-text-secondary">
                  {humaniseRole(r)}
                </span>
              ))}
            </span>
          )}
        </div>
      </div>

      <div className="flex-1 px-8 py-6">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {actions.map((a) => (
            <QuickAction key={a.to} action={a} />
          ))}
        </div>
      </div>
    </div>
  );
}

function QuickAction({ action }: { action: Action }) {
  const Icon = MODULE_ICON[action.module];
  return (
    <Link
      to={action.to}
      className="group flex flex-col rounded-md border border-border-default bg-bg-surface p-4 transition hover:border-border-strong"
    >
      <div className="mb-3 flex items-center justify-between">
        <div className="flex h-9 w-9 items-center justify-center rounded-md bg-brand-primary-soft text-brand-primary">
          <Icon className="h-5 w-5" />
        </div>
        <ArrowRight className="h-4 w-4 text-text-faint group-hover:text-text-secondary" />
      </div>
      <div className="text-[11px] font-medium uppercase tracking-wide text-text-muted">{action.module}</div>
      <div className="mt-0.5 text-sm font-semibold text-text-primary">{action.title}</div>
      <p className="mt-1 text-xs text-text-muted">{action.description}</p>
    </Link>
  );
}
