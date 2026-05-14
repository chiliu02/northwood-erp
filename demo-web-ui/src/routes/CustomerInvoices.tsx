import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { fetchCustomerInvoices } from "@/api/fetchers";
import type { CustomerInvoiceView } from "@/api/types";
import { MasterDetail } from "@/components/MasterDetail";
import { StatusBadge, inferStatusKind } from "@/components/ui/StatusBadge";
import { formatMoney, truncateUuid } from "@/lib/utils";

export function CustomerInvoices() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["customer-invoices"],
    queryFn: fetchCustomerInvoices,
    refetchInterval: 5_000,
  });

  return (
    <MasterDetail
      title="Customer invoices"
      persona="olivia"
      items={data}
      isLoading={isLoading}
      error={error}
      errorContext="finance-service on :8086"
      rowKey={(i) => i.id}
      emptyMessage="No customer invoices yet — ship a sales order to auto-create one."
      renderRow={(i) => (
        <div className="space-y-1">
          <div className="flex items-center justify-between">
            <span className="font-mono text-text-primary">{i.invoiceNumber}</span>
            <StatusBadge kind={inferStatusKind(i.status)}>{i.status}</StatusBadge>
          </div>
          <div className="flex items-center justify-between text-xs text-text-muted">
            <span>{i.customerName}</span>
            <span className="tabular-nums">{formatMoney(i.totalAmount, i.currencyCode)}</span>
          </div>
        </div>
      )}
      renderDetail={(i) => <Detail invoice={i} />}
    />
  );
}

function Detail({ invoice }: { invoice: CustomerInvoiceView }) {
  return (
    <div className="space-y-5">
      <header className="space-y-1">
        <div className="flex items-center gap-3">
          <h2 className="font-mono text-xl font-semibold">{invoice.invoiceNumber}</h2>
          <StatusBadge kind={inferStatusKind(invoice.status)}>{invoice.status}</StatusBadge>
        </div>
        <p className="text-sm text-text-muted">
          {invoice.customerName} · sales order{" "}
          <Link
            to={`/sales-orders/${invoice.salesOrderHeaderId}`}
            className="font-mono text-text-primary hover:text-state-active hover:underline"
          >
            {truncateUuid(invoice.salesOrderHeaderId)}
          </Link>
        </p>
      </header>

      <section className="grid grid-cols-3 gap-3">
        <Stat label="Subtotal" value={formatMoney(invoice.subtotalAmount, invoice.currencyCode)} />
        <Stat label="Tax"      value={formatMoney(invoice.taxAmount,      invoice.currencyCode)} />
        <Stat label="Total"    value={formatMoney(invoice.totalAmount,    invoice.currencyCode)} emphasis />
      </section>

      <section>
        <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">Lines</h3>
        <table className="w-full text-sm">
          <thead className="border-b border-border-subtle text-left text-[11px] uppercase tracking-wider text-text-muted">
            <tr>
              <th className="px-3 py-2 font-semibold">#</th>
              <th className="px-3 py-2 font-semibold">Product</th>
              <th className="px-3 py-2 text-right font-semibold">Qty</th>
              <th className="px-3 py-2 text-right font-semibold">Unit price</th>
              <th className="px-3 py-2 text-right font-semibold">Tax</th>
              <th className="px-3 py-2 text-right font-semibold">Line total</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border-subtle">
            {invoice.lines.map((l) => (
              <tr key={l.lineId}>
                <td className="px-3 py-2 text-text-faint">{l.lineNumber}</td>
                <td className="px-3 py-2">
                  <span className="font-mono text-xs text-text-faint">{l.productSku}</span>
                  <span className="ml-2">{l.productName}</span>
                </td>
                <td className="px-3 py-2 text-right tabular-nums">{l.quantity}</td>
                <td className="px-3 py-2 text-right tabular-nums">{formatMoney(l.unitPrice, invoice.currencyCode)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{formatMoney(l.taxAmount, invoice.currencyCode)}</td>
                <td className="px-3 py-2 text-right font-medium tabular-nums">{formatMoney(l.lineTotal, invoice.currencyCode)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="space-y-1 text-xs text-text-faint">
        <p>id: <span className="font-mono">{truncateUuid(invoice.id)}</span></p>
      </section>
    </div>
  );
}

function Stat({ label, value, emphasis }: { label: string; value: string; emphasis?: boolean }) {
  return (
    <div className="rounded-md border border-border-subtle p-3">
      <p className="text-xs uppercase tracking-wider text-text-muted">{label}</p>
      <p className={emphasis ? "mt-1 text-lg font-semibold tabular-nums" : "mt-1 text-sm tabular-nums"}>{value}</p>
    </div>
  );
}
