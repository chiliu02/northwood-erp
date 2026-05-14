import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Save, Search } from "lucide-react";
import { apiGet, apiPut, ApiError } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { FormSection, Field } from "@/components/ui/FormSection";
import { TextInput, NumberInput } from "@/components/ui/Form";
import { DataGrid, type Column } from "@/components/ui/DataGrid";

interface PriceRow {
  supplierProductPriceId: string;
  supplierId: string;
  productId: string;
  currencyCode: string;
  unitPrice: string;
  version: number;
}

/**
 * Supplier price authoring — Tom's screen for the §3.10 setPrice flow.
 * Two narrow workflows side by side:
 *
 *   1. List prices for a supplier (lookup by supplier id, GET
 *      /api/supplier-product-prices/by-supplier/{supplierId}).
 *   2. Author a price (PUT /api/supplier-product-prices) — submitting the
 *      same price twice triggers the §3.10 no-op suppression on the
 *      backend (no event, no version bump). The "Saved" feedback shows
 *      regardless; the actual no-op vs change is visible in finance logs.
 */
export function SupplierPrices() {
  return (
    <>
      <PageHeader
        title="Supplier Prices"
        description="Author the supplier price list. Used by PurchaseOrderService when converting a requisition to a PO."
        trail={[
          { label: "Home", to: "/" },
          { label: "Purchasing" },
          { label: "Supplier Prices" },
        ]}
      />
      <div className="space-y-8 px-8 py-6">
        <ListBySupplierSection />
        <div className="border-t border-border-default" />
        <AuthorPriceSection />
      </div>
    </>
  );
}

// ----- List prices for a supplier -----

function ListBySupplierSection() {
  const [pendingId, setPendingId] = useState("");
  const [supplierId, setSupplierId] = useState<string | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ["prices-by-supplier", supplierId],
    queryFn: () => apiGet<PriceRow[]>(`/api/supplier-product-prices/by-supplier/${supplierId}`),
    enabled: !!supplierId,
  });

  const columns: Column<PriceRow>[] = [
    {
      key: "product",
      header: "Product id",
      render: (r) => <code className="text-xs text-text-muted">{shortUuid(r.productId)}</code>,
    },
    {
      key: "currency",
      header: "Currency",
      width: "100px",
      render: (r) => r.currencyCode,
    },
    {
      key: "price",
      header: "Unit Price",
      numeric: true,
      width: "130px",
      render: (r) => formatMoney(r.unitPrice),
    },
    {
      key: "version",
      header: "Version",
      numeric: true,
      width: "80px",
      render: (r) => <span className="text-text-muted">{r.version}</span>,
    },
  ];

  return (
    <section>
      <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-text-primary">
        <Search className="h-4 w-4 text-text-muted" />
        Prices by supplier
      </h2>
      <FormSection columns={1}>
        <div className="flex gap-2">
          <TextInput
            placeholder="Supplier id (UUID)"
            value={pendingId}
            onChange={(e) => setPendingId(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") setSupplierId(pendingId.trim() || null); }}
          />
          <ActionButton variant="primary" onClick={() => setSupplierId(pendingId.trim() || null)}>Search</ActionButton>
        </div>
      </FormSection>

      <div className="mt-4">
        {!supplierId ? (
          <div className="rounded-md border border-dashed border-border-default px-4 py-8 text-center text-sm text-text-muted">
            Enter a supplier id and press <kbd className="rounded bg-bg-subtle px-1.5 py-0.5 text-xs">Enter</kbd> or click <strong>Search</strong> to list the prices on file for that supplier.
          </div>
        ) : error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(r) => r.supplierProductPriceId}
            loading={isLoading}
            emptyState="No prices on file for that supplier."
          />
        )}
      </div>
    </section>
  );
}

// ----- Author a price -----

function AuthorPriceSection() {
  const queryClient = useQueryClient();
  const [supplierId, setSupplierId] = useState("");
  const [productId, setProductId] = useState("");
  const [currencyCode, setCurrencyCode] = useState("AUD");
  const [unitPrice, setUnitPrice] = useState("");
  const [feedback, setFeedback] = useState<{ type: "success" | "error"; message: string } | null>(null);

  const mutation = useMutation({
    mutationFn: () => apiPut("/api/supplier-product-prices", {
      supplierId: supplierId.trim(),
      productId: productId.trim(),
      currencyCode,
      unitPrice: Number(unitPrice),
    }),
    onSuccess: () => {
      setFeedback({ type: "success", message: `Saved. (Re-submitting the same price will be a no-op on the backend per §3.10.)` });
      queryClient.invalidateQueries({ queryKey: ["prices-by-supplier"] });
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : "Save failed.";
      setFeedback({ type: "error", message: msg });
    },
  });

  function submit() {
    if (!supplierId.trim() || !productId.trim() || !unitPrice) {
      setFeedback({ type: "error", message: "Supplier, product, and unit price are all required." });
      return;
    }
    setFeedback(null);
    mutation.mutate();
  }

  return (
    <section>
      <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-text-primary">
        <Save className="h-4 w-4 text-text-muted" />
        Set / update a price
      </h2>

      <FormSection
        description="PUTs to /api/supplier-product-prices. Emits SupplierProductPriceChanged on change. Identical re-submits are a no-op (no event, no version bump)."
        columns={2}
      >
        <Field label="Supplier id" required fullWidth>
          <TextInput placeholder="UUID" value={supplierId} onChange={(e) => setSupplierId(e.target.value)} />
        </Field>
        <Field label="Product id" required fullWidth>
          <TextInput placeholder="UUID" value={productId} onChange={(e) => setProductId(e.target.value)} />
        </Field>
        <Field label="Currency" required>
          <TextInput value={currencyCode} onChange={(e) => setCurrencyCode(e.target.value.toUpperCase())} maxLength={3} />
        </Field>
        <Field label="Unit price" required>
          <NumberInput min="0" step="0.01" value={unitPrice} onChange={(e) => setUnitPrice(e.target.value)} />
        </Field>
      </FormSection>

      <div className="mt-3 flex items-center justify-between gap-3">
        {feedback && (
          <span
            className={
              feedback.type === "success"
                ? "text-xs text-status-success"
                : "text-xs text-status-error"
            }
          >
            {feedback.message}
          </span>
        )}
        <ActionButton
          variant="primary"
          icon={<Save className="h-4 w-4" />}
          onClick={submit}
          disabled={mutation.isPending}
          requiresRole="purchasing_manager"
          className="ml-auto"
        >
          {mutation.isPending ? "Saving…" : "Save price"}
        </ActionButton>
      </div>
    </section>
  );
}

function formatMoney(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
function shortUuid(uuid: string): string {
  return uuid ? `${uuid.slice(0, 8)}…${uuid.slice(-4)}` : "—";
}
