import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { apiGet, ApiError } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { FormSection, ReadOnlyField } from "@/components/ui/FormSection";
import { TextInput } from "@/components/ui/Form";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";

interface PurchaseOrderTracking {
  purchaseOrderHeaderId: string;
  purchaseOrderNumber: string;
  supplierId: string;
  supplierName: string | null;
  poStatus: string;
  orderDate: string | null;
  expectedReceiptDate: string | null;
  currencyCode: string;
  orderedAmount: string;
  receivedAmount: string;
  invoicedAmount: string;
  paidAmount: string;
  outstandingAmount: string;
  receiptStatus: string | null;
  invoiceStatus: string | null;
  paymentStatus: string | null;
  matchStatus: string | null;
  lastGoodsReceiptHeaderId: string | null;
  lastSupplierInvoiceHeaderId: string | null;
  lastPaymentId: string | null;
  updatedAt: string | null;
}

/** Reporting view of a single PO's full P2P trajectory. */
export function PurchaseOrderTracking() {
  const [pendingId, setPendingId] = useState("");
  const [poId, setPoId] = useState<string | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ["po-tracking", poId],
    queryFn: () => apiGet<PurchaseOrderTracking>(`/api/purchase-orders/${poId}/tracking`),
    enabled: !!poId,
  });

  function search() {
    const trimmed = pendingId.trim();
    setPoId(trimmed || null);
  }

  const poStatus = data ? statusForOrder(data.poStatus) : null;

  return (
    <>
      <PageHeader
        title="PO Tracking"
        description="End-to-end P2P trajectory of a purchase order: ordered → received → invoiced → paid, sourced from the reporting projection."
        trail={[
          { label: "Reporting" },
          { label: "PO Tracking" },
        ]}
      />

      <div className="space-y-6 px-8 py-6">
        <section>
          <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-text-primary">
            <Search className="h-4 w-4 text-text-muted" />
            Find a purchase order by id
          </h2>
          <FormSection columns={1}>
            <div className="flex gap-2">
              <TextInput
                placeholder="Purchase order header id (UUID)"
                value={pendingId}
                onChange={(e) => setPendingId(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") search(); }}
              />
              <ActionButton variant="primary" onClick={search}>Search</ActionButton>
            </div>
          </FormSection>
        </section>

        {!poId ? (
          <div className="rounded-md border border-dashed border-border-default px-4 py-8 text-center text-sm text-text-muted">
            Paste a purchase order header id and press <kbd className="rounded bg-bg-subtle px-1.5 py-0.5 text-xs">Enter</kbd> or click <strong>Search</strong> to see its tracking dashboard.
          </div>
        ) : isLoading ? (
          <div className="rounded-md border border-border-default bg-bg-surface px-4 py-6 text-center text-sm text-text-muted">
            Loading tracking record…
          </div>
        ) : error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            {error instanceof ApiError && error.status === 404
              ? "No tracking record found for that id."
              : `Failed to load: ${(error as Error).message}`}
          </div>
        ) : data ? (
          <>
            <FormSection title={data.purchaseOrderNumber} description={data.supplierName ?? "—"}>
              <ReadOnlyField label="PO status" value={poStatus && <StatusPill label={poStatus.label} tone={poStatus.tone} />} />
              <ReadOnlyField label="Order date" value={data.orderDate ?? "—"} />
              <ReadOnlyField label="Expected receipt" value={data.expectedReceiptDate ?? "—"} />
              <ReadOnlyField label="Currency" value={data.currencyCode} />
              <ReadOnlyField label="Updated" value={data.updatedAt ?? "—"} />
              <ReadOnlyField label="Supplier id" value={<code className="text-xs text-text-muted">{shortUuid(data.supplierId)}</code>} />
            </FormSection>

            <section>
              <h2 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">Amounts</h2>
              <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
                <Tile label="Ordered" value={formatMoney(data.orderedAmount, data.currencyCode)} />
                <Tile label="Received" value={formatMoney(data.receivedAmount, data.currencyCode)} />
                <Tile label="Invoiced" value={formatMoney(data.invoicedAmount, data.currencyCode)} />
                <Tile label="Paid" value={formatMoney(data.paidAmount, data.currencyCode)} />
                <Tile label="Outstanding" value={formatMoney(data.outstandingAmount, data.currencyCode)} emphasize />
              </div>
            </section>

            <section>
              <h2 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">Sub-statuses</h2>
              <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
                <StatusTile label="Receipts" status={data.receiptStatus} />
                <StatusTile label="Invoices" status={data.invoiceStatus} />
                <StatusTile label="Payments" status={data.paymentStatus} />
                <StatusTile label="3-way match" status={data.matchStatus} />
              </div>
            </section>

            <section>
              <h2 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">Last document ids</h2>
              <div className="grid grid-cols-1 gap-2 lg:grid-cols-3">
                <DocRef label="Last goods receipt" id={data.lastGoodsReceiptHeaderId} />
                <DocRef label="Last supplier invoice" id={data.lastSupplierInvoiceHeaderId} />
                <DocRef label="Last payment" id={data.lastPaymentId} />
              </div>
            </section>
          </>
        ) : null}
      </div>
    </>
  );
}

function Tile({ label, value, emphasize }: { label: string; value: string; emphasize?: boolean }) {
  return (
    <div
      className={
        emphasize
          ? "rounded-md border border-brand-primary/30 bg-brand-primary-soft px-4 py-3"
          : "rounded-md border border-border-default bg-bg-surface px-4 py-3"
      }
    >
      <div className="text-[11px] font-medium uppercase tracking-wider text-text-muted">{label}</div>
      <div className="mt-1 text-lg font-semibold tabular-nums text-text-primary">{value}</div>
    </div>
  );
}

function StatusTile({ label, status }: { label: string; status: string | null }) {
  const pill = statusForOrder(status);
  return (
    <div className="rounded-md border border-border-default bg-bg-surface px-4 py-3">
      <div className="text-[11px] font-medium uppercase tracking-wider text-text-muted">{label}</div>
      <div className="mt-1.5">
        <StatusPill label={pill.label} tone={pill.tone} />
      </div>
    </div>
  );
}

function DocRef({ label, id }: { label: string; id: string | null }) {
  return (
    <div className="rounded-md border border-border-default bg-bg-surface px-4 py-2">
      <div className="text-[11px] font-medium uppercase tracking-wider text-text-muted">{label}</div>
      <div className="mt-1 font-mono text-xs text-text-secondary">
        {id ? shortUuid(id) : "—"}
      </div>
    </div>
  );
}

function formatMoney(v: string | null | undefined, currency: string): string {
  if (v == null) return "—";
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return `${currency} ${n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function shortUuid(uuid: string): string {
  return uuid ? `${uuid.slice(0, 8)}…${uuid.slice(-4)}` : "—";
}
