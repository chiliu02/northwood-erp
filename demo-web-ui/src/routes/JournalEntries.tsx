import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, RotateCcw } from "lucide-react";
import { Link } from "react-router-dom";
import { fetchJournalEntries, fetchJournalEntry } from "@/api/fetchers";
import type { JournalEntrySummary, JournalEntryView } from "@/api/types";
import { StatusBadge, inferStatusKind } from "@/components/ui/StatusBadge";
import { Select } from "@/components/ui/Form";
import { formatMoney, truncateUuid } from "@/lib/utils";
import { PERSONAS } from "@/personas";

const SOURCE_TYPES = [
  { value: "",                   label: "All source types" },
  { value: "supplier_invoice",   label: "Supplier invoices" },
  { value: "supplier_payment",   label: "Supplier payments" },
  { value: "customer_invoice",   label: "Customer invoices" },
  { value: "customer_payment",   label: "Customer payments" },
  { value: "goods_receipt",      label: "Goods receipts" },
  { value: "shipment_cost",      label: "Shipment cost (COGS)" },
  { value: "journal_reversal",   label: "Reversals" },
];

export function JournalEntries() {
  const persona = PERSONAS.daniel;
  const [filter, setFilter] = useState("");
  const { data, isLoading, error } = useQuery({
    queryKey: ["journal-entries", filter],
    queryFn: () => fetchJournalEntries(filter || undefined),
    refetchInterval: 5_000,
  });

  const list = data ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-baseline gap-3">
        <h1 className="text-[28px] font-semibold tracking-tight">Journal entries</h1>
        <span className="flex items-center gap-2 text-sm text-text-muted">
          <span className="h-1.5 w-1.5 rounded-full" style={{ background: persona.accentVar }} aria-hidden />
          {persona.name} · {persona.role}
        </span>
        <span className="ml-auto text-xs text-text-faint">
          {isLoading ? "loading…" : `${list.length} entries`}
        </span>
      </div>

      <p className="text-sm text-text-muted">
        Every operational side effect that hits the GL — receipt clears 1300 GRNI, supplier invoice
        approval moves 1300 to 2100, payment moves 2100 to 1000, shipment posts 5000 COGS, customer
        invoice posts 1100 AR, customer payment moves 1100 to 1000. Filter by source document type to
        trace one flow.
      </p>

      <div className="flex items-center gap-3">
        <Select value={filter} onChange={(e) => setFilter(e.target.value)} className="max-w-xs">
          {SOURCE_TYPES.map((s) => (
            <option key={s.value} value={s.value}>{s.label}</option>
          ))}
        </Select>
        <Link to="/journal-entries/reverse" className="text-sm text-text-muted hover:text-text-primary">
          <RotateCcw className="-ml-0.5 mr-1 inline h-3.5 w-3.5" />
          reverse a journal →
        </Link>
      </div>

      {error && (
        <div
          className="rounded-md border px-4 py-3 text-sm"
          style={{
            borderColor: "var(--color-state-error)",
            background: "color-mix(in srgb, var(--color-state-error) 12%, transparent)",
          }}
        >
          <p className="font-medium" style={{ color: "var(--color-state-error)" }}>
            Couldn't reach finance-service on :8086
          </p>
          <p className="mt-1 text-text-muted">{String(error)}</p>
        </div>
      )}

      <div className="overflow-hidden rounded-lg border border-border-subtle bg-bg-elevated">
        <table className="w-full text-sm">
          <thead className="border-b border-border-subtle bg-bg-base/50 text-left text-[11px] uppercase tracking-wider text-text-muted">
            <tr>
              <th className="w-8"></th>
              <th className="px-3 py-2 font-semibold">Journal #</th>
              <th className="px-3 py-2 font-semibold">Posting date</th>
              <th className="px-3 py-2 font-semibold">Source</th>
              <th className="px-3 py-2 font-semibold">Description</th>
              <th className="px-3 py-2 text-right font-semibold">Total</th>
              <th className="px-3 py-2 font-semibold">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border-subtle">
            {list.map((j) => (
              <JournalRow key={j.journalEntryHeaderId} summary={j} />
            ))}
          </tbody>
        </table>
        {!isLoading && list.length === 0 && !error && (
          <p className="px-4 py-6 text-center text-sm text-text-faint">
            No journals match. Drive a flow (post a receipt, ship an order, take a payment) to see them appear.
          </p>
        )}
      </div>
    </div>
  );
}

