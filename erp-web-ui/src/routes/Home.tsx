import { Link } from "react-router-dom";
import { ArrowRight, Receipt, ShoppingCart, Package, Factory } from "lucide-react";
import { Breadcrumb } from "@/components/layout/Breadcrumb";

/**
 * Landing page. Operators arrive here and pick a module. Real ERPs
 * usually show a personalised dashboard (open tasks, KPIs); for the
 * showcase we keep it as a clean module launcher pointing at the most
 * common entry points.
 */
export function Home() {
  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-border-default bg-bg-surface px-8 py-5">
        <Breadcrumb trail={[{ label: "Home" }]} />
        <h1 className="mt-2 text-xl font-semibold text-text-primary">Welcome to Northwood ERP</h1>
        <p className="mt-1 text-sm text-text-muted">
          Pick a module from the sidebar, or jump to a common task below.
        </p>
      </div>

      <div className="flex-1 px-8 py-6">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          <QuickAction
            to="/sales-orders"
            icon={ShoppingCart}
            module="Sales"
            title="Sales Orders"
            description="View, place, and manage customer orders."
          />
          <QuickAction
            to="/work-orders"
            icon={Factory}
            module="Manufacturing"
            title="Work Orders"
            description="Track production and complete operations."
          />
          <QuickAction
            to="/purchase-orders"
            icon={Package}
            module="Purchasing"
            title="Purchase Orders"
            description="Approve and track purchase orders."
          />
          <QuickAction
            to="/supplier-invoices/pending-review"
            icon={Receipt}
            module="Finance"
            title="Pending Review"
            description="Resolve 3-way-match-failed invoices."
          />
        </div>
      </div>
    </div>
  );
}

interface QuickActionProps {
  to: string;
  icon: React.ElementType;
  module: string;
  title: string;
  description: string;
}

function QuickAction({ to, icon: Icon, module, title, description }: QuickActionProps) {
  return (
    <Link
      to={to}
      className="group flex flex-col rounded-md border border-border-default bg-bg-surface p-4 transition hover:border-border-strong"
    >
      <div className="mb-3 flex items-center justify-between">
        <div className="flex h-9 w-9 items-center justify-center rounded-md bg-brand-primary-soft text-brand-primary">
          <Icon className="h-5 w-5" />
        </div>
        <ArrowRight className="h-4 w-4 text-text-faint group-hover:text-text-secondary" />
      </div>
      <div className="text-[11px] font-medium uppercase tracking-wide text-text-muted">{module}</div>
      <div className="mt-0.5 text-sm font-semibold text-text-primary">{title}</div>
      <p className="mt-1 text-xs text-text-muted">{description}</p>
    </Link>
  );
}
