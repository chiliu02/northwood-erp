import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Undo2, Layers, X } from "lucide-react";
import { apiGet, apiPost, ApiError } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { FormSection, Field, ReadOnlyField } from "@/components/ui/FormSection";
import { TextInput, TextArea, DateInput, Select } from "@/components/ui/Form";
import { StatusPill, statusForOrder } from "@/components/ui/StatusPill";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";

/** Lightweight list row from {@code GET /api/journal-entries}. */
interface JournalEntrySummary {
  journalEntryHeaderId: string;
  journalNumber: string;
  postingDate: string;
  sourceModule: string;
  sourceDocumentType: string;
  sourceDocumentId: string;
  description: string;
  status: string;
  currencyCode: string;
  totalAmount: string;
  lineCount: number;
  postedAt: string | null;
}

interface JournalEntryLine {
  lineId: string;
  lineNumber: number;
  glAccountId: string;
  accountCode: string;
  accountName: string;
  debitAmount: string;
  creditAmount: string;
  description: string;
  postingDate: string;
}

interface JournalEntry {
  journalEntryHeaderId: string;
  journalNumber: string;
  postingDate: string;
  sourceModule: string;
  sourceDocumentType: string;
  sourceDocumentId: string;
  description: string;
  status: string;
  currencyCode: string;
  exchangeRate: string;
  lines: JournalEntryLine[];
  version: number;
}

interface ReverseBySourceResponse {
  reversedCount: number;
  reversalEntryIds: string[];
}

const SOURCE_DOC_TYPES = [
  "goods_receipt",
  "supplier_invoice",
  "supplier_payment",
  "shipment",
  "customer_invoice",
  "customer_payment",
  "customer_refund", // Refund on cancelled prepayment/deposit (Dr 2110 / Cr Bank)
];

/**
 * Journal Entries page — the GL audit trail:
 *
 *   1. All entries: a newest-first list of every posted journal
 *      ({@code GET /api/journal-entries}); click a row to see its header +
 *      balanced debit/credit lines + a Reverse button.
 *   2. Reverse-by-source: pick a source document type + id, post a bulk
 *      reversal of every posted journal that originated from that source.
 */
export function JournalEntries() {
  return (
    <>
      <PageHeader
        title="Journal Entries"
        description="GL audit trail. Browse every posted entry, drill in for balanced lines, or bulk-reverse every entry sourced from a specific document."
        trail={[
          { label: "Home", to: "/" },
          { label: "Finance" },
          { label: "Journal Entries" },
        ]}
      />
      <div className="space-y-8 px-8 py-6">
        <AllEntriesSection />
        <div className="border-t border-border-default" />
        <ReverseBySourceSection />
      </div>
    </>
  );
}

// ----- All entries -----

