import { NavLink } from "react-router-dom";
import { LayoutDashboard, BarChart3, FileClock } from "lucide-react";
import { PERSONAS, PERSONA_ORDER, type PersonaKey } from "@/personas";
import { cn } from "@/lib/utils";

interface NavItem {
  to: string;
  label: string;
}

const PERSONA_NAV: Record<PersonaKey, NavItem[]> = {
  emma: [
    // Pricing + Reorder are inline modals on /products (pencil icon on each
    // row); a dedicated page would duplicate the modal UI without adding
    // anything. /boms is a read-only tree viewer — authoring (create draft /
    // add lines / activate) is still REST-only per dev-todo §3.4.
    { to: "/products",         label: "Products" },
    { to: "/boms",             label: "BOMs" },
  ],
  sarah: [
    { to: "/sales-orders",     label: "Sales orders" },
    { to: "/sales-orders/new", label: "Place new order" },
  ],
  mike: [
    { to: "/stock-items",     label: "Stock items" },
    { to: "/goods-receipts",  label: "Goods receipts" },
    { to: "/shipments",       label: "Shipments" },
    { to: "/atp",             label: "Available-to-promise" },
  ],
  linda: [
    { to: "/production-board",  label: "Production board" },
    { to: "/work-orders",       label: "Work orders" },
    { to: "/material-shortages", label: "Material shortages" },
  ],
  tom: [
    { to: "/purchase-requisitions",   label: "Purchase requisitions" },
    { to: "/purchase-orders",         label: "Purchase orders" },
    { to: "/purchase-orders/tracking", label: "PO tracking" },
    { to: "/supplier-prices",         label: "Supplier prices" },
  ],
  olivia: [
    { to: "/customer-invoices",                 label: "Customer invoices" },
    { to: "/supplier-invoices",                 label: "Supplier invoices" },
    { to: "/supplier-invoices/pending-review",  label: "Pending 3-way review" },
    { to: "/payments",                          label: "Payments" },
  ],
  daniel: [
    { to: "/journal-entries",         label: "Journal entries" },
    { to: "/journal-entries/reverse", label: "Reverse a journal" },
  ],
};

interface SidebarProps {
  activePersona: PersonaKey;
}

export function Sidebar({ activePersona }: SidebarProps) {
  return (
    <nav className="flex w-60 shrink-0 flex-col overflow-y-auto border-r border-border-subtle bg-bg-elevated py-4 scrollbar-thin">
      <SidebarLink to="/" icon={<LayoutDashboard className="h-4 w-4" />} label="Dashboard" />

      {PERSONA_ORDER.map((key) => {
        const persona = PERSONAS[key];
        const items = PERSONA_NAV[key];
        const isActive = key === activePersona;
        return (
          <div key={key} className="mt-5">
            <div className="flex items-center gap-2 px-4 pb-1.5 text-[11px] font-semibold uppercase tracking-wider text-text-faint">
              <span
                className="h-1.5 w-1.5 rounded-full"
                style={{ background: persona.accentVar }}
                aria-hidden
              />
              <span className={cn(isActive && "text-text-muted")}>{persona.name}</span>
              <span>· {persona.role}</span>
            </div>
            {items.map((it) => (
              <SidebarLink key={it.to} to={it.to} label={it.label} indent />
            ))}
          </div>
        );
      })}

      <div className="mt-auto border-t border-border-subtle pt-4">
        <SidebarLink
          to="/saga-console"
          icon={<BarChart3 className="h-4 w-4" />}
          label="Saga console"
          subtle
        />
        <SidebarLink
          to="/event-log"
          icon={<FileClock className="h-4 w-4" />}
          label="Event log"
          subtle
        />
      </div>
    </nav>
  );
}

interface SidebarLinkProps {
  to: string;
  label: string;
  icon?: React.ReactNode;
  indent?: boolean;
  subtle?: boolean;
}

function SidebarLink({ to, label, icon, indent, subtle }: SidebarLinkProps) {
  return (
    <NavLink
      to={to}
      end={to === "/"}
      className={({ isActive }) =>
        cn(
          "flex items-center gap-2 px-4 py-1.5 text-sm transition-colors",
          indent && !icon && "pl-7",
          subtle && "text-text-muted",
          isActive
            ? "bg-bg-hover font-medium text-text-primary"
            : "text-text-muted hover:bg-bg-hover hover:text-text-primary"
        )
      }
    >
      {icon}
      <span>{label}</span>
    </NavLink>
  );
}
