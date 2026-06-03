import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Save } from "lucide-react";
import { apiGet, apiPut, ApiError } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { FormSection, Field } from "@/components/ui/FormSection";
import { TextInput, NumberInput } from "@/components/ui/Form";
import { DataGrid, type Column } from "@/components/ui/DataGrid";

/** Enriched list row from {@code GET /api/supplier-product-prices}. */
interface SupplierPriceRow {
  supplierProductPriceId: string;
  supplierId: string;
  supplierCode: string | null;
  supplierName: string | null;
  productId: string;
  productSku: string | null;
  productName: string | null;
  currencyCode: string;
  unitPrice: string;
  minQuantity: string;
  version: number;
}

interface PrefillState {
  supplierId: string;
  productId: string;
  currencyCode: string;
  unitPrice: string;
}

/**
 * Supplier price authoring — Tom's screen for the setPrice flow.
 *
 *   1. All prices: the full supplier price list ({@code GET
 *      /api/supplier-product-prices}), enriched with supplier + product names.
 *      Click a row to load it into the editor below.
 *   2. Author a price (PUT /api/supplier-product-prices) — resubmitting the
 *      same price is a no-op on the backend (no event, no version bump).
 */
export function SupplierPrices() {
  const [editor, setEditor] = useState<PrefillState>({
    supplierId: "",
    productId: "",
    currencyCode: "AUD",
    unitPrice: "",
  });

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
        <AllPricesSection onSelect={setEditor} />
        <div className="border-t border-border-default" />
        <AuthorPriceSection editor={editor} setEditor={setEditor} />
      </div>
    </>
  );
}

// ----- All prices -----

function AllPricesSection({ onSelect }: { onSelect: (p: PrefillState) => void }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ["supplier-prices"],
    queryFn: () => apiGet<SupplierPriceRow[]>("/api/supplier-product-prices"),
  });

  const columns: Column<SupplierPriceRow>[] = [
    {
      key: "supplier",
      header: "Supplier",
      sortAccessor: (r) => r.supplierName ?? "",
      render: (r) => (
        <div>
          <div className="text-text-primary">{r.supplierName ?? "—"}</div>
          <div className="text-[11px] text-text-muted tabular-nums">{r.supplierCode ?? shortUuid(r.supplierId)}</div>
        </div>
      ),
    },
    {
      key: "product",
      header: "Product",
      sortAccessor: (r) => r.productSku ?? "",
      render: (r) => (
        <div>
          <div className="text-text-primary">{r.productName ?? "—"}</div>
          <div className="text-[11px] text-text-muted tabular-nums">{r.productSku ?? shortUuid(r.productId)}</div>
        </div>
      ),
    },
    {
      key: "currency",
      header: "Currency",
      width: "100px",
      sortAccessor: (r) => r.currencyCode,
      render: (r) => r.currencyCode,
    },
    {
      key: "price",
      header: "Unit Price",
      numeric: true,
      width: "130px",
      sortAccessor: (r) => Number(r.unitPrice),
      render: (r) => formatMoney(r.unitPrice),
    },
    {
      key: "minQty",
      header: "Min Qty",
      numeric: true,
      width: "100px",
      sortAccessor: (r) => Number(r.minQuantity),
      render: (r) => (Number(r.minQuantity) > 0 ? formatQty(r.minQuantity) : <span className="text-text-muted">—</span>),
    },
    {
      key: "version",
      header: "Version",
      numeric: true,
      width: "80px",
      sortAccessor: (r) => r.version,
      render: (r) => <span className="text-text-muted">{r.version}</span>,
    },
  ];

  return (
    <section>
      <h2 className="mb-3 text-sm font-semibold text-text-primary">All prices</h2>

      {error ? (
        <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
          Failed to load supplier prices: {(error as Error).message}
        </div>
      ) : (
        <DataGrid
          columns={columns}
          rows={data ?? []}
          rowKey={(r) => r.supplierProductPriceId}
          onRowClick={(r) =>
            onSelect({
              supplierId: r.supplierId,
              productId: r.productId,
              currencyCode: r.currencyCode,
              unitPrice: r.unitPrice,
            })
          }
          loading={isLoading}
          emptyState="No supplier prices on file. Author one below."
        />
      )}
      {data && (
        <div className="mt-3 text-xs text-text-muted">
          {data.length} {data.length === 1 ? "price" : "prices"}. Click a row to load it into the editor below.
        </div>
      )}
    </section>
  );
}

// ----- Author a price -----

function AuthorPriceSection({ editor, setEditor }: { editor: PrefillState; setEditor: (p: PrefillState) => void }) {
  const queryClient = useQueryClient();
  const [feedback, setFeedback] = useState<{ type: "success" | "error"; message: string } | null>(null);

  const set = (patch: Partial<PrefillState>) => setEditor({ ...editor, ...patch });

  const mutation = useMutation({
    mutationFn: () => apiPut("/api/supplier-product-prices", {
      supplierId: editor.supplierId.trim(),
      productId: editor.productId.trim(),
      currencyCode: editor.currencyCode,
      unitPrice: Number(editor.unitPrice),
    }),
    onSuccess: () => {
      setFeedback({ type: "success", message: `Saved. (Re-submitting the same price is a no-op on the backend.)` });
      queryClient.invalidateQueries({ queryKey: ["supplier-prices"] });
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : "Save failed.";
      setFeedback({ type: "error", message: msg });
    },
  });

  function submit() {
    if (!editor.supplierId.trim() || !editor.productId.trim() || !editor.unitPrice) {
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
        description="PUTs to /api/supplier-product-prices. Emits SupplierProductPriceChanged on change. Identical re-submits are a no-op (no event, no version bump). Click a row above to pre-fill these fields."
        columns={2}
      >
        <Field label="Supplier id" required fullWidth>
          <TextInput placeholder="UUID" value={editor.supplierId} onChange={(e) => set({ supplierId: e.target.value })} />
        </Field>
        <Field label="Product id" required fullWidth>
          <TextInput placeholder="UUID" value={editor.productId} onChange={(e) => set({ productId: e.target.value })} />
        </Field>
        <Field label="Currency" required>
          <TextInput value={editor.currencyCode} onChange={(e) => set({ currencyCode: e.target.value.toUpperCase() })} maxLength={3} />
        </Field>
        <Field label="Unit price" required>
          <NumberInput min="0" step="0.01" value={editor.unitPrice} onChange={(e) => set({ unitPrice: e.target.value })} />
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
function formatQty(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 3 });
}
function shortUuid(uuid: string): string {
  return uuid ? `${uuid.slice(0, 8)}…${uuid.slice(-4)}` : "—";
}
