import { useState } from "react";
import { NavLink } from "react-router-dom";
import {
  BookOpen,
  ChevronDown,
  ChevronRight,
  Home,
  ShoppingCart,
  Package,
  Warehouse,
  Factory,
  Receipt,
  BarChart3,
  Settings,
  type LucideIcon,
} from "lucide-react";
import clsx from "clsx";

interface NavItem {
  label: string;
  to: string;
}

interface NavModule {
  label: string;
  icon: LucideIcon;
  to?: string;       // when present, the module label is itself a link
  items?: NavItem[]; // when present, the module expands to reveal sub-items
}

/**
 * Module-grouped left navigation. Each module collapses/expands; the
 * active route's module is auto-expanded. Mirrors the SAP Fiori / Oracle
 * Redwood "module rail" pattern — operators in real ERPs think "I'm
 * working in Sales" not "I am persona X."
 */
const MODULES: NavModule[] = [
  { label: "Home", icon: Home, to: "/" },
  {
    label: "Master Data",
    icon: BookOpen,
    items: [
      { label: "Products", to: "/products" },
    ],
  },
  {
    label: "Sales",
    icon: ShoppingCart,
    items: [
      { label: "Customers", to: "/customers" },
      { label: "Sales Orders", to: "/sales-orders" },
      { label: "360 View", to: "/sales-orders/360" },
    ],
  },
  {
    label: "Purchasing",
    icon: Package,
    items: [
      { label: "Suppliers", to: "/suppliers" },
      { label: "Purchase Requisitions", to: "/purchase-requisitions" },
      { label: "Purchase Orders", to: "/purchase-orders" },
      { label: "Supplier Prices", to: "/supplier-prices" },
    ],
  },
  {
    label: "Inventory",
    icon: Warehouse,
    items: [
      { label: "Stock Balances", to: "/stock-items" },
      { label: "Reservations", to: "/stock-reservations" },
      { label: "Goods Receipts", to: "/goods-receipts" },
      { label: "Shipments", to: "/shipments" },
      { label: "Stock Movements", to: "/stock-movements" },
    ],
  },
  {
    label: "Manufacturing",
    icon: Factory,
    items: [
      { label: "Work Orders", to: "/work-orders" },
      { label: "Production Board", to: "/production-board" },
      { label: "BOMs", to: "/boms" },
    ],
  },
  {
    label: "Finance",
    icon: Receipt,
    items: [
      { label: "Supplier Invoices", to: "/supplier-invoices" },
      { label: "Pending Review", to: "/supplier-invoices/pending-review" },
      { label: "Customer Invoices", to: "/customer-invoices" },
      { label: "Payments", to: "/payments" },
      { label: "Journal Entries", to: "/journal-entries" },
      { label: "Exchange Rate", to: "/exchange-rate" },
      { label: "AR / AP Dashboard", to: "/ar-ap" },
    ],
  },
  {
    label: "Reporting",
    icon: BarChart3,
    items: [
      { label: "Available-to-Promise", to: "/atp" },
      { label: "Material Shortage", to: "/material-shortages" },
      { label: "PO Tracking", to: "/purchase-orders/tracking" },
      { label: "Financial Dashboard", to: "/financial-dashboard" },
    ],
  },
  {
    label: "System",
    icon: Settings,
    items: [
      { label: "Users", to: "/system/users" },
      { label: "Audit Log", to: "/system/audit-log" },
    ],
  },
];

export function Sidebar() {
  const [expanded, setExpanded] = useState<Record<string, boolean>>({
    Sales: true,
    Manufacturing: true,
    Finance: true,
  });

  function toggle(label: string) {
    setExpanded((e) => ({ ...e, [label]: !e[label] }));
  }

  return (
    <nav className="flex w-60 shrink-0 flex-col overflow-y-auto border-r border-border-default bg-bg-sidebar text-text-sidebar scrollbar-thin">
      <div className="flex flex-col gap-0.5 p-3">
        {MODULES.map((m) => (m.to ? renderLink(m) : renderGroup(m, expanded[m.label] ?? false, toggle)))}
      </div>
    </nav>
  );
}

function renderLink(m: NavModule) {
  const Icon = m.icon;
  return (
    <NavLink
      key={m.label}
      to={m.to!}
      end
      className={({ isActive }) =>
        clsx(
          "flex items-center gap-2.5 rounded-md px-3 py-2 text-sm",
          isActive
            ? "bg-bg-sidebar-active text-text-sidebar-active font-medium"
            : "hover:bg-bg-sidebar-hover hover:text-text-sidebar-active"
        )
      }
    >
      <Icon className="h-4 w-4 shrink-0" />
      <span>{m.label}</span>
    </NavLink>
  );
}

function renderGroup(m: NavModule, isExpanded: boolean, toggle: (label: string) => void) {
  const Icon = m.icon;
  const Caret = isExpanded ? ChevronDown : ChevronRight;
  return (
    <div key={m.label}>
      <button
        type="button"
        onClick={() => toggle(m.label)}
        className="flex w-full items-center gap-2.5 rounded-md px-3 py-2 text-sm hover:bg-bg-sidebar-hover hover:text-text-sidebar-active"
      >
        <Icon className="h-4 w-4 shrink-0" />
        <span className="flex-1 text-left">{m.label}</span>
        <Caret className="h-3.5 w-3.5 text-text-sidebar/60" />
      </button>
      {isExpanded && m.items && (
        <div className="ml-4 mt-0.5 flex flex-col gap-0.5 border-l border-bg-sidebar-hover pl-2">
          {m.items.map((it) => (
            <NavLink
              key={it.to}
              to={it.to}
              end
              className={({ isActive }) =>
                clsx(
                  "rounded-md px-3 py-1.5 text-[13px]",
                  isActive
                    ? "bg-bg-sidebar-active text-text-sidebar-active font-medium"
                    : "text-text-sidebar/85 hover:bg-bg-sidebar-hover hover:text-text-sidebar-active"
                )
              }
            >
              {it.label}
            </NavLink>
          ))}
        </div>
      )}
    </div>
  );
}