function AllEntriesSection() {
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const { data: rows, isLoading, error } = useQuery({
    queryKey: ["journal-entries"],
    queryFn: () => apiGet<JournalEntrySummary[]>("/api/journal-entries"),
  });

  const columns: Column<JournalEntrySummary>[] = [
    {
      key: "number",
      header: "Journal #",
      width: "150px",
      sortAccessor: (r) => r.journalNumber,
      render: (r) => <span className="font-medium tabular-nums">{r.journalNumber}</span>,
    },
    {
      key: "date",
      header: "Date",
      width: "110px",
      sortAccessor: (r) => r.postingDate,
      render: (r) => <span className="text-text-muted tabular-nums">{r.postingDate}</span>,
    },
    {
      key: "source",
      header: "Source",
      width: "200px",
      sortAccessor: (r) => r.sourceDocumentType,
      render: (r) => (
        <span className="text-text-muted">
          {r.sourceModule} • {r.sourceDocumentType}
        </span>
      ),
    },
    { key: "description", header: "Description", sortAccessor: (r) => r.description, render: (r) => r.description },
    {
      key: "total",
      header: "Amount",
      numeric: true,
      width: "130px",
      sortAccessor: (r) => Number(r.totalAmount),
      render: (r) => (
        <span>
          {formatMoney(r.totalAmount)} <span className="text-text-faint">{r.currencyCode}</span>
        </span>
      ),
    },
    {
      key: "lines",
      header: "Lines",
      numeric: true,
      width: "70px",
      sortAccessor: (r) => r.lineCount,
      render: (r) => <span className="tabular-nums text-text-muted">{r.lineCount}</span>,
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
    <section>
      <h2 className="mb-3 text-sm font-semibold text-text-primary">All entries</h2>

      {error ? (
        <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
          Failed to load journal entries: {(error as Error).message}
        </div>
      ) : (
        <DataGrid
          columns={columns}
          rows={rows ?? []}
          rowKey={(r) => r.journalEntryHeaderId}
          onRowClick={(r) => setSelectedId(r.journalEntryHeaderId)}
          loading={isLoading}
          emptyState="No journal entries yet. They post as side effects of AP/AR flows (invoices, payments, shipments, adjustments)."
        />
      )}
      {rows && (
        <div className="mt-3 text-xs text-text-muted">
          {rows.length} {rows.length === 1 ? "entry" : "entries"} (newest first).
        </div>
      )}

      {selectedId && (
        <EntryDetail
          id={selectedId}
          onClose={() => setSelectedId(null)}
          onReversed={() => {
            queryClient.invalidateQueries({ queryKey: ["journal-entries"] });
            queryClient.invalidateQueries({ queryKey: ["journal-entry", selectedId] });
          }}
        />
      )}
    </section>
  );
}

// ----- Selected entry detail + reverse -----

function EntryDetail({ id, onClose, onReversed }: { id: string; onClose: () => void; onReversed: () => void }) {
  const [reverseDialog, setReverseDialog] = useState(false);
  const [reverseReason, setReverseReason] = useState("");
  const [reverseDate, setReverseDate] = useState(new Date().toISOString().slice(0, 10));
  const [reverseError, setReverseError] = useState<string | null>(null);

  const { data, isLoading, error: fetchError, refetch } = useQuery({
    queryKey: ["journal-entry", id],
    queryFn: () => apiGet<JournalEntry>(`/api/journal-entries/${id}`),
    enabled: !!id,
  });

  const reverseMutation = useMutation({
    mutationFn: async () => {
      await apiPost(`/api/journal-entries/${id}/reverse`, {
        reason: reverseReason.trim(),
        postingDate: reverseDate,
      });
    },
    onSuccess: () => {
      refetch();
      onReversed();
      setReverseDialog(false);
      setReverseReason("");
    },
    onError: (err) => setReverseError(err instanceof ApiError ? err.message : "Reverse failed."),
  });

  const status = data ? statusForOrder(data.status) : null;
  const isReversible = data?.status === "posted";

  return (
    <div className="mt-6 rounded-md border border-border-default bg-bg-surface p-4">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-text-primary">
          {data ? data.journalNumber : "Entry"} detail
        </h3>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close detail"
          className="text-text-muted hover:text-text-primary"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      {isLoading ? (
        <div className="px-4 py-6 text-center text-sm text-text-muted">Loading entry…</div>
      ) : fetchError ? (
        <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
          {fetchError instanceof ApiError && fetchError.status === 404
            ? "No entry found with that id."
            : `Failed to load: ${(fetchError as Error).message}`}
        </div>
      ) : data ? (
        <div className="space-y-4">
          <FormSection title={data.journalNumber} description={data.description}>
            <ReadOnlyField label="Posting date" value={data.postingDate} />
            <ReadOnlyField label="Status" value={status && <StatusPill label={status.label} tone={status.tone} />} />
            <ReadOnlyField label="Source" value={`${data.sourceModule} • ${data.sourceDocumentType}`} />
            <ReadOnlyField label="Source doc id" value={<code className="text-xs text-text-muted">{shortUuid(data.sourceDocumentId)}</code>} />
            <ReadOnlyField label="Currency" value={data.currencyCode} />
            <ReadOnlyField label="Rate" value={<span className="tabular-nums">{data.exchangeRate}</span>} />
          </FormSection>

          <DataGrid columns={lineColumns} rows={data.lines} rowKey={(l) => l.lineId} />

          <div className="flex justify-end">
            {isReversible ? (
              <ActionButton
                variant="danger"
                icon={<Undo2 className="h-4 w-4" />}
                onClick={() => {
                  setReverseDialog(true);
                  setReverseError(null);
                }}
                requiresRole="finance_manager"
              >
                Reverse this entry
              </ActionButton>
            ) : (
              <span className="text-xs text-text-muted">
                Status is <strong>{data.status}</strong> — not reversible.
              </span>
            )}
          </div>
        </div>
      ) : null}

      <ConfirmDialog
        open={reverseDialog}
        title="Reverse journal entry?"
        message={data && (
          <>
            Posts a debit/credit-flipped reversal of <strong>{data.journalNumber}</strong> in
            the same transaction that flips the original from <code>posted</code> →{" "}
            <code>reversed</code>. Reversal is linked back via{" "}
            <code>source_document_type='journal_reversal'</code>.
          </>
        )}
        confirmLabel="Reverse"
        variant="danger"
        busy={reverseMutation.isPending}
        onCancel={() => setReverseDialog(false)}
        onConfirm={() => reverseMutation.mutate()}
        body={
          <div className="space-y-3">
            <Field label="Posting date" required>
              <DateInput value={reverseDate} onChange={(e) => setReverseDate(e.target.value)} />
            </Field>
            <Field label="Reason">
              <TextArea
                value={reverseReason}
                onChange={(e) => setReverseReason(e.target.value)}
                placeholder="e.g. duplicate posting; supplier credit note"
                rows={3}
              />
            </Field>
            {reverseError && (
              <div className="rounded-md border border-status-error/30 bg-status-error-soft px-3 py-2 text-xs text-status-error">
                {reverseError}
              </div>
            )}
          </div>
        }
      />
    </div>
  );
}

const lineColumns: Column<JournalEntryLine>[] = [
  { key: "ln", header: "#", width: "40px", numeric: true, render: (l) => l.lineNumber },
  { key: "code", header: "GL", width: "70px", render: (l) => <span className="font-medium tabular-nums">{l.accountCode}</span> },
  { key: "name", header: "Account", render: (l) => <span>{l.accountName}</span> },
  { key: "dr", header: "Debit", numeric: true, width: "110px", render: (l) => formatDr(l.debitAmount) },
  { key: "cr", header: "Credit", numeric: true, width: "110px", render: (l) => formatCr(l.creditAmount) },
  { key: "desc", header: "Description", render: (l) => <span className="text-text-muted">{l.description || "—"}</span> },
];

function formatMoney(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
function formatDr(v: string): string {
  const n = Number(v);
  return n > 0 ? n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : "—";
}
function formatCr(v: string): string {
  const n = Number(v);
  return n > 0 ? n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : "—";
}
function shortUuid(uuid: string): string {
  return uuid ? `${uuid.slice(0, 8)}…${uuid.slice(-4)}` : "—";
}

// ----- Reverse by source -----

function ReverseBySourceSection() {
  const [sourceDocType, setSourceDocType] = useState(SOURCE_DOC_TYPES[0]);
  const [sourceDocId, setSourceDocId] = useState("");
  const [reason, setReason] = useState("");
  const [postingDate, setPostingDate] = useState(new Date().toISOString().slice(0, 10));
  const [confirm, setConfirm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ReverseBySourceResponse | null>(null);
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: async () => {
      return apiPost<ReverseBySourceResponse>("/api/journal-entries/reverse-by-source", {
        sourceDocumentType: sourceDocType,
        sourceDocumentId: sourceDocId.trim(),
        reason: reason.trim(),
        postingDate,
      });
    },
    onSuccess: (data) => {
      setResult(data);
      setConfirm(false);
      queryClient.invalidateQueries({ queryKey: ["journal-entries"] });
    },
    onError: (err) => {
      setError(err instanceof ApiError ? err.message : "Reverse failed.");
      setConfirm(false);
    },
  });

  function open() {
    if (!sourceDocId.trim()) {
      setError("Source document id is required.");
      return;
    }
    setError(null);
    setResult(null);
    setConfirm(true);
  }

  return (
    <section>
      <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-text-primary">
        <Layers className="h-4 w-4 text-text-muted" />
        Reverse by source document
      </h2>

      <FormSection
        description="Reverses every posted journal entry whose (sourceDocumentType, sourceDocumentId) matches. Already-reversed entries are skipped."
        columns={2}
      >
        <Field label="Source document type" required>
          <Select value={sourceDocType} onChange={(e) => setSourceDocType(e.target.value)}>
            {SOURCE_DOC_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </Select>
        </Field>
        <Field label="Source document id" required>
          <TextInput
            value={sourceDocId}
            onChange={(e) => setSourceDocId(e.target.value)}
            placeholder="UUID"
          />
        </Field>
        <Field label="Posting date" required>
          <DateInput value={postingDate} onChange={(e) => setPostingDate(e.target.value)} />
        </Field>
        <Field label="Reason" fullWidth>
          <TextArea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="e.g. cancel customer invoice INV-0042"
            rows={3}
          />
        </Field>
      </FormSection>

      <div className="mt-3 flex items-center justify-between gap-3">
        {error && (
          <span className="text-xs text-status-error">{error}</span>
        )}
        {result && (
          <span className="text-xs text-status-success">
            Reversed {result.reversedCount} {result.reversedCount === 1 ? "entry" : "entries"}.
          </span>
        )}
        <ActionButton
          variant="danger"
          icon={<Undo2 className="h-4 w-4" />}
          onClick={open}
          requiresRole="finance_manager"
          className="ml-auto"
        >
          Reverse all matching
        </ActionButton>
      </div>

      <ConfirmDialog
        open={confirm}
        title="Bulk-reverse journal entries?"
        message={
          <>
            Reverses every posted journal entry whose source matches{" "}
            <code>{sourceDocType}</code> / <code>{shortUuid(sourceDocId)}</code>. Reversals
            are atomic in one transaction; already-reversed entries are skipped.
          </>
        }
        confirmLabel="Reverse all matching"
        variant="danger"
        busy={mutation.isPending}
        onCancel={() => setConfirm(false)}
        onConfirm={() => mutation.mutate()}
      />
    </section>
  );
}
