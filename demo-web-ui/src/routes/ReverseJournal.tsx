import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { fetchJournalEntries } from "@/api/fetchers";
import { reverseJournalEntry, reverseBySource } from "@/api/commands";
import type { JournalEntrySummary } from "@/api/types";
import { Button, FieldRow, FormCard, FormStatus, Input, PersonaTag, Select, type SubmitState } from "@/components/ui/Form";
import { formatMoney, truncateUuid } from "@/lib/utils";
import { PERSONAS } from "@/personas";

const SOURCE_TYPES = [
  { value: "supplier_invoice",   label: "supplier_invoice" },
  { value: "supplier_payment",   label: "supplier_payment" },
  { value: "customer_invoice",   label: "customer_invoice" },
  { value: "customer_payment",   label: "customer_payment" },
  { value: "goods_receipt",      label: "goods_receipt" },
  { value: "shipment_cost",      label: "shipment_cost" },
];

export function ReverseJournal() {
  const persona = PERSONAS.daniel;
  return (
    <div className="space-y-6">
      <div className="flex items-baseline gap-3">
        <h1 className="text-[28px] font-semibold tracking-tight">Reverse a journal</h1>
        <PersonaTag {...persona} />
        <Link to="/journal-entries" className="ml-auto text-sm text-text-muted hover:text-text-primary">
          ← back to journal list
        </Link>
      </div>
      <p className="text-sm text-text-muted">
        Two paths: reverse a single posted journal (by id) or bulk-reverse every journal that came from
        the same source document. Reversal posts a balancing entry and flips the original to{" "}
        <span className="font-mono">reversed</span> in the same transaction.
      </p>

      <SingleReverse />
      <BulkReverse />
    </div>
  );
}

function SingleReverse() {
  const queryClient = useQueryClient();
  const { data } = useQuery({
    queryKey: ["journal-entries", "for-reverse"],
    queryFn: () => fetchJournalEntries(),
  });
  const postedOnly: JournalEntrySummary[] = (data ?? []).filter((j) => j.status === "posted");

  const [journalId, setJournalId] = useState("");
  const [reason, setReason] = useState("");
  const [postingDate, setPostingDate] = useState("");
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  async function onSubmit() {
    setSubmit({ status: "submitting" });
    try {
      const result = await reverseJournalEntry(journalId, {
        reason,
        postingDate: postingDate || undefined,
      });
      setSubmit({
        status: "success",
        message: `reversed → ${result.journalNumber ?? truncateUuid(result.journalEntryHeaderId ?? "")}`,
      });
      queryClient.invalidateQueries({ queryKey: ["journal-entries"] });
      setReason("");
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <FormCard title="Reverse a single journal">
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <FieldRow label="Journal" required>
          <Select value={journalId} onChange={(e) => setJournalId(e.target.value)}>
            <option value="">— pick a posted journal —</option>
            {postedOnly.map((j) => (
              <option key={j.journalEntryHeaderId} value={j.journalEntryHeaderId}>
                {j.journalNumber} · {j.sourceDocumentType} ·{" "}
                {formatMoney(j.totalAmount, j.currencyCode)}
              </option>
            ))}
          </Select>
        </FieldRow>
        <FieldRow label="Reason" required>
          <Input value={reason} onChange={(e) => setReason(e.target.value)} />
        </FieldRow>
        <FieldRow label="Posting date" hint="Defaults to today">
          <Input type="date" value={postingDate} onChange={(e) => setPostingDate(e.target.value)} />
        </FieldRow>
      </div>
      <div className="mt-4 flex items-center justify-end gap-3">
        <FormStatus state={submit} />
        <Button
          variant="primary"
          onClick={onSubmit}
          disabled={submit.status === "submitting" || !journalId || !reason}
        >
          Reverse journal
        </Button>
      </div>
    </FormCard>
  );
}

function BulkReverse() {
  const queryClient = useQueryClient();
  const [sourceDocumentType, setSourceDocumentType] = useState(SOURCE_TYPES[0].value);
  const [sourceDocumentId, setSourceDocumentId] = useState("");
  const [reason, setReason] = useState("");
  const [postingDate, setPostingDate] = useState("");
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  async function onSubmit() {
    setSubmit({ status: "submitting" });
    try {
      const result = await reverseBySource({
        sourceDocumentType,
        sourceDocumentId,
        reason,
        postingDate: postingDate || undefined,
      });
      setSubmit({
        status: "success",
        message: `reversed ${result.reversedCount} journal${result.reversedCount === 1 ? "" : "s"}`,
      });
      queryClient.invalidateQueries({ queryKey: ["journal-entries"] });
      setReason("");
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <FormCard title="Bulk reverse by source document">
      <p className="mb-3 text-xs text-text-muted">
        Reverses every <span className="font-mono">posted</span> journal that came from the given source
        (<span className="font-mono">supplier_invoice</span> + invoice id, etc.). Idempotent —
        already-reversed entries are skipped.
      </p>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <FieldRow label="Source document type" required>
          <Select value={sourceDocumentType} onChange={(e) => setSourceDocumentType(e.target.value)}>
            {SOURCE_TYPES.map((s) => (
              <option key={s.value} value={s.value}>{s.label}</option>
            ))}
          </Select>
        </FieldRow>
        <FieldRow label="Source document id" required hint="UUID of the source aggregate">
          <Input value={sourceDocumentId} onChange={(e) => setSourceDocumentId(e.target.value)} />
        </FieldRow>
        <FieldRow label="Reason" required>
          <Input value={reason} onChange={(e) => setReason(e.target.value)} />
        </FieldRow>
        <FieldRow label="Posting date" hint="Defaults to today">
          <Input type="date" value={postingDate} onChange={(e) => setPostingDate(e.target.value)} />
        </FieldRow>
      </div>
      <div className="mt-4 flex items-center justify-end gap-3">
        <FormStatus state={submit} />
        <Button
          variant="destructive"
          onClick={onSubmit}
          disabled={submit.status === "submitting" || !sourceDocumentId || !reason}
        >
          Reverse all journals from this source
        </Button>
      </div>
    </FormCard>
  );
}
