import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { ArrowRight } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";

interface FinancialDashboard {
  dashboardDate: string;
  currencyCode: string;
  salesRevenue: string;
  costOfGoodsSold: string;
  grossProfit: string;
  inventoryValue: string;
  wipValue: string;
  accountsReceivable: string;
  accountsPayable: string;
  cashReceived: string;
  cashPaid: string;
  openSalesOrdersCount: number;
  openPurchaseOrdersCount: number;
  openWorkOrdersCount: number;
  updatedAt: string;
}

/**
 * Focused AR/AP dashboard. Reads the same financial-dashboard projection
 * the main reporting view uses, but emphasises the AR/AP and cash-flow
 * columns and links into the customer-invoices / supplier-invoices
 * pending-review lists for action.
 *
 * <p>Today's AR/AP figures on the dashboard are 0 (the projection columns
 * are deferred). This screen still serves as the
 * Olivia/Daniel landing page once the projection writes land; for now it
 * surfaces the placeholder zeros transparently.
 */
export function ArAp() {
  const navigate = useNavigate();
  const { data, isLoading, error } = useQuery({
    queryKey: ["financial-dashboard"],
    queryFn: () => apiGet<FinancialDashboard[]>("/api/financial-dashboard"),
  });

  const latest = data?.[0];

  return (
    <>
      <PageHeader
        title="AR / AP Dashboard"
        description="Today's accounts-receivable and accounts-payable position. Click through for the open-invoice lists."
        trail={[
          { label: "Home", to: "/" },
          { label: "Finance" },
          { label: "AR / AP Dashboard" },
        ]}
      />

      <div className="px-8 py-6 space-y-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load dashboard: {(error as Error).message}
          </div>
        ) : isLoading ? (
          <div className="text-sm text-text-muted">Loading…</div>
        ) : !latest ? (
          <div className="text-sm text-text-muted">No dashboard data for AUD yet.</div>
        ) : (
          <>
            <div className="text-xs text-text-muted">
              As of {latest.dashboardDate} · {latest.currencyCode}
            </div>

            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 max-w-4xl">
              <Tile
                label="Accounts Receivable"
                value={formatMoney(latest.accountsReceivable)}
                currency={latest.currencyCode}
                hint={
                  <>
                    Open customer invoices.{" "}
                    <button
                      onClick={() => navigate("/customer-invoices")}
                      className="text-text-link inline-flex items-center gap-1 hover:underline"
                    >
                      View list <ArrowRight className="h-3 w-3" />
                    </button>
                  </>
                }
              />
              <Tile
                label="Accounts Payable"
                value={formatMoney(latest.accountsPayable)}
                currency={latest.currencyCode}
                hint={
                  <>
                    Pending review supplier invoices.{" "}
                    <button
                      onClick={() => navigate("/supplier-invoices/pending-review")}
                      className="text-text-link inline-flex items-center gap-1 hover:underline"
                    >
                      Review queue <ArrowRight className="h-3 w-3" />
                    </button>
                  </>
                }
              />
              <Tile
                label="Cash Received (today)"
                value={formatMoney(latest.cashReceived)}
                currency={latest.currencyCode}
                hint="Sum of customer payments posted on this date."
              />
              <Tile
                label="Cash Paid (today)"
                value={formatMoney(latest.cashPaid)}
                currency={latest.currencyCode}
                hint="Sum of supplier payments posted on this date."
              />
            </div>

            <div className="rounded-md border border-border-default bg-bg-surface p-4">
              <div className="text-sm font-medium mb-2">Quick actions</div>
              <div className="flex flex-wrap gap-2">
                <ActionButton onClick={() => navigate("/customer-invoices")}>
                  All customer invoices
                </ActionButton>
                <ActionButton onClick={() => navigate("/supplier-invoices/pending-review")}>
                  Supplier-invoice queue
                </ActionButton>
                <ActionButton onClick={() => navigate("/payments")}>
                  Recent payments
                </ActionButton>
                <ActionButton onClick={() => navigate("/journal-entries")}>
                  Journal entries
                </ActionButton>
              </div>
            </div>
          </>
        )}
      </div>
    </>
  );
}

function Tile({
  label,
  value,
  currency,
  hint,
}: {
  label: string;
  value: string;
  currency: string;
  hint: React.ReactNode;
}) {
  return (
    <div className="rounded-md border border-border-default bg-bg-surface p-4">
      <div className="text-xs uppercase tracking-wide text-text-secondary">{label}</div>
      <div className="mt-1 text-2xl font-semibold tabular-nums">
        {value} <span className="text-base font-normal text-text-muted">{currency}</span>
      </div>
      <div className="mt-2 text-xs text-text-muted">{hint}</div>
    </div>
  );
}

function formatMoney(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
