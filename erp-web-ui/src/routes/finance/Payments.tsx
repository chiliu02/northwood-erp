import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Plus, Filter, Download } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { FilterPanel, useFieldFilters, type FilterField } from "@/components/ui/FilterPanel";
import { downloadCsv } from "@/lib/csv";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";

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

/**
 * Olivia's payments list — both AR (incoming) and AP (outgoing) in one
 * place, with a per-field filter to narrow. Click-through to detail
 * (when wired) will show allocation breakdown.
 */
export function Payments() {
  const navigate = useNavigate();

  const { data, isLoading, error } = useQuery({
    queryKey: ["payments"],
    queryFn: () => apiGet<PaymentRow[]>("/api/payments"),
  });

  const filterFields: FilterField<PaymentRow>[] = [
    { key: "number", label: "Payment #", get: (r) => r.paymentNumber },
    { key: "party", label: "Party", get: (r) => r.partyName },
    {
      key: "direction",
      label: "Direction",
      type: "select",
      get: (r) => r.paymentDirection,
      optionLabel: (v) => (v === "incoming" ? "AR (incoming)" : v === "outgoing" ? "AP (outgoing)" : v),
    },
    { key: "method", label: "Method", type: "select", get: (r) => r.paymentMethod },
    { key: "status", label: "Status", type: "select", get: (r) => r.status },
  ];
  const filter = useFieldFilters(data ?? [], filterFields);

  const columns: Column<PaymentRow>[] = [
    {
      key: "number",
      header: "Payment #",
      width: "150px",
      sortAccessor: (r) => r.paymentNumber,
      render: (r) => <span className="font-medium tabular-nums">{r.paymentNumber}</span>,
    },
    {
      key: "direction",
      header: "Direction",
      width: "120px",
      sortAccessor: (r) => r.paymentDirection,
      render: (r) => (
        <StatusPill
          label={r.paymentDirection === "incoming" ? "AR (in)" : "AP (out)"}
          tone={r.paymentDirection === "incoming" ? "success" : "info"}
        />
      ),
    },
    { key: "party", header: "Party", sortAccessor: (r) => r.partyName, render: (r) => r.partyName },
    {
      key: "date",
      header: "Date",
      width: "110px",
      sortAccessor: (r) => r.paymentDate,
      render: (r) => <span className="text-text-muted tabular-nums">{r.paymentDate}</span>,
    },
    {
      key: "method",
      header: "Method",
      width: "120px",
      sortAccessor: (r) => r.paymentMethod,
      render: (r) => <span className="text-text-muted">{r.paymentMethod}</span>,
    },
    {
      key: "amount",
      header: "Amount",
      numeric: true,
      width: "140px",
      sortAccessor: (r) => Number(r.amount),
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
      sortAccessor: (r) => r.status,
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
            <ActionButton
              icon={<Filter className="h-4 w-4" />}
              variant={filter.open ? "primary" : "secondary"}
              onClick={filter.toggle}
            >
              Filter
            </ActionButton>
            <ActionButton
              icon={<Download className="h-4 w-4" />}
              onClick={() => downloadCsv("payments.csv", filter.filtered)}
              disabled={filter.filtered.length === 0}
            >
              Export
            </ActionButton>
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

      <FilterPanel
        open={filter.open}
        rows={data ?? []}
        fields={filterFields}
        values={filter.values}
        onChange={filter.set}
        onClear={filter.clear}
        onClose={filter.close}
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load payments: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={filter.filtered}
            rowKey={(r) => r.id}
            loading={isLoading}
            emptyState={
              filter.active
                ? <span>No payments match the filter.</span>
                : <span>No payments yet. Posted via <code>POST /api/payments</code> + variants.</span>
            }
          />
        )}

        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {filter.filtered.length} payment{filter.filtered.length === 1 ? "" : "s"}
            {filter.active ? ` (filtered from ${data.length})` : ""}.
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
