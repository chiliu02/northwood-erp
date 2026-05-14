import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Calculator } from "lucide-react";
import { apiGet, ApiError } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { FormSection, Field, ReadOnlyField } from "@/components/ui/FormSection";
import { TextInput, DateInput } from "@/components/ui/Form";

interface RateResponse {
  fromCurrency: string;
  toCurrency: string;
  rate: string;
  effectiveDate: string;
}

const COMMON_CURRENCIES = ["AUD", "USD", "EUR", "GBP", "NZD", "JPY", "CNY"];

/**
 * Ad-hoc exchange-rate lookup. Wraps `GET /api/exchange-rate`. Useful
 * for the operator who just wants to see "what rate are we using
 * for today's USD invoice?" without scraping a transaction header.
 */
export function ExchangeRate() {
  const [from, setFrom] = useState("USD");
  const [to, setTo] = useState("AUD");
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<RateResponse | null>(null);

  const mutation = useMutation({
    mutationFn: async () => {
      const params = new URLSearchParams({ from, to, date });
      return apiGet<RateResponse>(`/api/exchange-rate?${params.toString()}`);
    },
    onSuccess: (data) => {
      setResult(data);
      setError(null);
    },
    onError: (err) => {
      setResult(null);
      if (err instanceof ApiError && err.status === 404) {
        setError(`No rate on file for ${from} → ${to} effective on or before ${date}.`);
      } else {
        setError(err instanceof Error ? err.message : "Lookup failed.");
      }
    },
  });

  function lookup() {
    if (from === to) {
      setResult({
        fromCurrency: from,
        toCurrency: to,
        rate: "1.000000",
        effectiveDate: date,
      });
      setError(null);
      return;
    }
    if (!from.match(/^[A-Z]{3}$/) || !to.match(/^[A-Z]{3}$/)) {
      setError("Currency codes must be 3 uppercase letters.");
      return;
    }
    setError(null);
    mutation.mutate();
  }

  return (
    <>
      <PageHeader
        title="Exchange Rate Lookup"
        description="Look up a stamped FX rate by (from currency, to currency, effective date). Same lookup used by transaction posting."
        trail={[
          { label: "Home", to: "/" },
          { label: "Finance" },
          { label: "Exchange Rate" },
        ]}
      />

      <div className="px-8 py-6">
        <FormSection columns={3} className="max-w-2xl">
          <Field label="From currency" required>
            <TextInput
              value={from}
              onChange={(e) => setFrom(e.target.value.toUpperCase())}
              maxLength={3}
              list="erp-currency-list"
            />
          </Field>
          <Field label="To currency" required>
            <TextInput
              value={to}
              onChange={(e) => setTo(e.target.value.toUpperCase())}
              maxLength={3}
              list="erp-currency-list"
            />
          </Field>
          <Field label="Effective on or before" required>
            <DateInput value={date} onChange={(e) => setDate(e.target.value)} />
          </Field>
          <datalist id="erp-currency-list">
            {COMMON_CURRENCIES.map((c) => <option key={c} value={c} />)}
          </datalist>
        </FormSection>

        <div className="mt-3 flex max-w-2xl items-center justify-between gap-3">
          {error && <span className="text-xs text-status-error">{error}</span>}
          <ActionButton
            variant="primary"
            icon={<Calculator className="h-4 w-4" />}
            onClick={lookup}
            disabled={mutation.isPending}
            className="ml-auto"
          >
            {mutation.isPending ? "Looking up…" : "Look up"}
          </ActionButton>
        </div>

        {result && (
          <div className="mt-6 max-w-2xl">
            <FormSection title="Resolved rate" columns={2}>
              <ReadOnlyField label="From" value={<span className="font-medium tabular-nums">{result.fromCurrency}</span>} />
              <ReadOnlyField label="To" value={<span className="font-medium tabular-nums">{result.toCurrency}</span>} />
              <ReadOnlyField label="Rate" value={<span className="text-lg font-semibold tabular-nums">{result.rate}</span>} />
              <ReadOnlyField label="Effective date" value={<span className="tabular-nums">{result.effectiveDate}</span>} />
            </FormSection>
            <p className="mt-3 text-xs text-text-muted">
              Latest rate row matching <code>effective_date ≤ {date}</code>.
              {result.fromCurrency !== result.toCurrency &&
                " Resolved via direct pair or inverse-rate fallback (no triangulation today)."}
            </p>
          </div>
        )}
      </div>
    </>
  );
}
