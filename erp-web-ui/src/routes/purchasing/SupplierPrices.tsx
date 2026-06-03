import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Plus, Filter, Download } from "lucide-react";
import { apiGet, apiPut, ApiError } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { FilterPanel, useFieldFilters, type FilterField } from "@/components/ui/FilterPanel";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { Field } from "@/components/ui/FormSection";
import { TextInput, NumberInput } from "@/components/ui/Form";
import { downloadCsv } from "@/lib/csv";

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

type Dialog =
  | { mode: "add" }
  | { mode: "edit"; row: SupplierPriceRow }
  | null;

/**
 * Supplier price list — Tom's screen. Lists every supplier price (enriched
 * with supplier + product names), filterable + exportable like the other list
 * pages. Click a row to edit its unit price; "New price" adds one. Both PUT to
 * /api/supplier-product-prices (resubmitting the same price is a backend no-op).
 */
export function SupplierPrices() {
  const [dialog, setDialog] = useState<Dialog>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ["supplier-prices"],
    queryFn: () => apiGet<SupplierPriceRow[]>("/api/supplier-product-prices"),
  });

  const filterFields: FilterField<SupplierPriceRow>[] = [
    { key: "supplier", label: "Supplier", get: (r) => `${r.supplierName ?? ""} ${r.supplierCode ?? ""}` },
    { key: "product", label: "Product", get: (r) => `${r.productName ?? ""} ${r.productSku ?? ""}` },
    { key: "currency", label: "Currency", type: "select", get: (r) => r.currencyCode },
  ];
  const filter = useFieldFilters(data ?? [], filterFields);

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
      width: "140px",
      sortAccessor: (r) => Number(r.unitPrice),
      render: (r) => formatMoney(r.unitPrice),
    },
    {
      key: "minQty",
      header: "Min Qty",
      numeric: true,
      width: "110px",
      sortAccessor: (r) => Number(r.minQuantity),
      render: (r) => (Number(r.minQuantity) > 0 ? formatQty(r.minQuantity) : <span className="text-text-muted">—</span>),
    },
  ];

  return (
    <>
      <PageHeader
        title="Supplier Prices"
        description="The supplier price list, used by PurchaseOrderService when converting a requisition to a PO. Click a row to edit its price."
        trail={[
          { label: "Purchasing" },
          { label: "Supplier Prices" },
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
              onClick={() => downloadCsv("supplier-prices.csv", filter.filtered)}
              disabled={filter.filtered.length === 0}
            >
              Export
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Plus className="h-4 w-4" />}
              requiresRole="purchasing_manager"
              onClick={() => setDialog({ mode: "add" })}
            >
              New price
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
            Failed to load supplier prices: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={filter.filtered}
            rowKey={(r) => r.supplierProductPriceId}
            onRowClick={(r) => setDialog({ mode: "edit", row: r })}
            loading={isLoading}
            emptyState={filter.active ? "No prices match the filter." : "No supplier prices on file. Click 'New price' to add one."}
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {filter.filtered.length} {filter.filtered.length === 1 ? "price" : "prices"}
            {filter.active ? ` (filtered from ${data.length})` : ""}.
          </div>
        )}
      </div>

      <PriceDialog dialog={dialog} onClose={() => setDialog(null)} />
    </>
  );
}

// ----- Add / edit price dialog -----

function PriceDialog({ dialog, onClose }: { dialog: Dialog; onClose: () => void }) {
  const queryClient = useQueryClient();
  const isEdit = dialog?.mode === "edit";
  const row = dialog?.mode === "edit" ? dialog.row : null;

  const [supplierId, setSupplierId] = useState("");
  const [productId, setProductId] = useState("");
  const [currencyCode, setCurrencyCode] = useState("AUD");
  const [unitPrice, setUnitPrice] = useState("");
  const [error, setError] = useState<string | null>(null);
  // Re-seed the fields whenever a different dialog opens.
  const [seededFor, setSeededFor] = useState<string | null>(null);
  const dialogKey = dialog ? (dialog.mode === "edit" ? dialog.row.supplierProductPriceId : "add") : null;
  if (dialog && dialogKey !== seededFor) {
    setSeededFor(dialogKey);
    setSupplierId(row?.supplierId ?? "");
    setProductId(row?.productId ?? "");
    setCurrencyCode(row?.currencyCode ?? "AUD");
    setUnitPrice(row?.unitPrice ?? "");
    setError(null);
  }

  const mutation = useMutation({
    mutationFn: () => apiPut("/api/supplier-product-prices", {
      supplierId: supplierId.trim(),
      productId: productId.trim(),
      currencyCode,
      unitPrice: Number(unitPrice),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["supplier-prices"] });
      onClose();
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : "Save failed."),
  });

  function submit() {
    if (!supplierId.trim() || !productId.trim() || !unitPrice) {
      setError("Supplier, product, and unit price are all required.");
      return;
    }
    setError(null);
    mutation.mutate();
  }

  return (
    <ConfirmDialog
      open={dialog !== null}
      title={isEdit ? "Edit supplier price" : "New supplier price"}
      message={
        isEdit ? (
          <>Update the unit price for <strong>{row?.productName ?? row?.productSku}</strong> from <strong>{row?.supplierName}</strong>. Emits <code>SupplierProductPriceChanged</code> on change; an identical price is a no-op.</>
        ) : (
          <>Add a price for a (supplier, product, currency). Emits <code>SupplierProductPriceChanged</code>. Supplier + product are entered by id.</>
        )
      }
      confirmLabel={isEdit ? "Save" : "Add"}
      busy={mutation.isPending}
      onCancel={onClose}
      onConfirm={submit}
      body={
        <div className="space-y-3">
          {isEdit ? (
            <>
              <Field label="Supplier">
                <TextInput value={`${row?.supplierName ?? ""} (${row?.supplierCode ?? shortUuid(row?.supplierId ?? "")})`} disabled />
              </Field>
              <Field label="Product">
                <TextInput value={`${row?.productName ?? ""} (${row?.productSku ?? shortUuid(row?.productId ?? "")})`} disabled />
              </Field>
            </>
          ) : (
            <>
              <Field label="Supplier id" required>
                <TextInput placeholder="UUID" value={supplierId} onChange={(e) => setSupplierId(e.target.value)} />
              </Field>
              <Field label="Product id" required>
                <TextInput placeholder="UUID" value={productId} onChange={(e) => setProductId(e.target.value)} />
              </Field>
            </>
          )}
          <div className="grid grid-cols-2 gap-3">
            <Field label="Currency" required>
              <TextInput value={currencyCode} onChange={(e) => setCurrencyCode(e.target.value.toUpperCase())} maxLength={3} disabled={isEdit} />
            </Field>
            <Field label="Unit price" required>
              <NumberInput min="0" step="0.01" value={unitPrice} onChange={(e) => setUnitPrice(e.target.value)} autoFocus />
            </Field>
          </div>
          {error && (
            <div className="rounded-md border border-status-error/30 bg-status-error-soft px-3 py-2 text-xs text-status-error">
              {error}
            </div>
          )}
        </div>
      }
    />
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