function JournalRow({ summary }: { summary: JournalEntrySummary }) {
  const [open, setOpen] = useState(false);
  const { data: detail } = useQuery({
    queryKey: ["journal-entry", summary.journalEntryHeaderId],
    queryFn: () => fetchJournalEntry(summary.journalEntryHeaderId),
    enabled: open,
  });

  return (
    <>
      <tr className="hover:bg-bg-hover">
        <td className="px-2 py-2 align-top">
          <button
            type="button"
            onClick={() => setOpen((o) => !o)}
            className="text-text-muted hover:text-text-primary"
            aria-label={open ? "Collapse" : "Expand"}
          >
            {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          </button>
        </td>
        <td className="px-3 py-2 font-mono">{summary.journalNumber}</td>
        <td className="px-3 py-2 text-text-muted">{summary.postingDate}</td>
        <td className="px-3 py-2 font-mono text-xs text-text-faint">{summary.sourceDocumentType}</td>
        <td className="px-3 py-2">{summary.description}</td>
        <td className="px-3 py-2 text-right tabular-nums">
          {formatMoney(summary.totalAmount, summary.currencyCode)}
        </td>
        <td className="px-3 py-2">
          <StatusBadge kind={inferStatusKind(summary.status)}>{summary.status}</StatusBadge>
        </td>
      </tr>
      {open && (
        <tr>
          <td colSpan={7} className="bg-bg-base/40 px-8 py-3">
            <DetailLines detail={detail} />
          </td>
        </tr>
      )}
    </>
  );
}

function DetailLines({ detail }: { detail: JournalEntryView | undefined }) {
  if (!detail) return <p className="text-xs text-text-faint">loading…</p>;
  return (
    <>
      <table className="w-full text-xs">
        <thead className="border-b border-border-subtle text-left text-[10px] uppercase tracking-wider text-text-muted">
          <tr>
            <th className="px-2 py-1.5 font-semibold">#</th>
            <th className="px-2 py-1.5 font-semibold">Account</th>
            <th className="px-2 py-1.5 font-semibold">Description</th>
            <th className="px-2 py-1.5 text-right font-semibold">Debit</th>
            <th className="px-2 py-1.5 text-right font-semibold">Credit</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border-subtle">
          {detail.lines.map((l) => (
            <tr key={l.lineId}>
              <td className="px-2 py-1 text-text-faint">{l.lineNumber}</td>
              <td className="px-2 py-1">
                <span className="font-mono text-text-primary">{l.accountCode}</span>{" "}
                <span className="text-text-muted">· {l.accountName}</span>
              </td>
              <td className="px-2 py-1 text-text-muted">{l.description}</td>
              <td className="px-2 py-1 text-right tabular-nums">
                {Number(l.debitAmount) > 0 ? formatMoney(l.debitAmount, detail.currencyCode) : ""}
              </td>
              <td className="px-2 py-1 text-right tabular-nums">
                {Number(l.creditAmount) > 0 ? formatMoney(l.creditAmount, detail.currencyCode) : ""}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="mt-2 text-[11px] text-text-faint">
        source: <span className="font-mono">{detail.sourceModule}</span>/
        <span className="font-mono">{detail.sourceDocumentType}</span> ·
        id <span className="font-mono">{truncateUuid(detail.sourceDocumentId)}</span> ·
        journal id <span className="font-mono">{truncateUuid(detail.journalEntryHeaderId)}</span>
      </p>
    </>
  );
}
