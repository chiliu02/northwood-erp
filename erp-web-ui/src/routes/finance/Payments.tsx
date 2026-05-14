import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Plus, Filter, Download } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";
import { Select } from "@/components/ui/Form";

interface PaymentRow {
  id: string;
  paymentNumber: string;
  paymentDirection: string;       // 'incoming' (AR) | 'outgoing' (AP)
  paymentType: string;
  partyName: string;
  paymentDate: string;
  paymentMethod: string;
  currencyCode: string;
  amount: string;
  status: string;
}

const DIRECTION_FILTERS = ["all", "incoming", "outgoing"];

/**
 * Olivia's payments list — both AR (incoming) and AP (outgoing) in one
 * place, with a direction filter to narrow. Click-through to detail
 * (when wired) will show allocation breakdown.
 */
export function Payments() {
  const navigate = useNavigate();
  const [directionFilter, setDirectionFilter] = useState("all");

  const { data, isLoading, error } = useQuery({
    queryKey: ["payments"],
    queryFn: () => apiGet<PaymentRow[]>("/api/payments"),
  });

  const filtered = (data ?? []).filter((r) =>
    directionFilter === "all" ? true : r.paymentDirection === directionFilter
  );

  const columns: Column<PaymentRow>[] = [
    {
      key: "number",
      header: "Payment #",
      width: "150px",
      render: (r) => <span className="font-medium tabular-nums">{r.paymentNumber}</span>,
    },
    {
      key: "direction",
      header: "Direction",
      width: "120px",
      render: (r) => (
        <StatusPill
          label={r.paymentDirection === "incoming" ? "AR (in)" : "AP (out)"}
          tone={r.paymentDirection === "incoming" ? "success" : "info"}
        />
      ),
    },
    { key: "party", header: "Party", render: (r) => r.partyName },
    {
      key: "date",
      header: "Date",
      width: "110px",
      render: (r) => <span className="text-text-muted tabular-nums">{r.paymentDate}</span>,
    },
    {
      key: "method",
      header: "Method",
      width: "120px",
      render: (r) => <span className="text-text-muted">{r.paymentMethod}</span>,
    },
    {
      key: "amount",
      header: "Amount",
      numeric: true,
      width: "140px",
      render: (r) => (
        <span>
          <strong>{formatMoney(r.amount)}</strong> <span className="text-text-faint">{r.currencyCode}</span>
        </span>
      ),
    },
    {
      key: "status",
      header: "Status",
      width: "120px",
      render: (r) => {
        const s = statusForOrder(r.status);
        return <StatusPill label={s.label} tone={s.tone} />;
      },
    },
  ];

  return (
    <>
      <PageHeader
        title="Payments"
        description="AP (outgoing) and AR (incoming) payments. Each payment links to one or more invoices via allocations."
        trail={[
          { label: "Home", to: "/" },
          { label: "Finance" },
          { label: "Payments" },
        ]}
        actions={
          <>
            <ActionButton icon={<Filter className="h-4 w-4" />}>Filter</ActionButton>
            <ActionButton icon={<Download className="h-4 w-4" />}>Export</ActionButton>
            <ActionButton
              variant="primary"
              icon={<Plus className="h-4 w-4" />}
              requiresRole="accountant"
              onClick={() => navigate("/payments/new")}
            >
              Record payment
            </ActionButton>
          </>
        }
      />

      <div className="px-8 py-6">
        <div className="mb-3 flex items-center gap-3 text-xs">
          <span className="text-text-muted">Direction:</span>
          <Select
            value={directionFilter}
            onChange={(e) => setDirectionFilter(e.target.value)}
            className="!h-8 max-w-[160px]"
          >
            {DIRECTION_FILTERS.map((d) => (
              <option key={d} value={d}>
                {d === "all" ? "all" : d === "incoming" ? "AR (incoming)" : "AP (outgoing)"}
              </option>
            ))}
          </Select>
        </div>

        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load payments: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={filtered}
            rowKey={(r) => r.id}
            loading={isLoading}
            emptyState={
              directionFilter === "all"
                ? <span>No payments yet. Posted via <code>POST /api/payments</code> + variants.</span>
                : <span>No <code>{directionFilter}</code> payments.</span>
            }
          />
        )}

        {data && (
          <div className="mt-3 text-xs text-text-muted">
            Showing {filtered.length} of {data.length} payments.
          </div>
        )}
      </div>
    </>
  );
}

function formatMoney(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
